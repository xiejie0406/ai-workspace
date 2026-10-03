package com.paicli.tui;

import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.dialogs.ListSelectDialogBuilder;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import com.paicli.tui.pane.CenterPane;
import com.paicli.tui.pane.StatusPane;

import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lanterna 全屏 TUI 形态的 {@link Renderer} 适配器。
 *
 * <p>设计：本类不拥有 Lanterna 主循环，而是作为现有 {@link LanternaWindow} 的"视图"。
 * 真正的 GUI 主循环由 {@link com.paicli.tui.TuiBootstrap}（或后续 Main）启动。
 * 本类把 {@code Renderer} 方法委托到 {@link CenterPane}/{@link StatusPane} 等 widget。
 *
 * <p>所有 widget 操作必须在 GUI 事件线程执行，本类内部用
 * {@link LanternaWindow#runOnGuiThread} 封送。
 */
public final class LanternaRenderer implements Renderer {

    private final LanternaWindow window;
    private final CenterPane centerPane;
    private final StatusPane statusPane;
    private final WindowBasedTextGUI gui;
    private final PrintStream stream;
    private volatile boolean closed;

    public LanternaRenderer(LanternaWindow window) {
        this.window = Objects.requireNonNull(window);
        this.centerPane = window.getRootPane().getCenterPane();
        this.statusPane = window.getRootPane().getStatusPane();
        this.gui = window.getGui();
        this.stream = new PrintStream(new Utf8Sink(text -> window.runOnGuiThread(() -> centerPane.appendAssistantChunk(text))), true, StandardCharsets.UTF_8);
    }

    @Override
    public void start() {
        // GUI 主循环由 LanternaWindow.start() 驱动，本方法 no-op。
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            window.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public PrintStream stream() {
        return stream;
    }

    @Override
    public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        for (LlmClient.ToolCall tc : toolCalls) {
            String name = tc.function().name();
            String args = tc.function().arguments();
            window.runOnGuiThread(() -> centerPane.appendToolCall(name, args));
        }
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
        StringBuilder sb = new StringBuilder();
        sb.append("📝 ").append(filePath == null ? "(unnamed)" : filePath).append("\n");
        if (before == null) {
            sb.append("(新建文件，").append(after == null ? 0 : after.length()).append(" 字符)");
        } else if (after == null) {
            sb.append("(删除文件)");
        } else {
            sb.append(before.length()).append(" → ").append(after.length()).append(" 字符");
        }
        String text = sb.toString();
        window.runOnGuiThread(() -> centerPane.appendSystemMessage(text));
    }

    @Override
    public void updateStatus(StatusInfo status) {
        if (status != null) window.runOnGuiThread(() -> statusPane.updateStatus(status));
    }

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        AtomicReference<ApprovalResult> result = new AtomicReference<>();
        Object lock = new Object();
        window.runOnGuiThread(() -> {
            ApprovalResult r = doShowApprovalDialog(request);
            synchronized (lock) {
                result.set(r);
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            while (result.get() == null) {
                try {
                    lock.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ApprovalResult.reject("审批被中断");
                }
            }
        }
        return result.get();
    }

    private ApprovalResult doShowApprovalDialog(ApprovalRequest request) {
        String title = "⚠️ HITL 审批: " + request.toolName();
        String body = request.toDisplayText() + "\n\n[Yes] 批准  [No] 拒绝  [Cancel] 跳过";
        MessageDialogButton button = new MessageDialogBuilder()
                .setTitle(title)
                .setText(body)
                .addButton(MessageDialogButton.Yes)
                .addButton(MessageDialogButton.No)
                .addButton(MessageDialogButton.Cancel)
                .build()
                .showDialog(gui);
        if (button == null) {
            return ApprovalResult.skip();
        }
        return switch (button) {
            case Yes -> ApprovalResult.approve();
            case No -> ApprovalResult.reject("Lanterna 模态框拒绝");
            case Cancel -> ApprovalResult.skip();
            default -> ApprovalResult.skip();
        };
    }

    @Override
    public int openPalette(String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return -1;
        }
        AtomicReference<Integer> result = new AtomicReference<>();
        Object lock = new Object();
        window.runOnGuiThread(() -> {
            ListSelectDialogBuilder<String> builder = new ListSelectDialogBuilder<String>()
                    .setTitle(title == null ? "选择" : title)
                    .setDescription("方向键 + Enter 选择");
            for (String item : items) {
                builder.addListItem(item);
            }
            String selected = builder.build().showDialog(gui);
            int idx = selected == null ? -1 : items.indexOf(selected);
            synchronized (lock) {
                result.set(idx);
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            while (result.get() == null) {
                try {
                    lock.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
        }
        return result.get();
    }

    /** 返回底层 LanternaWindow，方便 Main 启动 GUI 主循环。 */
    public LanternaWindow window() {
        return window;
    }

    /** 把外部 Map 风格的属性面板（如 /config）一次性渲染到 CenterPane（用于状态展示）。 */
    public void appendKeyValueBlock(String header, Map<String, String> kv) {
        StringBuilder sb = new StringBuilder(header).append("\n");
        for (var e : kv.entrySet()) {
            sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
        String text = sb.toString();
        window.runOnGuiThread(() -> centerPane.appendSystemMessage(text));
    }

    /** 把 stream() 的字节缓冲到 CenterPane（按行 flush）。 */
    static final class Utf8Sink extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final java.util.function.Consumer<String> output;
        Utf8Sink(java.util.function.Consumer<String> output) { this.output = Objects.requireNonNull(output); }

        @Override
        public synchronized void write(int b) throws IOException {
            buffer.write(b);
            if (b == '\n') flush();
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            for (int i = off; i < off + len; i++) {
                write(b[i] & 0xFF);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            if (buffer.size() == 0) return;
            byte[] bytes = buffer.toByteArray(); ByteBuffer input = ByteBuffer.wrap(bytes);
            CharBuffer chars = CharBuffer.allocate(bytes.length);
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            var result = decoder.decode(input, chars, false); if (result.isError()) result.throwException();
            buffer.reset(); buffer.write(bytes, input.position(), bytes.length - input.position());
            chars.flip(); if (chars.hasRemaining()) output.accept(chars.toString());
        }
    }
}
