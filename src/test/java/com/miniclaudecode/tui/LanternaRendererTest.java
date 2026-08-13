package com.miniclaudecode.tui;

import com.miniclaudecode.render.Renderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LanternaRenderer 的轻量级测试 —— 主要覆盖类型契约
 * 真实 GUI 行为依赖 alternate screen，不在 unit test 范围
 */
class LanternaRendererTest {

    @Test
    void implementsRendererInterface() {
        // 实例化依赖真实终端，因此这里只校验 Renderer 契约
        assertTrue(Renderer.class.isAssignableFrom(LanternaRenderer.class));
    }

    @Test
    void hasPublicConstructorAcceptingLanternaWindow() throws Exception {
        var ctor = LanternaRenderer.class.getConstructor(LanternaWindow.class);
        assertNotNull(ctor);
    }
}
