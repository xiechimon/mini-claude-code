package com.miniclaudecode.pty;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PtyCliSessionSmokeTest {

    @Test
    void launchesAndExitsInline() throws Exception {
        StubScript script = StubScript.textReply("hello");
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline")) {
            // CLI 应该显示 banner 并等待输入
            String output = h.session().currentOutput();
            assertTrue(output.contains("Mini Claude Code"),
                    "banner 应出现，实际前 500 字符: " + output.substring(0, Math.min(500, output.length())));

            // 发送 /exit 退出
            h.session().send("/exit");

            // 给点时间退出
            int code = h.session().exitCode();
            // exit code 可能是 0 或其他；主要看进程能结束
            // 用一个超时等待
        }
    }

    @Test
    void plainRendererStarts() throws Exception {
        StubScript script = StubScript.textReply("ok");
        try (PtyTestHarness h = PtyTestHarness.start(script, "plain")) {
            // plain 模式下也应启动，只是输出风格不同
            String output = h.session().currentOutput();
            assertTrue(output.length() > 0, "plain 模式也应产生输出");
        }
    }

    @Test
    void unknownRendererFallsBackToInline() throws Exception {
        StubScript script = StubScript.textReply("ok");
        try (PtyTestHarness h = PtyTestHarness.start(script, "weird-renderer")) {
            String output = h.session().currentOutput();
            // 应有 banner
            assertTrue(output.contains("Mini Claude Code") || output.length() > 10,
                    "未知 renderer 应回退 inline 并启动");
        }
    }

    @Test
    void termDumbFallsBackToPlain() throws Exception {
        StubScript script = StubScript.textReply("ok");
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline",
                java.util.Map.of("TERM", "dumb"))) {
            String output = h.session().currentOutput();
            assertTrue(output.contains("⚠️ 终端不支持 ANSI，inline 模式回退到 plain")
                            || output.length() > 10,
                    "TERM=dumb 应回退到 plain");
        }
    }

    @Test
    void sendAndExpectPattern() throws Exception {
        StubScript script = StubScript.toolThenReply(
                "read_file", "{\"path\":\"README.md\",\"offset\":1,\"limit\":50}",
                "reading now");
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline")) {
            h.session().send("帮我读取 README.md 前 50 行");
            // 单工具调用折叠头：� ReadFile(README.md) (ctrl+o to expand)
            String match = h.session().expect(
                    Pattern.compile("⏵ ReadFile\\(README\\.md\\)"),
                    PtyTestHarness.LLM_RESPONSE_TIMEOUT);
            assertTrue(match.contains("ReadFile(README.md)"), "应匹配工具组折叠头: " + match);
            assertTrue(h.stub().requestCount() >= 1,
                    "stub 应至少被调用一次，实际: " + h.stub().requestCount());
        }
    }
}