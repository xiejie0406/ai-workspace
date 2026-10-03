package com.paicli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class StdioTransport implements McpTransport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_STDERR_LINES = 200;
    public static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;
    private static final int MAX_STDERR_LINE_BYTES = 64 * 1024;
    private static final int MAX_STDERR_TOTAL_BYTES = 1024 * 1024;
    private static final String INCOMPLETE = "MCP_RESULT_INCOMPLETE: MCP通道返回超过限额或协议不完整，原结果不完整，须暂停对账";

    private final Process process;
    private final BufferedWriter stdin;
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<String> stderrRing = new ArrayDeque<>();
    private final Object stderrLock = new Object();
    private int stderrBytes;
    private volatile int frameLimit;
    private final java.util.Set<JsonNode> pendingIds = ConcurrentHashMap.newKeySet();
    private final List<Consumer<IOException>> failureListeners = new CopyOnWriteArrayList<>();
    private volatile IOException terminalFailure;
    private final java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;
    private Thread stdoutThread;
    private Thread stderrThread;

    public StdioTransport(String command, List<String> args, Map<String, String> env, Path workingDir) throws IOException {
        this(builder(command, args, env, workingDir));
    }

    private static ProcessBuilder builder(String command, List<String> args, Map<String, String> env, Path workingDir) {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        if (args != null) {
            commandLine.addAll(args);
        }
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        if (workingDir != null) {
            builder.directory(workingDir.toFile());
        }
        if (env != null && !env.isEmpty()) {
            builder.environment().putAll(env);
        }
        return builder;
    }

    /** 中心模式传入已经由 REQUIRED 沙箱包装且环境已清理的 builder。 */
    public StdioTransport(ProcessBuilder builder) throws IOException {
        this(builder, MAX_FRAME_BYTES);
    }

    /** 小限额仅供同包协议fixture；正式入口始终使用64MB。 */
    StdioTransport(ProcessBuilder builder, int frameLimit) throws IOException {
        if (frameLimit < 1 || frameLimit > MAX_FRAME_BYTES) throw new IllegalArgumentException("MCP帧限额非法");
        this.frameLimit = frameLimit;
        this.process = builder.start();
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        startStdoutReader();
        startStderrReader();
    }

    /** 受控批次可以收紧下一帧限额；已开始的帧保留首字节时捕获的限额。 */
    public void setResponseLimit(int bytes) {
        if(bytes<1||bytes>MAX_FRAME_BYTES)throw new IllegalArgumentException("MCP帧限额非法");
        frameLimit=bytes;
    }

    @Override
    public synchronized void send(JsonNode message) throws IOException {
        if (closed) {
            throw terminalFailure == null ? new IOException("MCP stdio transport already closed") : terminalFailure;
        }
        byte[] bytes = MAPPER.writeValueAsBytes(message);
        if (bytes.length > frameLimit) { failIncomplete(); throw terminalFailure; }
        JsonNode id = message.get("id");
        if (id != null && !id.isNull()) pendingIds.add(id.deepCopy());
        stdin.write(new String(bytes, StandardCharsets.UTF_8));
        stdin.newLine();
        stdin.flush();
    }

    @Override
    public void onReceive(Consumer<JsonNode> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** 异步stdout失败也必须立即撤销受控Runner租约，不能等待普通RPC超时后重试。 */
    public void onFailure(Consumer<IOException> listener) {
        if (listener != null) { failureListeners.add(listener); if (terminalFailure != null) listener.accept(terminalFailure); }
    }

    @Override
    public List<String> stderrLines() {
        synchronized (stderrLock) {
            return List.copyOf(stderrRing);
        }
    }

    @Override
    public Long processId() {
        return process.pid();
    }

    @Override
    public String transportName() {
        return "stdio";
    }

    @Override
    public void close() {
        stop(true);
    }

    private void stop(boolean graceful) {
        if (!closing.compareAndSet(false, true)) return;
        closed = true;
        com.paicli.runtime.ProcessTree.observe(process);
        // 关 stdin 让子进程读到 EOF，给一次优雅退出窗口（1 秒）。
        // shutdown 通知由 McpClient.close 在调本方法之前发出，子进程拿到 EOF 后通常会立即退出。
        // 协议已不完整时立即结束进程树，不能再给失控进程一秒副作用窗口。
        if (graceful) try { stdin.close(); } catch (IOException ignored) { }
        try {
            if (graceful) process.waitFor(1, TimeUnit.SECONDS);
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        finally {
            try { com.paicli.runtime.ProcessTree.terminate(process); }
            catch (IOException failure) { throw new java.io.UncheckedIOException("MCP进程树尚未完全停止", failure); }
            for (Thread reader : new Thread[]{stdoutThread, stderrThread}) {
                if (reader == null) continue;
                if (reader == Thread.currentThread()) continue;
                try { reader.join(1000); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private void startStdoutReader() {
        Thread thread = new Thread(() -> {
            try (InputStream reader = new BufferedInputStream(process.getInputStream())) {
                String line;
                while ((line = readUtf8Line(reader, () -> frameLimit)) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode message = MAPPER.readTree(line);
                    if (message == null || !message.isObject()) throw new IOException(INCOMPLETE);
                    if (message.hasNonNull("id")) pendingIds.remove(message.get("id"));
                    for (Consumer<JsonNode> listener : listeners) {
                        listener.accept(message);
                    }
                }
                if (!closed && !pendingIds.isEmpty()) failIncomplete();
            } catch (Exception e) {
                if (!closed) failIncomplete();
            }
        }, "paicli-mcp-stdio-stdout");
        thread.setDaemon(true);
        stdoutThread = thread;
        thread.start();
    }

    private void startStderrReader() {
        Thread thread = new Thread(() -> {
            try (InputStream reader = new BufferedInputStream(process.getErrorStream())) {
                String line;
                while ((line = readUtf8Line(reader, MAX_STDERR_LINE_BYTES)) != null) {
                    appendStderr(line);
                }
            } catch (IOException e) {
                if (!closed) failIncomplete();
            }
        }, "paicli-mcp-stdio-stderr");
        thread.setDaemon(true);
        stderrThread = thread;
        thread.start();
    }

    /** 先按线上UTF-8字节计数，再解码；不把超限前缀当作合法消息。 */
    static String readUtf8Line(InputStream input, int limit) throws IOException {
        return readUtf8Line(input, () -> limit);
    }

    private static String readUtf8Line(InputStream input, java.util.function.IntSupplier limits) throws IOException {
        // 等到线上第一字节再捕获，避免空闲reader在请求收紧限额前提前冻结64MB。
        int next=input.read();
        if(next==-1)return null;
        int limit=limits.getAsInt();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(limit, 8192));
        while (next != -1 && next != '\n') {
            if (bytes.size() >= limit) throw new IOException(INCOMPLETE);
            bytes.write(next);
            next=input.read();
        }
        byte[] value = bytes.toByteArray();
        int length = value.length > 0 && value[value.length - 1] == '\r' ? value.length - 1 : value.length;
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value, 0, length)).toString();
    }

    private void failIncomplete() {
        synchronized (stderrLock) {
            if (terminalFailure != null) return;
            terminalFailure = new IOException(INCOMPLETE);
        }
        closed = true;
        appendStderr("[paicli] " + INCOMPLETE);
        // 先关物理进程，再通知可能同步等待中心网络的授权回调，防回调延迟期间继续副作用。
        try { stop(false); } catch (RuntimeException stopUnconfirmed) { appendStderr("[paicli] MCP进程停止尚未确认，需要人工对账"); }
        for (Consumer<IOException> listener : failureListeners) try { listener.accept(terminalFailure); } catch (RuntimeException ignored) { }
        for (JsonNode id : pendingIds) {
            var error = MAPPER.createObjectNode().put("jsonrpc", "2.0"); error.set("id", id);
            error.putObject("error").put("code", -32098).put("message", INCOMPLETE);
            for (Consumer<JsonNode> listener : listeners) try { listener.accept(error); } catch (RuntimeException ignored) { }
        }
        pendingIds.clear();
    }

    private void appendStderr(String line) {
        synchronized (stderrLock) {
            int size=line.getBytes(StandardCharsets.UTF_8).length;
            while (!stderrRing.isEmpty()&&(stderrRing.size() >= MAX_STDERR_LINES||stderrBytes+size>MAX_STDERR_TOTAL_BYTES)) {
                stderrBytes-=stderrRing.removeFirst().getBytes(StandardCharsets.UTF_8).length;
            }
            stderrRing.addLast(line);
            stderrBytes+=size;
        }
    }
}
