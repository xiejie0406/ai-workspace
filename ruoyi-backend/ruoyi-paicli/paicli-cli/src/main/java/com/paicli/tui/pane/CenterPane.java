package com.paicli.tui.pane;

import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.LinearLayout.Alignment;
import com.googlecode.lanterna.gui2.LinearLayout.GrowPolicy;
import com.paicli.llm.LlmClient;
import com.paicli.render.inline.FoldableBlock;
import com.paicli.tui.highlight.CodeHighlighter;
import com.paicli.util.AnsiStyle;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 中央对话流面板：呈现已收到的文本，不持有中心消息或工具账本。 */
public class CenterPane extends Panel {
    private static final Pattern OPEN_FENCE = Pattern.compile("(?m)^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)\\r?(?:\\n|$)");
    private static final PrintStream REDRAW_ONLY = new PrintStream(OutputStream.nullOutputStream());
    private final TextBox chatArea;
    // 仅用于可逆重绘；不落盘，不创建消息 ID，也不作为中心状态来源。
    private final List<Presentation> presentations = new ArrayList<>();
    private Presentation streamingOutput;
    private boolean codesCollapsed;

    private static final class Presentation {
        private final boolean assistant;
        private final StringBuilder text;
        private Presentation(boolean assistant, String text) {
            this.assistant = assistant;
            this.text = new StringBuilder(text);
        }
    }

    public CenterPane(com.paicli.config.PaiCliConfig config, LlmClient llmClient) {
        setLayoutManager(new LinearLayout(Direction.VERTICAL));
        String welcome = "对话开始...\n\n💡 提示：\n  - 在底部输入框输入问题\n  - Ctrl+O 折叠/展开代码块\n  - Ctrl+P 查看历史对话\n  - Ctrl+\\ 显示/隐藏文件树";
        chatArea = new TextBox(welcome);
        chatArea.setReadOnly(true);
        presentations.add(new Presentation(false, welcome));
        addComponent(chatArea.setLayoutData(LinearLayout.createLayoutData(Alignment.Fill, GrowPolicy.CanGrow)));
    }

    public synchronized void onUserMessage(String message) {
        appendPlain("\n👤 你:\n" + renderMarkdown(message, false) + "\n");
    }

    public synchronized void appendSystemMessage(String message) {
        if (message == null || message.isBlank()) return;
        appendPlain("\n💡 系统:\n" + message.trim() + "\n");
    }

    public synchronized void appendAssistantOutput(String output) {
        if (output == null || output.isBlank()) return;
        streamingOutput = null;
        presentations.add(new Presentation(false, "\n🤖 PaiCLI:\n"));
        presentations.add(new Presentation(true, output));
        presentations.add(new Presentation(false, "\n"));
        redraw();
    }

    /** 跨 chunk 保留原始内容，使不完整 fence 随后能被正确识别。 */
    public synchronized void appendAssistantChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        if (streamingOutput == null) {
            streamingOutput = new Presentation(true, "");
            presentations.add(streamingOutput);
        }
        streamingOutput.text.append(chunk);
        redraw();
    }

    public synchronized void appendToolCall(String toolName, String args) {
        appendPlain("\n🔧 工具调用: " + (toolName != null ? toolName : "unknown") + "\n"
                + (args != null ? "  参数: " + args : "") + "\n");
    }

    public synchronized void appendToolResult(String result) {
        appendPlain("📤 工具结果:\n" + truncateResult(result, 500) + "\n");
    }

    private void appendPlain(String text) {
        streamingOutput = null;
        presentations.add(new Presentation(false, text));
        redraw();
    }

    /** Ctrl+O 只改变呈现，不追加系统消息或修改原始内容。 */
    public synchronized void toggleCodeBlocks() {
        codesCollapsed = !codesCollapsed;
        redraw();
    }

    private void redraw() {
        StringBuilder display = new StringBuilder();
        for (Presentation item : presentations) {
            display.append(item.assistant ? renderMarkdown(item.text.toString(), codesCollapsed) : item.text);
        }
        chatArea.setText(display.toString());
    }

    private String renderMarkdown(String text, boolean collapsed) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder display = new StringBuilder();
        Matcher opening = OPEN_FENCE.matcher(text);
        int position = 0;
        while (opening.find(position)) {
            String fence = opening.group(1);
            String info = opening.group(2).trim();
            // Markdown 的反引号 fence 信息串不能包含反引号。
            if (fence.charAt(0) == '`' && info.indexOf('`') >= 0) {
                display.append(renderInline(text.substring(position, opening.end())));
                position = opening.end();
                continue;
            }
            display.append(renderInline(text.substring(position, opening.start())));
            Pattern closePattern = Pattern.compile("(?m)^ {0,3}" + Pattern.quote(fence.substring(0, 1))
                    + "{" + fence.length() + ",}[ \t]*\\r?(?:\\n|$)");
            Matcher closing = closePattern.matcher(text);
            if (!closing.find(opening.end())) {
                // 未闭合时原样展示；不能折叠尚未完整收到的内容。
                display.append(text.substring(opening.start()));
                return display.toString();
            }
            String language = info.isEmpty() ? "text" : info.split("\\s+", 2)[0];
            String code = text.substring(opening.end(), closing.start());
            String highlighted = CodeHighlighter.highlight(code, language);
            List<String> lines = Arrays.asList(highlighted.split("\n", -1));
            int lineCount = code.isEmpty() ? 0 : code.split("\n", -1).length - (code.endsWith("\n") ? 1 : 0);
            FoldableBlock block = new FoldableBlock(REDRAW_ONLY,
                    "⏵ 代码块 " + language + " · " + lineCount + " 行（Ctrl+O 展开）", lines, null);
            if (!collapsed) block.toggleForRedraw();
            display.append(String.join("\n", block.currentLines()));
            if (collapsed && (code.endsWith("\n") || text.charAt(closing.end() - 1) == '\n')) display.append('\n');
            position = closing.end();
        }
        display.append(renderInline(text.substring(position)));
        return display.toString();
    }

    /** 行内格式只处理代码块之外的正文，保留代码中的 Markdown 字面值。 */
    private static String renderInline(String text) {
        text = replaceAllRegex(text, "\\*\\*(.+?)\\*\\*", m -> AnsiStyle.emphasis(m.group(1)));
        return replaceAllRegex(text, "`(.+?)`", m -> AnsiStyle.codeLabel(m.group(1)));
    }

    private static String replaceAllRegex(String text, String regex, java.util.function.Function<Matcher, String> replacer) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(result, Matcher.quoteReplacement(replacer.apply(matcher)));
        matcher.appendTail(result);
        return result.toString();
    }

    private static String truncateResult(String result, int maxLength) {
        if (result == null) return "null";
        if (result.length() <= maxLength) return result;
        return result.substring(0, maxLength) + "\n... (截断，共 " + result.length() + " 字符)";
    }

    public synchronized void clear() {
        presentations.clear();
        streamingOutput = null;
        codesCollapsed = false;
        chatArea.setText("");
    }
}
