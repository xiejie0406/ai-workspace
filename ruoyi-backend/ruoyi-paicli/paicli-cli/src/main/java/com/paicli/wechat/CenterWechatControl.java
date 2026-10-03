package com.paicli.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.render.PlainRenderer;
import com.paicli.runner.*;
import com.paicli.runtime.CancellationContext;
import com.paicli.tool.ToolOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/** 微信原长轮询、渲染、队列和typing复用；文字任务只经中心绑定身份转发。 */
public final class CenterWechatControl implements NativeControlExecutor.Extension {
    private static final Map<String, Channel> CHANNELS = new ConcurrentHashMap<>();
    @Override public void close() { CHANNELS.values().forEach(Channel::close); CHANNELS.clear(); }
    @Override public boolean supports(String operation) { return operation.startsWith("WECHAT_"); }
    @Override public ToolOutput execute(String operation, JsonNode args, JsonNode context, CenterHttpClient center, RunnerLease lease, CenterToolRegistry tools) throws Exception {
        String id = required(args, "configId"); String version = required(args, "configVersion");
        Channel existing = CHANNELS.get(id);
        if (existing != null && !version.equals(existing.version)) { existing.close(); CHANNELS.remove(id); existing = null; }
        switch (operation) {
            case "WECHAT_STOP" -> { if (existing != null) existing.close(); CHANNELS.remove(id); result(center, lease, tools, id, version, "STOPPED", null); return ToolOutput.text("微信中心通道已停止"); }
            case "WECHAT_PAUSE", "WECHAT_RESUME" -> {
                if (existing == null) return ToolOutput.failure("微信通道未启动");
                if ("WECHAT_PAUSE".equals(operation)) existing.loop.pause(); else existing.loop.resume();
                result(center, lease, tools, id, version, "WECHAT_PAUSE".equals(operation) ? "PAUSED" : "RUNNING", null);
                return ToolOutput.text("微信中心通道状态已更新");
            }
            case "WECHAT_SETUP" -> {
                IlinkClient client = new IlinkClient(); WechatQrLogin qr = client.startQrLogin("3");
                result(center, lease, tools, id, version, "WAITING_QR", qr.qrcodeUrl());
                TerminalQrRenderer.print(System.out, qr.qrcodeUrl());
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
                while (System.nanoTime() < deadline) {
                    lease.ensureActive(); if (CancellationContext.isCancelled()) return ToolOutput.failure("微信绑定已取消");
                    WechatLoginResult login = client.pollQrStatus(qr.qrcodeId());
                    if (login.expired()) return ToolOutput.failure("微信二维码已过期，请重新绑定");
                    if (login.connected()) {
                        ObjectNode body = binding(args, tools);
                        body.put("token", login.token()); body.put("accountId", login.accountId()); body.put("baseUrl", login.baseUrl());
                        JsonNode saved = center.post("/paicli/runner/v1/wechat/account", body, UUID.randomUUID().toString());
                        result(center, lease, tools, id, required(saved, "configVersion"), "BOUND", null); return ToolOutput.text("微信账号已加密托管中心；启动后才消费消息");
                    }
                    Thread.sleep(3000);
                }
                return ToolOutput.failure("等待微信扫码超时");
            }
            case "WECHAT_START", "WECHAT_RESTART" -> {
                if (existing != null && "WECHAT_START".equals(operation)) return ToolOutput.text("微信通道已运行");
                if (existing != null) existing.close();
                JsonNode resolved = center.post("/paicli/runner/v1/wechat/context", binding(args, tools), UUID.randomUUID().toString());
                Bridge bridge = new Bridge(center.withRunnerToken(required(resolved, "bridgeKey")), resolved, version);
                JsonNode account = resolved.path("account");
                String workspaceId = required(account, "workspaceId");
                if (!workspaceId.equals(context.path("workspace").path("id").asText(context.path("workspaceId").asText()))) { bridge.close(); throw new IOException("微信中心绑定工作区不符"); }
                WechatAccount delegated = new WechatAccount(required(account, "token"), required(account, "accountId"), required(account, "baseUrl"), required(account, "boundUserId"), tools.getProjectPath(), account.path("syncBuf").asText(""), Instant.now().toString());
                IlinkClient client = new IlinkClient() {
                    @Override public WechatUpdate getUpdates(WechatAccount value, long timeout) throws IOException { bridge.ensureActive(); return super.getUpdates(value, timeout); }
                    private boolean boundSender(String user) { return user != null && bridge.senderHash.equals(CanonicalJson.sha256(user.getBytes(StandardCharsets.UTF_8))); }
                    @Override public void sendText(WechatAccount value, String user, String token, String text) throws IOException { if(!boundSender(user))throw new IOException("微信回复目标未匹配中心sender绑定");bridge.ensureActive(); super.sendText(value, user, token, text); }
                    @Override public void sendTyping(WechatAccount value, String user, String token, int status) throws IOException { if(!boundSender(user))throw new IOException("微信typing目标未匹配中心sender绑定");bridge.ensureActive(); super.sendTyping(value, user, token, status); }
                    @Override public void notifyStart(WechatAccount value) throws IOException { bridge.ensureActive(); super.notifyStart(value); }
                    @Override public void notifyStop(WechatAccount value) throws IOException { if (!bridge.closed) { bridge.ensureActive(); super.notifyStop(value); } }
                };
                WechatAccountStore cursor = new WechatAccountStore(Path.of(tools.getProjectPath())) {
                    @Override public void save(WechatAccount value) { try { ObjectNode body = bridge.identity(); body.put("syncBuf", value.syncBuf()); bridge.post("cursor", body); } catch (IOException failure) { throw new IllegalStateException("微信中心游标未确认", failure); } }
                };
                String senderHash=required(resolved,"senderHash"); if(!senderHash.matches("[a-f0-9]{64}")){bridge.close();throw new IOException("微信中心sender绑定无效");}
                WechatMessageLoop loop = new WechatMessageLoop(client, cursor, delegated, new PlainRenderer(), sender -> new RemoteSession(bridge), actual -> senderHash.equals(CanonicalJson.sha256(actual.getBytes(StandardCharsets.UTF_8))));
                Channel channel = new Channel(version, bridge, loop); CHANNELS.put(id, channel); channel.start();
                result(center, lease, tools, id, version, "RUNNING", null); return ToolOutput.text("微信通道已连接中心；文字任务按中心权限与审批执行");
            }
            default -> { return ToolOutput.failure("微信控制操作不受支持"); }
        }
    }
    private static ObjectNode binding(JsonNode args, CenterToolRegistry tools) throws IOException {
        ObjectNode value = tools.currentExecutionBinding(); if (value == null) throw new IOException("微信操作缺少已消费中心许可");
        value.set("configId", args.path("configId")); value.set("configVersion", args.path("configVersion")); return value;
    }
    private static void result(CenterHttpClient center, RunnerLease lease, CenterToolRegistry tools, String id, String version, String status, String qr) throws IOException {
        ObjectNode body = lease.identity(); String event = UUID.randomUUID().toString(); body.put("eventId", event); body.put("type", "CONNECTION_RESULT");
        ObjectNode payload = body.putObject("payload"); payload.put("kind", "WECHAT"); payload.put("configId", id); payload.put("configVersion", version); payload.put("status", status); if (qr != null) payload.put("qrcodeUrl", qr);
        if ("WAITING_QR".equals(status)) {
            try { center.post("/paicli/runner/v1/events", body, event); }
            catch (IOException failure) { lease.invalidate(); throw new java.io.UncheckedIOException("二维码通知未确认，微信绑定已暂停",failure); }
        } else NativeControlExecutor.sendControlEvent(center, lease, tools, "CONNECTION_RESULT", payload);
    }
    private static String required(JsonNode value, String key) throws IOException { String text = value.path(key).asText(); if (text.isBlank()) throw new IOException("中心微信上下文缺少 " + key); return text; }

