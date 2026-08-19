package com.miniclaudecode.pty;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用例 2, 3, 4: 折叠块 — Ctrl+O 展开收起
 *
 * <p>对应 docs/inline-tui-manual-tests.md
 * <p>折叠头字符 ⏵ (U+23F5) / ⏷ (U+23F7)，regex 用 Unicode 转义避免编码问题
 */
class FoldBlockTest {

    private static final String TRIANGLE = "\\u23F5"; // ⏵ 折叠态
    private static final String TRIANGLE_DOWN = "\\u23F7"; // ⏷ 展开态

    @Test
    void singleToolFoldHeader() throws Exception {
        StubScript script = StubScript.toolThenReply(
                "read_file", "{\"path\":\"README.md\",\"offset\":1,\"limit\":50}",
                "reading");
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline")) {
            h.session().send("帮我读 README.md 前 50 行");
            h.session().expect(
                    Pattern.compile(TRIANGLE + " ReadFile\\(README\\.md\\)"),
                    PtyTestHarness.LLM_RESPONSE_TIMEOUT);
            String output = PtyCliSession.stripAnsi(h.session().currentOutput());
            assertTrue(output.contains("(ctrl+o to expand)"),
                    "应显示 ctrl+o 提示");
        }
    }

    @Test
    void ctrlOExpandsThenCollapses() throws Exception {
        StubScript script = StubScript.toolThenReply(
                "read_file", "{\"path\":\"README.md\",\"offset\":1,\"limit\":50}",
                "done");
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline")) {
            h.session().send("读 README.md");
            h.session().expect(
                    Pattern.compile(TRIANGLE + " ReadFile\\(README\\.md\\)"),
                    PtyTestHarness.LLM_RESPONSE_TIMEOUT);
            // Ctrl+O 展开
            h.session().sendCtrl('O');
            h.session().expect(
                    Pattern.compile(TRIANGLE_DOWN + " collapse \\(ctrl\\+o\\)"),
                    Duration.ofSeconds(10));
            // 再 Ctrl+O 折回
            h.session().sendCtrl('O');
            h.session().expect(
                    Pattern.compile(TRIANGLE + " ReadFile\\(README\\.md\\)"),
                    Duration.ofSeconds(10));
        }
    }

    @Test
    void multipleToolsInSameTurn() throws Exception {
        StubScript script = StubScript.readMultipleFiles("pom.xml", "AGENTS.md", "ROADMAP.md");
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline")) {
            h.session().send("同时读 pom.xml、AGENTS.md、ROADMAP.md");
            String match = h.session().expect(
                    Pattern.compile(TRIANGLE + " "),
                    PtyTestHarness.LLM_RESPONSE_TIMEOUT);
            assertNotNull(match, "应有折叠头出现");
            assertTrue(h.stub().requestCount() >= 1,
                    "stub 应被调用，实际: " + h.stub().requestCount());
        }
    }

    @Test
    void frozenBlockNotToggledAfterNewContent() throws Exception {
        StubScript script = new StubScript("glm-5.1", java.util.List.of(
                new StubScript.Turn("工具调用", java.util.List.of(
                        StubScript.sseChunk("assistant", null, null, 0, null),
                        StubScript.sseToolCallStart("call_1", "read_file"),
                        StubScript.sseToolCallArgs("{\"path\":\"README.md\",\"offset\":1,\"limit\":50}"),
                        StubScript.sseFinish("tool_calls", 55, 12)
                )),
                new StubScript.Turn("纯文本", java.util.List.of(
                        StubScript.sseChunk("assistant", null, null, 0, null),
                        StubScript.sseChunk(null, "这是后续回复", "stop", 55, 12)
                ))
        ));
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline")) {
            h.session().send("读 README.md");
            h.session().expect(Pattern.compile(TRIANGLE + " ReadFile\\(README\\.md\\)"),
                    PtyTestHarness.LLM_RESPONSE_TIMEOUT);

            h.session().send("总结一下");
            h.session().expect(Pattern.compile("这是后续回复"),
                    PtyTestHarness.LLM_RESPONSE_TIMEOUT);

            h.session().sendCtrl('O');
            Thread.sleep(500);
            String output = PtyCliSession.stripAnsi(h.session().currentOutput());
            assertTrue(output.contains("ReadFile(README.md)"),
                    "旧折叠块应保留，实际: " + output.substring(0, Math.min(500, output.length())));
        }
    }
}