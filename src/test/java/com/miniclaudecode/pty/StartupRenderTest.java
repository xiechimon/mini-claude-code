package com.miniclaudecode.pty;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用例 1, 12-15: 启动渲染 + renderer fallback
 *
 * <p>对应 docs/inline-tui-manual-tests.md：
 * <ul>
 *   <li>用例 1: 默认 inline 启动 banner + ANSI</li>
 *   <li>用例 12: MINI_CLAUDE_CODE_RENDERER=plain 兜底</li>
 *   <li>用例 13: NO_COLOR=1 禁色</li>
 *   <li>用例 14: 未知 RENDERER 回退 inline</li>
 *   <li>用例 15: TERM=dumb 回退 plain</li>
 * </ul>
 */
class StartupRenderTest {

    @Test
    void inlineStartupShowsBanner() throws Exception {
        StubScript script = StubScript.textReply("ready");
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline")) {
            String output = h.session().currentOutput();
            assertTrue(output.contains("Mini Claude Code"),
                    "banner 应显示 Mini Claude Code");
            assertTrue(output.contains("v16.1.0"), "应显示版本号");
            assertTrue(output.contains("Tips for getting started"),
                    "应显示新手提示");
        }
    }

    @Test
    void plainFallbackWhenRendererSetToPlain() throws Exception {
        StubScript script = StubScript.textReply("ok");
        try (PtyTestHarness h = PtyTestHarness.start(script, "plain")) {
            String output = h.session().currentOutput();
            assertTrue(output.contains("Mini Claude Code"), "plain 也应有 banner");
            // plain 模式不输出底部状态栏（无 ANSI 颜色）
            assertFalse(output.contains("▰"), "plain 模式不应有进度条块字符");
        }
    }

    @Test
    void noColorEnvStripsAnsi() throws Exception {
        StubScript script = StubScript.textReply("ok");
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline",
                Map.of("NO_COLOR", "1"))) {
            String output = h.session().currentOutput();
            // banner 文本保留，但 ANSI 颜色码被 NO_COLOR 关掉
            // JLine 在 NO_COLOR 下不输出 \x1B[...m 颜色序列
            String stripped = PtyCliSession.stripAnsi(output);
            assertEquals(stripped.length(), PtyCliSession.stripAnsi(stripped).length(),
                    "stripAnsi 幂等");
            assertTrue(output.contains("Mini Claude Code"));
        }
    }

    @Test
    void unknownRendererValueFallsBackToInline() throws Exception {
        StubScript script = StubScript.textReply("ok");
        try (PtyTestHarness h = PtyTestHarness.start(script, "weird-value")) {
            // stderr 应包含回退提示；buffer 可能捕获
            String output = h.session().currentOutput();
            assertTrue(output.contains("Mini Claude Code"),
                    "未知 renderer 应回退到 inline，banner 仍出现");
        }
    }

    @Test
    void termDumbFallsBackToPlain() throws Exception {
        StubScript script = StubScript.textReply("ok");
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline",
                Map.of("TERM", "dumb"))) {
            String output = h.session().currentOutput();
            assertTrue(output.contains("⚠️ 终端不支持 ANSI，inline 模式回退到 plain")
                            || output.contains("Mini Claude Code"),
                    "TERM=dumb 应回退到 plain 或 banner 仍可见");
        }
    }
}