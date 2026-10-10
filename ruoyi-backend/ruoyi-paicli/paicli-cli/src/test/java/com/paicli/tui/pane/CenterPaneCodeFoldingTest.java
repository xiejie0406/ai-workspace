package com.paicli.tui.pane;

import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.input.KeyStroke;
import com.paicli.config.PaiCliConfig;
import com.paicli.llm.LlmClient;
import com.paicli.tui.RootPane;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CenterPaneCodeFoldingTest {
    private final LlmClient client = mock(LlmClient.class);
    private final RootPane root = new RootPane(new PaiCliConfig(), client, true);
    private final CenterPane pane = root.getCenterPane();

    private String text() {
        return ((TextBox) pane.getChildrenList().get(0)).getText()
                .replaceAll("\u001B\\[[0-9;]*m", "");
    }
    private void toggle(char key) { assertTrue(root.handleInput(new KeyStroke(key, true, false))); }

    @Test void actualShortcutFoldsFullOutputAndRestoresExactly() {
        pane.clear();
        pane.appendAssistantOutput("before\n```java\nuniqueBody();\n```\nafter");
        String expanded = text();
        assertTrue(expanded.contains("uniqueBody();"));
        toggle('o');
        assertFalse(text().contains("uniqueBody();"));
        assertTrue(text().contains("代码块"));
        assertTrue(text().indexOf("before") < text().indexOf("after"));
        assertFalse(text().contains("快捷键已收到"));
        toggle('O');
        assertEquals(expanded, text());
        // RootPane 构造状态栏会读取模型名称；快捷键不能触发模型请求。
        verify(client).getModelName();
        verifyNoMoreInteractions(client);
    }

    @Test void everyCharacterChunkPreservesFencesAndSpecialCode() {
        pane.clear();
        String response = "head\n```text\n$1 C:\\tmp\\a **literal** `literal`\n```\ntail";
        for (char c : response.toCharArray()) pane.appendAssistantChunk(String.valueOf(c));
        String expanded = text();
        assertTrue(expanded.contains("$1 C:\\tmp\\a **literal** `literal`"));
        assertFalse(expanded.contains("```"));
        toggle('o');
        assertFalse(text().contains("C:\\tmp"));
        assertTrue(text().contains("head") && text().contains("tail"));
        toggle('o');
        assertEquals(expanded, text());
    }

    @Test void unfinishedBlockStaysVisibleUntilItsClosingFenceArrives() {
        pane.clear();
        toggle('o');
        pane.appendAssistantChunk("start\n```text\nunfinished-body\n");
        assertTrue(text().contains("unfinished-body"));
        pane.appendAssistantChunk("`");
        pane.appendAssistantChunk("`");
        assertTrue(text().contains("unfinished-body"));
        pane.appendAssistantChunk("`\nend");
        assertFalse(text().contains("unfinished-body"));
        assertTrue(text().contains("end"));
        toggle('o');
        assertTrue(text().contains("unfinished-body"));
    }

    @Test void nonAssistantEntriesPreserveTheirBodyAndMessageOrder() {
        pane.clear();
        pane.appendAssistantChunk("```text\nfirst-code\n```\n");
        pane.onUserMessage("user **$1 C:\\tmp**\n```text\nuser-code\n```");
        pane.appendSystemMessage("system $2 \\path");
        pane.appendToolCall("read", "{\"path\":\"$3\\\\tmp\"}");
        pane.appendToolResult("tool-code " + "z".repeat(510));
        pane.appendAssistantChunk("```text\nsecond-code\n```\n");
        String expanded = text();
        assertTrue(expanded.contains("$1 C:\\tmp"));
        toggle('o');
        String folded = text();
        assertFalse(folded.contains("first-code") || folded.contains("second-code"));
        assertTrue(folded.contains("user-code") && folded.contains("system $2 \\path"));
        assertTrue(folded.contains("tool-code") && folded.contains("截断，共 520 字符"));
        assertTrue(folded.indexOf("你:") < folded.indexOf("系统:"));
        assertTrue(folded.indexOf("系统:") < folded.indexOf("工具调用:"));
        assertTrue(folded.indexOf("工具调用:") < folded.indexOf("工具结果:"));
        toggle('o');
        assertEquals(expanded, text());
    }

    @Test void clearResetsCodeStateAndDoesNotJoinDifferentAssistantEntries() {
        pane.clear();
        pane.appendAssistantChunk("```text\nunclosed-old\n");
        pane.appendSystemMessage("boundary");
        pane.appendAssistantOutput("```\n");
        toggle('o');
        assertTrue(text().contains("unclosed-old"));
        pane.clear();
        assertEquals("", text());
        pane.appendAssistantOutput("```text\nfresh-body\n```");
        assertTrue(text().contains("fresh-body"));
        assertFalse(text().contains("unclosed-old"));
        toggle('o');
        assertFalse(text().contains("fresh-body"));
    }

    @Test void longerFenceContainsLiteralShortFenceAndMultipleBlocksToggleTogether() {
        pane.clear();
        pane.appendAssistantOutput("top\r\n````text\r\nouter-body\r\n```\r\nend-body\r\n````\r\nmiddle\n~~~text\nother-body\n~~~\nbottom");
        String expanded = text();
        assertTrue(expanded.contains("outer-body") && expanded.contains("end-body") && expanded.contains("other-body"));
        assertTrue(expanded.contains("```"));
        toggle('o');
        assertFalse(text().contains("outer-body") || text().contains("end-body") || text().contains("other-body"));
        assertTrue(text().indexOf("top") < text().indexOf("middle"));
        assertTrue(text().indexOf("middle") < text().indexOf("bottom"));
        toggle('o');
        assertEquals(expanded, text());
    }
}
