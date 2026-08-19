package com.miniclaudecode.pty;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用例 2, 3, 4: 折叠块 — Ctrl+O 展开收起
 *
 * <p>对应 docs/inline-tui-manual-tests.md：
 * <ul>
 *   <li>用例 2: 单工具 read_file，折叠头 ⏵ ReadFile(...) (ctrl+o to expand)</li>
 *   <li>用例 3: 同轮多个 read_file，聚合折叠头</li>
 *   <li>用例 4: 折叠块 frozen 行为——后续 LLM 输出时旧块不变</li>
 * </ul>
 */
class FoldBlockTest {

    @Test
    void singleToolFoldHeader() throws Exception {
        StubScript script = StubScript.toolThenReply(
                "read_file", "{\"path\":\"README.md\",\"offset\":1,\"limit\":50}",
                "reading");
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline")) {
            h.session().send("帮我读 README.md 前 50 行");
            // 单工具调用折叠头：� ReadFile(...) (ctrl+o to expand)
            h.session().expect(
                    Pattern.compile("� ReadFile\\(README\\.md\\) \\(ctrl\\+o to expand\\)"),
                    PtyTestHarness.LLM_RESPONSE_TIMEOUT);
            String output = h.session().currentOutput();
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
                    Pattern.compile("⏵ ReadFile\\(README\\.md\\)"),
                    PtyTestHarness.LLM_RESPONSE_TIMEOUT);
            // Ctrl+O 展开
            h.session().sendCtrl('O');
            // 等待展开态：应出现 ⏷ collapse (ctrl+o)
            h.session().expect(
                    Pattern.compile("⏷ collapse \\(ctrl\\+o\\)"),
                    Duration.ofSeconds(10));
            // 再 Ctrl+O 折回
            h.session().sendCtrl('O');
            h.session().expect(
                    Pattern.compile("� ReadFile\\(README\\.md\\)"),
                    Duration.ofSeconds(10));
        }
    }

    @Test
    void multipleToolsInSameTurn() throws Exception {
        // 同轮多个 read_file → agent 决定，可能聚合成一个折叠块或多个
        StubScript script = StubScript.readMultipleFiles("pom.xml", "AGENTS.md", "ROADMAP.md");
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline")) {
            h.session().send("同时读 pom.xml、AGENTS.md、ROADMAP.md");
            // 等待至少一个折叠头出现
            String match = h.session().expect(
                    Pattern.compile("⏵ "),
                    PtyTestHarness.LLM_RESPONSE_TIMEOUT);
            assertNotNull(match, "应有折叠头出现，实际: " + match);
            assertTrue(h.stub().requestCount() >= 1,
                    "stub 应被调用，实际: " + h.stub().requestCount());
        }
    }

    @Test
    void frozenBlockNotToggledAfterNewContent() throws Exception {
        // 用例 4: 折叠块 frozen 行为
        // 第一轮触发一个 tool call，第二轮纯文本回复；按 Ctrl+O 应该只动最新块
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
            h.session().expect(Pattern.compile("⏵ ReadFile\\(README\\.md\\)"),
                    PtyTestHarness.LLM_RESPONSE_TIMEOUT);

            // 再问一个不需要工具的问题
            h.session().send("总结一下");
            // 等到第二轮文本回复
            h.session().expect(Pattern.compile("这是后续回复"),
                    PtyTestHarness.LLM_RESPONSE_TIMEOUT);

            // 按 Ctrl+O 应该不破坏前面的折叠块
            h.session().sendCtrl('O');
            // 短暂等待，让任何输出稳定
            Thread.sleep(500);
            String output = h.session().currentOutput();
            // ReadFile 折叠头应该还在
            assertTrue(output.contains("ReadFile(README.md)"),
                    "旧折叠块应保留，实际: " + output.substring(0, Math.min(500, output.length())));
        }
    }
}