    static final class Bridge implements AutoCloseable {
        final CenterHttpClient client; private String channel; final String version; final String senderHash; volatile Instant expires; volatile boolean closed;
        Bridge(CenterHttpClient client, JsonNode resolved, String version) throws IOException { this.client = client; this.channel = required(resolved, "channelId"); this.version = version; this.senderHash=required(resolved,"senderHash"); expires = RunnerLease.expiry(resolved.path("expiresAt")); }
        synchronized ObjectNode identity() { ObjectNode value = CanonicalJson.MAPPER.createObjectNode(); value.put("channelId", channel); value.put("configVersion", version); return value; }
        synchronized void ensureActive() throws IOException {
            if (closed) throw new IOException("微信中心通道已停止");
            if (expires.isBefore(Instant.now().plusSeconds(40))) {
                JsonNode renewed = client.post("/paicli/runner/v1/wechat/context", identity(), UUID.randomUUID().toString());
                String renewedId=required(renewed,"channelId");Instant renewedExpiry=RunnerLease.expiry(renewed.path("expiresAt"));
                if(!renewedId.matches("[1-9][0-9]{0,18}")||!version.equals(renewed.path("configVersion").asText())||!senderHash.equals(renewed.path("senderHash").asText())||!renewedExpiry.isAfter(Instant.now()))throw new IOException("微信中心续期绑定或期限不符");
                client.replaceToken(required(renewed, "bridgeKey")); channel=renewedId; expires=renewedExpiry;
            }
            if (!expires.isAfter(Instant.now())) throw new IOException("微信中心短期能力已过期");
        }
        synchronized JsonNode post(String path, ObjectNode value) throws IOException { ensureActive(); ObjectNode request=value.deepCopy();request.put("channelId",channel);request.put("configVersion",version);return client.post("/paicli/runner/v1/wechat/" + path, request, UUID.randomUUID().toString()); }
        @Override public void close() { closed = true; client.close(); }
    }
    private static final class Channel implements AutoCloseable {
        final String version; final Bridge bridge; final WechatMessageLoop loop; final Thread thread;
        Channel(String version, Bridge bridge, WechatMessageLoop loop) { this.version = version; this.bridge = bridge; this.loop = loop; thread = new Thread(loop::run, "paicli-center-wechat"); thread.setDaemon(true); }
        void start() { thread.start(); }
        @Override public void close() { loop.stop(); bridge.close(); thread.interrupt(); try { thread.join(3000); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); } }
    }
    static final class RemoteSession implements WechatTaskSession {
        final Bridge bridge; final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread thread = new Thread(r, "paicli-wechat-center-turn"); thread.setDaemon(true); return thread; });
        Future<String> current; volatile String taskId; private volatile boolean lastCommandAccepted = true;
        RemoteSession(Bridge bridge) { this.bridge = bridge; }
        public synchronized boolean isRunning() { return current != null && !current.isDone(); }
        public synchronized boolean hasCompletedRun() { return current != null && current.isDone(); }
        public Future<String> submit(String prompt) { return submitMessage(new WechatMessage(UUID.randomUUID().toString(), "", "", prompt, List.of())); }
        public synchronized Future<String> submitMessage(WechatMessage message) {
            if (isRunning()) throw new IllegalStateException("微信中心已有运行任务");
            current = worker.submit(() -> {
                if (!message.mediaItems().isEmpty()) return "微信当前通道仅接受文字，媒体元数据已收到，请从中心上传附件。";
                if(message.fromUserId()==null||message.fromUserId().isBlank())throw new IOException("微信任务缺少真实已绑定sender");
                ObjectNode body = bridge.identity(); body.put("senderHash", CanonicalJson.sha256(message.fromUserId().getBytes(StandardCharsets.UTF_8))); body.put("messageId", message.messageId()); body.put("text", message.text());
                JsonNode turn = bridge.post("turn", body); taskId = required(turn, "taskId");
                StringBuilder output = new StringBuilder(); long after = 0;long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(30);
                while (!Thread.currentThread().isInterrupted()&&System.nanoTime()<deadline) {
                    ObjectNode request = bridge.identity(); request.put("taskId", taskId); request.put("after", after); request.put("limit", 200);
                    JsonNode batch = bridge.post("events", request);
                    for (JsonNode event : batch.path("events")) {
                        long sequence = event.path("sequence").asLong(); if (sequence <= after) continue; after = sequence;
                        String type = event.path("type").asText(); JsonNode payload = event.path("payload");
                        if ("TASK_RECONCILE_REQUIRED".equals(type))return "中心任务已暂停待核对，请在编码工作台核对任务 "+taskId+"；不会自动重交。";
                        if ("MODEL_DELTA".equals(type) && "ASSISTANT_DELTA".equals(payload.path("presentation").asText())) {String delta=payload.path("delta").asText();if(output.length()+delta.length()>4*1024*1024)return "微信结果超过本通道展示上限，请在中心查看完整账本。任务 "+taskId;output.append(delta);}
                        if (type.startsWith("TASK_") && List.of("TASK_COMPLETED", "TASK_FAILED", "TASK_CANCELLED").contains(type)) {
                            String result = payload.path("message").asText(payload.path("result").asText(payload.path("text").asText()));
                            if(!"TASK_COMPLETED".equals(type))return "中心任务未完成: "+type+(result.isBlank()?"":"\n"+result);
                            if (!result.isBlank()) return result;
                            return output.isEmpty() ? ("TASK_COMPLETED".equals(type) ? "中心任务完成" : "中心任务未完成: " + type) : output.toString();
                        }
                    }
                    Thread.sleep(250);
                }
                return Thread.currentThread().isInterrupted()?"微信中心等待已取消；实际任务状态请在中心核对。":"等待中心任务超时，请按任务ID "+taskId+" 核对；不会自动重交。";
            }); return current;
        }
        public synchronized String awaitCurrent() { try { return current == null ? "" : current.get(); } catch (Exception failure) { return "微信中心任务失败: " + failure.getMessage(); } finally { current = null; } }
        public void cancel() { command("cancel", ""); }
        public void clear() { command("clear", ""); }
        public String compact() { return command("compact", ""); }
        public boolean wasLastCommandAccepted() { return lastCommandAccepted; }
        public String command(String name, String arguments) {
            lastCommandAccepted = true;
            if ("unknown".equals(name)) return "未知微信命令，请发送 /help。";
            // send保留原循环的文件推送未实现提示，不伪造messageId/sender提交文字任务。
            if("send".equals(name))return null;
            try { ObjectNode body = bridge.identity();body.put("senderHash",bridge.senderHash); body.put("name", "stop".equals(name) ? "cancel" : name); body.put("arguments", arguments == null ? "" : arguments); if (taskId != null) body.put("taskId", taskId); JsonNode response = bridge.post("commands", body); return response.path("message").asText(response.toString()); }
            catch (IOException failure) { lastCommandAccepted = false; return "微信中心命令失败: " + failure.getMessage(); }
        }
        public void close() { if (isRunning()) cancel(); worker.shutdownNow(); }
    }
}
