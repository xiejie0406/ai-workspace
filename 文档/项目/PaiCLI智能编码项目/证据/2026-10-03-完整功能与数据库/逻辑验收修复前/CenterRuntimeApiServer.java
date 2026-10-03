package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.runtime.center.CenterCommandGateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** 原 Runtime 三接口的本机兼容门面，正式会话、turn 与事件只由中心持有。 */
public final class CenterRuntimeApiServer implements AutoCloseable {
    public static final int MAX_BODY_BYTES = 64 * 1024;
    public static final int MAX_EVENT_BYTES = 1024 * 1024;
    public static final int MAX_EVENTS = 200;
    private final CenterCommandGateway gateway;
    private final CenterCommandGateway.OpenSession binding;
    private final String expectedModelId;
    private final byte[] apiKey;
    private final HttpServer server;
    private final ThreadPoolExecutor workers;
    private final Semaphore active = new Semaphore(4);

    public CenterRuntimeApiServer(CenterCommandGateway gateway, CenterCommandGateway.OpenSession binding,
                                 String expectedModelId, int port, String apiKey) throws IOException {
        if (gateway == null || binding == null || !validId(binding.workspaceId()) || !validId(binding.runnerId()) || !validId(expectedModelId))
            throw new IllegalArgumentException("Runtime 必须固定中心工作区、Runner 和模型");
        if (apiKey == null || apiKey.isBlank() || apiKey.length() < 16 || apiKey.length() > 512 || apiKey.contains("\r") || apiKey.contains("\n"))
            throw new IllegalArgumentException("Runtime API 必须配置至少16位独立本机密钥");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Runtime 端口非法");
        this.gateway = gateway; this.binding = binding; this.expectedModelId = expectedModelId; this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        workers = new ThreadPoolExecutor(4,8,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),task -> {
            Thread thread = new Thread(task,"paicli-center-runtime-api"); thread.setDaemon(true); return thread;
        },new ThreadPoolExecutor.AbortPolicy());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",port),32);
        server.createContext("/",this::handle); server.setExecutor(workers);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    public InetSocketAddress address() { return server.getAddress(); }

