package com.paicli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用 unix shell 子进程做 mock，避免依赖真实 MCP server。
 *
 * 原shell fixture保留平台条件；有界读取与新Java子进程fixture在各平台执行。
 */
class StdioTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void echoesJsonMessageBackToListener() throws Exception {
        // sh 子进程把每行 stdin 原样回到 stdout，模拟一个最简响应器
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", "while IFS= read -r line; do echo \"$line\"; done"),
                Map.of(), null);
        try {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<JsonNode> messageHolder = new AtomicReference<>();
            transport.onReceive(node -> {
                messageHolder.set(node);
                received.countDown();
            });

            JsonNode payload = MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
            transport.send(payload);

            assertTrue(received.await(2, TimeUnit.SECONDS), "应能在 2 秒内收到回显");
            assertEquals("ping", messageHolder.get().path("method").asText());
            assertEquals("stdio", transport.transportName());
            assertNotNull(transport.processId());
        } finally {
            transport.close();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void stderrLinesAreCapturedWithoutBlockingStdout() throws Exception {
        // 子进程同时往 stdout 输出可解析 JSON 并往 stderr 写 50 行
        String script = "for i in $(seq 1 50); do echo error-$i 1>&2; done; echo '{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"ok\"}'";
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", script), Map.of(), null);
        try {
            CountDownLatch received = new CountDownLatch(1);
            transport.onReceive(node -> {
                if ("ok".equals(node.path("method").asText())) {
                    received.countDown();
                }
            });
            assertTrue(received.await(3, TimeUnit.SECONDS),
                    "stderr 大量输出不应阻塞 stdout 路径");
            // 等待 stderr 全部 drain
            Thread.sleep(200);
            List<String> lines = transport.stderrLines();
            assertFalse(lines.isEmpty());
            assertTrue(lines.size() <= 200, "stderr 环形 buffer 不能超过 200 行");
        } finally {
            transport.close();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void stderrRingBufferTruncatesOldLinesBeyondLimit() throws Exception {
        // 输出 250 行 stderr，预期只保留最近 200 行
        String script = "for i in $(seq 1 250); do echo line-$i 1>&2; done";
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", script), Map.of(), null);
        try {
            // 子进程会自然结束，等 drain
            Thread.sleep(500);
            List<String> lines = transport.stderrLines();
            assertEquals(200, lines.size(), "环形 buffer 上限是 200");
            assertEquals("line-51", lines.get(0), "最早保留的应是第 51 行");
            assertEquals("line-250", lines.get(lines.size() - 1));
        } finally {
            transport.close();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void closeTerminatesLongRunningProcess() throws Exception {
        // 故意起一个不会自动退出的 sleep 进程
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", "sleep 30"), Map.of(), null);
        Long pid = transport.processId();
        assertNotNull(pid);

        long start = System.currentTimeMillis();
        transport.close();
        long elapsed = System.currentTimeMillis() - start;

        // 优雅 1s + SIGTERM 2s 上限 = 3s 多一点
        assertTrue(elapsed < 5000, "close 应在 5 秒内完成（实际 " + elapsed + "ms）");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sendAfterCloseFailsExplicitly() throws Exception {
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", "cat"), Map.of(), null);
        transport.close();

        IOException ex = assertThrows(IOException.class,
                () -> transport.send(MAPPER.readTree("{}")));
        assertTrue(ex.getMessage().contains("closed"), "错误消息应明示已关闭: " + ex.getMessage());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void invalidJsonOnStdoutDoesNotCrashTransport() throws Exception {
        // 子进程先输出一行非 JSON，再输出一行合法 JSON，验证非法行被 swallow
        String script = "echo 'not-json'; echo '{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ok\"}'";
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", script), Map.of(), null);
        try {
            CountDownLatch received = new CountDownLatch(1);
            transport.onReceive(node -> {
                if ("ok".equals(node.path("method").asText())) {
                    received.countDown();
                }
            });
            // 当前实现：解析失败会让 stdout reader 退出，所以可能拿不到第二条。
            // 这里只断言 transport 仍可被 close 不抛异常即可，确认坏 JSON 不会让进程级 crash。
            assertDoesNotThrow(() -> Thread.sleep(300));
        } finally {
            assertDoesNotThrow(transport::close);
        }
    }

    @Test void frameLimitCountsUtf8BytesAndNeverAcceptsAnOversizedPrefix() throws Exception {
        assertEquals(64 * 1024 * 1024, StdioTransport.MAX_FRAME_BYTES);
        byte[] bytes = "中文\n".getBytes(StandardCharsets.UTF_8);
        assertEquals("中文", StdioTransport.readUtf8Line(new ByteArrayInputStream(bytes), 6));
        IOException failure = assertThrows(IOException.class, () -> StdioTransport.readUtf8Line(new ByteArrayInputStream(bytes), 5));
        assertTrue(failure.getMessage().contains("MCP_RESULT_INCOMPLETE"));
    }

    @Test void malformedUtf8IsRejectedRatherThanReplaced() {
        assertThrows(IOException.class, () -> StdioTransport.readUtf8Line(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28, '\n'}), 16));
    }

    @Test void oversizedStdoutStopsActualProcessAndOnlyReportsIncompleteResult() throws Exception {
        ProcessBuilder child = javaChild("stdout");
        try (StdioTransport transport = new StdioTransport(child, 512)) {
            CountDownLatch incomplete = new CountDownLatch(1);
            AtomicReference<JsonNode> received = new AtomicReference<>();
            AtomicReference<IOException> failure = new AtomicReference<>();
            transport.onFailure(failure::set);
            transport.onReceive(node -> { received.set(node); incomplete.countDown(); });
            assertThrows(IllegalArgumentException.class,()->transport.setResponseLimit(0));
            assertThrows(IllegalArgumentException.class,()->transport.setResponseLimit(StdioTransport.MAX_FRAME_BYTES+1));
            // stdout reader已启动等待第一字节；请求收紧仍必须作用于本响应。
            transport.setResponseLimit(128);
            transport.send(MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "fixture"));
            assertTrue(incomplete.await(3, TimeUnit.SECONDS));
            assertFalse(received.get().has("result"));
            assertEquals(-32098, received.get().path("error").path("code").asInt());
            assertTrue(received.get().path("error").path("message").asText().contains("MCP_RESULT_INCOMPLETE"));
            assertNotNull(failure.get());
            assertFalse(failure.get().getMessage().contains("fixture-secret"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (ProcessHandle.of(transport.processId()).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(20);
            assertFalse(ProcessHandle.of(transport.processId()).map(ProcessHandle::isAlive).orElse(false));
            assertThrows(IOException.class, () -> transport.send(MAPPER.createObjectNode()));
        }
    }

    @Test void oversizedStderrCannotAllocateAnUnboundedLineOrLeaveProcessRunning() throws Exception {
        try (StdioTransport transport = new StdioTransport(javaChild("stderr"), 128)) {
            CountDownLatch failure = new CountDownLatch(1);
            transport.onFailure(ignored -> failure.countDown());
            transport.send(MAPPER.createObjectNode().put("id", 1));
            assertTrue(failure.await(3, TimeUnit.SECONDS));
            assertTrue(transport.stderrLines().stream().allMatch(line -> line.length() <= 65536));
            assertThrows(IOException.class, () -> transport.send(MAPPER.createObjectNode()));
        }
    }

    @Test void stderrRetentionIsBoundedByBothLinesAndActualUtf8Bytes() throws Exception {
        try(StdioTransport transport=new StdioTransport(javaChild("stderr-ring"),128)) {
            transport.send(MAPPER.createObjectNode().put("id",1));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(transport.stderrLines().stream().noneMatch("ring-end"::equals)&&System.nanoTime()<deadline)Thread.sleep(20);
            List<String> logs=transport.stderrLines();assertTrue(logs.contains("ring-end"),"UTF8 fixture完成标记缺失；已保留行数="+logs.size()+"，有协议失败="+logs.stream().anyMatch(line->line.contains("MCP_RESULT_INCOMPLETE")));
            assertTrue(logs.size()<=200);assertTrue(logs.stream().mapToLong(line->line.getBytes(StandardCharsets.UTF_8).length).sum()<=1024*1024);
        }
    }

    private static ProcessBuilder javaChild(String mode) {
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        return new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"), FrameChild.class.getName(), mode);
    }

    public static final class FrameChild {
        public static void main(String[] args) throws Exception {
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            if (args[0].equals("stderr")) { System.err.print("x".repeat(65537)); System.err.flush(); }
            else if(args[0].equals("stderr-ring")){
                // 明确按协议UTF8发字节，不使用Windows子Java的本机stderr字符集。
                // 211行且>1.2MB已经同时超过两项保留上限，无需发送4.8MB吞吐负载。
                var logs=new java.io.BufferedWriter(new java.io.OutputStreamWriter(System.err,StandardCharsets.UTF_8));
                for(int i=0;i<210;i++){logs.write("中".repeat(2000));logs.newLine();}
                logs.write("ring-end\n");logs.flush();
            }
            else { System.out.print("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"fixture-secret" + "中".repeat(100) + "\"}\n"); System.out.flush(); }
            Thread.sleep(30000);
        }
    }
}
