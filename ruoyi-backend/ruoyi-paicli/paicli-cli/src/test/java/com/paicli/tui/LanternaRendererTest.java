package com.paicli.tui;

import com.paicli.render.Renderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LanternaRenderer 的轻量级测试 —— 主要覆盖类型契约。
 * 真实 GUI 行为依赖 alternate screen，不在 unit test 范围。
 */
class LanternaRendererTest {

    @Test
    void implementsRendererInterface() {
        // 仅校验 LanternaRenderer 实现了 Renderer 契约（接口方法签名）。
        // 实例化需要真实终端，留给端到端手测 §8。
        assertTrue(Renderer.class.isAssignableFrom(LanternaRenderer.class));
    }

    @Test
    void hasPublicConstructorAcceptingLanternaWindow() throws Exception {
        // 构造函数签名校验
        var ctor = LanternaRenderer.class.getConstructor(LanternaWindow.class);
        assertNotNull(ctor);
    }
    @Test void utf8SinkKeepsChineseAndEmojiWhenFlushSplitsEveryByte() throws Exception {
        String text = "实际中文🙂\n下一行：✔"; StringBuilder actual = new StringBuilder();
        var sink = new LanternaRenderer.Utf8Sink(actual::append);
        for (byte value : text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) { sink.write(value & 255); sink.flush(); }
        assertEquals(text, actual.toString());
    }
}