    private void handle(HttpExchange exchange) throws IOException {
        boolean acquired = false;
        try {
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress() || exchange.getRequestHeaders().containsKey("Origin")) {
                json(exchange,403,Map.of("error","loopback_only")); return;
            }
            Map<String,String> query = query(exchange.getRequestURI().getRawQuery());
            if (!authorized(exchange)) { json(exchange,401,Map.of("error","unauthorized")); return; }
            if (!(acquired = active.tryAcquire())) { json(exchange,503,Map.of("error","runtime_capacity","message","本机兼容请求已满，请稍后重试")); return; }
            String method = exchange.getRequestMethod(), path = exchange.getRequestURI().getPath();
            if ("GET".equals(method) && "/health".equals(path)) { json(exchange,200,Map.of("status","ready","authority","ruoyi-center")); return; }
            if ("POST".equals(method) && "/v1/threads".equals(path)) {
                readBody(exchange); // 即使旧协议不使用body，也必须限制读取容量。
                CenterCommandGateway.Session session = gateway.open(new CenterCommandGateway.OpenSession(binding.workspaceId(),binding.runnerId(),null));
                checkSession(session); json(exchange,200,Map.of("id","thread_"+session.id(),"object","thread")); return;
            }
            String[] parts = path.split("/");
            if (parts.length < 5 || !"v1".equals(parts[1]) || !"threads".equals(parts[2])) { json(exchange,404,Map.of("error","not_found")); return; }
            String sessionId = decodeId(parts[3],"thread_");
            CenterCommandGateway.Session session = gateway.open(new CenterCommandGateway.OpenSession(binding.workspaceId(),binding.runnerId(),sessionId));
            checkSession(session);
            if (parts.length == 5 && "turns".equals(parts[4]) && "POST".equals(method)) { turn(exchange,sessionId); return; }
            if (parts.length == 5 && "events".equals(parts[4]) && "GET".equals(method)) { events(exchange,sessionId,query); return; }
            if (parts.length == 7 && "turns".equals(parts[4]) && "cancel".equals(parts[6]) && "POST".equals(method)) {
                readBody(exchange); gateway.cancel(sessionId,decodeId(parts[5],"turn_"));
                json(exchange,202,Map.of("id",parts[5],"object","turn","status","cancelling")); return;
            }
            json(exchange,404,Map.of("error","not_found"));
        } catch (Boundary failure) { json(exchange,failure.status,Map.of("error",failure.code)); }
        catch (IllegalArgumentException failure) { json(exchange,400,Map.of("error","invalid_request")); }
        catch (IOException failure) { json(exchange,502,Map.of("error","center_unavailable","message","中心未确认操作，请核对中心状态后再提交")); }
        catch (RuntimeException failure) { json(exchange,500,Map.of("error","runtime_error")); }
        finally { if (acquired) active.release(); exchange.close(); }
    }
    private void turn(HttpExchange exchange,String sessionId) throws IOException {
        byte[] body = readBody(exchange); JsonNode input;
        try { input = CanonicalJson.MAPPER.readTree(body); }
        catch (IOException failure) { throw new Boundary(400,"invalid_json"); }
        if (input == null || !input.isObject() || !input.path("input").isTextual() || input.path("input").asText().isBlank()) throw new Boundary(400,"input_required");
        // 兼容本机Key不能改变中心身份、工作区、Runner、模型或批准模式。
        Set<String> fields = new HashSet<>(); input.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(Set.of("input"))) throw new Boundary(400,"unsupported_field");
        CenterCommandGateway.Turn turn = gateway.submit(sessionId,input.path("input").asText(),"ask",List.of());
        if (turn == null || !validId(turn.id())) throw new IOException("中心turn响应无效");
        json(exchange,202,Map.of("id","turn_"+turn.id(),"object","turn","status",turn.status().toLowerCase(Locale.ROOT)));
    }
    private void events(HttpExchange exchange,String sessionId,Map<String,String> query) throws IOException {
        long after = parseNumber(query.getOrDefault("after","0"),Long.MAX_VALUE);
        int limit = (int)parseNumber(query.getOrDefault("limit",String.valueOf(MAX_EVENTS)),MAX_EVENTS);
        if (limit < 1) throw new Boundary(400,"invalid_limit");
        List<CenterCommandGateway.Event> events = gateway.events(sessionId,after,limit);
        if (events == null || events.size() > limit) throw new Boundary(502,"invalid_center_events");
        StringBuilder stream = new StringBuilder(); Set<Long> seen = new HashSet<>(); long previous = after;
        for (CenterCommandGateway.Event event : events) {
            if (event.sequence() <= after || !seen.add(event.sequence())) continue;
            if (event.sequence() <= previous) throw new Boundary(502,"invalid_event_order"); previous = event.sequence();
            Map<String,Object> payload = new LinkedHashMap<>(event.payload());
            if (payload.get("taskId") != null) payload.put("turn_id","turn_"+payload.get("taskId"));
            String type = switch(event.type()) {
                case "TASK_ENQUEUED", "TURN_SUBMITTED" -> "turn.started";
                case "MODEL_DELTA", "MESSAGE" -> "message.delta";
                // 正式账本完整消息是快照，不能把已输出的delta再拼接一遍。
                case "LEDGER_MESSAGE" -> "message.snapshot";
                case "TASK_COMPLETED" -> "turn.completed";
                case "TASK_FAILED" -> "turn.failed";
                case "TASK_CANCELLED" -> "turn.cancelled";
                default -> event.type().toLowerCase(Locale.ROOT);
            };
            if (!type.matches("[A-Za-z0-9._:-]{1,128}")) throw new Boundary(502,"invalid_event_type");
            if (event.type().equals("MODEL_DELTA")) payload.put("content",Objects.toString(payload.get("delta"),""));
            if (event.type().equals("LEDGER_MESSAGE") && payload.get("message") instanceof Map<?,?> message) {
                payload.put("content",Objects.toString(message.get("content"),""));
                if (message.get("role") != null) payload.put("role",message.get("role"));
            }
            if (event.type().equals("TASK_COMPLETED")) payload.put("status","completed");
            if (event.type().equals("TASK_FAILED")) { payload.put("status","failed"); payload.put("error",Objects.toString(payload.getOrDefault("failureReason",payload.get("message")),"中心未提供失败原因")); }
            String data = CanonicalJson.MAPPER.writeValueAsString(payload);
            stream.append("id: ").append(event.sequence()).append('\n').append("event: ").append(type).append('\n').append("data: ").append(data).append("\n\n");
            if (stream.length() > MAX_EVENT_BYTES || stream.toString().getBytes(StandardCharsets.UTF_8).length > MAX_EVENT_BYTES) throw new Boundary(413,"event_snapshot_too_large");
        }
        write(exchange,200,"text/event-stream; charset=utf-8",stream.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void checkSession(CenterCommandGateway.Session session) {
        if (session == null || !validId(session.id()) || !binding.workspaceId().equals(session.workspaceId()) || !binding.runnerId().equals(session.runnerId()) || !expectedModelId.equals(session.modelId())) throw new Boundary(403,"thread_scope_mismatch");
    }
    private boolean authorized(HttpExchange exchange) {
        String bearer = exchange.getRequestHeaders().getFirst("Authorization"), direct = exchange.getRequestHeaders().getFirst("X-PaiCLI-API-Key");
        if (bearer != null && bearer.startsWith("Bearer ")) bearer = bearer.substring(7); else bearer = null;
        // 同时比较两条兼容header，不以明文String.equals检查密钥。
        boolean a = MessageDigest.isEqual(apiKey,Objects.toString(bearer,"").getBytes(StandardCharsets.UTF_8));
        boolean b = MessageDigest.isEqual(apiKey,Objects.toString(direct,"").getBytes(StandardCharsets.UTF_8)); return a | b;
    }
    private static byte[] readBody(HttpExchange exchange) throws IOException {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null && parseNumber(length,Long.MAX_VALUE) > MAX_BODY_BYTES) throw new Boundary(413,"request_too_large");
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES+1); if (bytes.length > MAX_BODY_BYTES) throw new Boundary(413,"request_too_large"); return bytes;
    }
    private static Map<String,String> query(String raw) {
        Map<String,String> result = new HashMap<>(); if (raw == null || raw.isBlank()) return result;
        for (String part:raw.split("&")) {
            String[] pair = part.split("=",2); String name = URLDecoder.decode(pair[0],StandardCharsets.UTF_8);
            if (!Set.of("after","limit").contains(name)) throw new Boundary(400,"unsupported_query");
            if (result.put(name,pair.length == 2 ? URLDecoder.decode(pair[1],StandardCharsets.UTF_8) : "") != null) throw new Boundary(400,"duplicate_query");
        }
        return result;
    }
    private static long parseNumber(String value,long maximum) {
        if (value == null || !value.matches("[0-9]{1,19}")) throw new Boundary(400,"invalid_number");
        try { long number = Long.parseLong(value); if (number > maximum) throw new Boundary(400,"invalid_number"); return number; }
        catch (NumberFormatException failure) { throw new Boundary(400,"invalid_number"); }
    }
    private static String decodeId(String value,String prefix) {
        if (value == null || !value.startsWith(prefix) || !validId(value.substring(prefix.length()))) throw new Boundary(404,"thread_not_found");
        return value.substring(prefix.length());
    }
    private static boolean validId(String value) { return value != null && value.matches("[1-9][0-9]{0,18}"); }
    private static void json(HttpExchange exchange,int status,Map<String,Object> data) throws IOException { write(exchange,status,"application/json; charset=utf-8",CanonicalJson.MAPPER.writeValueAsBytes(data)); }
    private static void write(HttpExchange exchange,int status,String type,byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type",type); exchange.getResponseHeaders().set("Cache-Control","no-store"); exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        exchange.sendResponseHeaders(status,body.length); try(var output=exchange.getResponseBody()) { output.write(body); }
    }
    private static final class Boundary extends RuntimeException { final int status; final String code; Boundary(int status,String code) { this.status=status;this.code=code; } }
    @Override public void close() throws IOException { server.stop(0); workers.shutdownNow(); Arrays.fill(apiKey,(byte)0); gateway.close(); }

    /** serve为显式启动，默认保持关闭；中心JWT和model从现有Gateway受控配置取得。 */
    public static int run(String[] args) {
        int port = 8080;
        try {
            for(int i = args.length > 0 && "serve".equals(args[0]) ? 1 : 0; i < args.length; i++) {
                if (!"--port".equals(args[i]) || ++i >= args.length) throw new IllegalArgumentException("用法：paicli serve [--port 端口]"); port = Integer.parseInt(args[i]);
            }
            String key = System.getProperty("paicli.runtime.api.key"); if (key == null || key.isBlank()) key = HttpCenterCommandGateway.requiredEnv("PAICLI_RUNTIME_API_KEY");
            CenterCommandGateway.OpenSession binding = new CenterCommandGateway.OpenSession(HttpCenterCommandGateway.requiredEnv("PAICLI_WORKSPACE_ID"),HttpCenterCommandGateway.requiredEnv("PAICLI_RUNNER_ID"),null);
            String modelId = HttpCenterCommandGateway.requiredEnv("PAICLI_MODEL_ID");
            try(CenterRuntimeApiServer server = new CenterRuntimeApiServer(new HttpCenterCommandGateway(modelId),binding,modelId,port,key)) {
                server.start(); System.out.println("PaiCLI Runtime兼容接口：http://127.0.0.1:"+server.port()+"；正式状态由若依中心持有");
                new CountDownLatch(1).await();
            }
            return 0;
        } catch(InterruptedException stopped) { Thread.currentThread().interrupt(); return 0; }
        catch(Exception failure) { System.err.println("Runtime启动失败，请核对固定工作区、Runner、模型、中心JWT和本机API Key配置。"); return 2; }
    }
}
