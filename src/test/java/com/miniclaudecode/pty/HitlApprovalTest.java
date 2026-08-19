package com.miniclaudecode.pty;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用例 5-9: HITL 单字符审批 — y/a/n/s/m 五路径
 *
 * <p>对应 docs/inline-tui-manual-tests.md
 */
class HitlApprovalTest {

    private static final String HITL_PROMPT = "需要审批";
    private static final String WRITE_FILE_HAPPY = "{\"path\":\"hello.txt\",\"content\":\"hi\"}";

    @Test
    void approveWithY() throws Exception {
        StubScript script = StubScript.writeFile("hello.txt", "hi");
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline")) {
            enableHitl(h);
            h.session().send("创建 hello.txt 写 'hi'");
            // 等待 HITL 审批框
            h.session().expect(Pattern.compile(HITL_PROMPT),
                    PtyTestHarness.LLM_RESPONSE_TIMEOUT);
            // 按 y 批准
            h.session().sendRaw((byte) 'y');
            // 应创建文件
            verifyFileCreated(h, "hello.txt");
        }
    }

    @Test
    void rejectWithReason() throws Exception {
        StubScript script = StubScript.writeFile("dangerous.sh", "rm -rf /");
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline")) {
            enableHitl(h);
            h.session().send("创建 dangerous.sh");
            h.session().expect(Pattern.compile(HITL_PROMPT),
                    PtyTestHarness.LLM_RESPONSE_TIMEOUT);
            // 按 n 拒绝
            h.session().sendRaw((byte) 'n');
            // 等待原因输入提示
            h.session().expect(Pattern.compile("拒绝原因"),
                    Duration.ofSeconds(10));
            h.session().send("太危险了");
            // 文件不应被创建
            verifyFileNotCreated(h, "dangerous.sh");
        }
    }

    @Test
    void approveAll() throws Exception {
        // 第一轮：agent 调 write_file a.txt
        // 第二轮：用户说继续，agent 调 write_file b.txt
        // 第三轮：同上 c.txt
        StubScript script = new StubScript("glm-5.1", java.util.List.of(
                new StubScript.Turn("write a.txt", java.util.List.of(
                        StubScript.sseChunk("assistant", null, null, 0, null),
                        StubScript.sseToolCallStart("call_1", "write_file"),
                        StubScript.sseToolCallArgs(WRITE_FILE_HAPPY.replace("hello.txt", "a.txt").replace("hi", "1")),
                        StubScript.sseFinish("tool_calls", 55, 12)
                )),
                new StubScript.Turn("write b.txt", java.util.List.of(
                        StubScript.sseChunk("assistant", null, null, 0, null),
                        StubScript.sseToolCallStart("call_2", "write_file"),
                        StubScript.sseToolCallArgs(WRITE_FILE_HAPPY.replace("hello.txt", "b.txt").replace("hi", "2")),
                        StubScript.sseFinish("tool_calls", 55, 12)
                )),
                new StubScript.Turn("write c.txt", java.util.List.of(
                        StubScript.sseChunk("assistant", null, null, 0, null),
                        StubScript.sseToolCallStart("call_3", "write_file"),
                        StubScript.sseToolCallArgs(WRITE_FILE_HAPPY.replace("hello.txt", "c.txt").replace("hi", "3")),
                        StubScript.sseFinish("tool_calls", 55, 12)
                ))
        ));
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline")) {
            enableHitl(h);
            h.session().send("连续创建 a/b/c.txt");
            // 第一次 HITL 提示
            h.session().expect(Pattern.compile(HITL_PROMPT),
                    PtyTestHarness.LLM_RESPONSE_TIMEOUT);
            // 按 a 全部放行
            h.session().sendRaw((byte) 'a');
            // 等所有文件创建
            verifyFileCreated(h, "a.txt");
            verifyFileCreated(h, "b.txt");
            verifyFileCreated(h, "c.txt");
        }
    }

    @Test
    void skipWithS() throws Exception {
        StubScript script = StubScript.toolThenReply(
                "execute_command", "{\"command\":\"echo hello\"}", "done");
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline")) {
            enableHitl(h);
            h.session().send("执行 echo hello");
            h.session().expect(Pattern.compile(HITL_PROMPT),
                    PtyTestHarness.LLM_RESPONSE_TIMEOUT);
            // 按 s 跳过
            h.session().sendRaw((byte) 's');
            // 应看到"已跳过"提示
            String match = h.session().expect(Pattern.compile("已跳过|跳过"),
                    Duration.ofSeconds(10));
            assertNotNull(match, "应显示跳过提示");
        }
    }

    @Test
    void modifyWithM() throws Exception {
        StubScript script = StubScript.writeFile("renamed.txt", "modified");
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline")) {
            enableHitl(h);
            h.session().send("创建 renamed.txt 写 modified");
            h.session().expect(Pattern.compile(HITL_PROMPT),
                    PtyTestHarness.LLM_RESPONSE_TIMEOUT);
            // 按 m 修改参数
            h.session().sendRaw((byte) 'm');
            // 等待 JSON 输入提示
            h.session().expect(Pattern.compile("修改后的 JSON|JSON"),
                    Duration.ofSeconds(10));
            // 输入修改后的参数
            h.session().send("{\"path\":\"renamed.txt\",\"content\":\"modified\"}");
            verifyFileCreated(h, "renamed.txt");
        }
    }

    private void enableHitl(PtyTestHarness h) throws Exception {
        h.session().send("/hitl on");
        // 等待 HITL 启用确认
        h.session().expect(Pattern.compile("HITL 审批已启用|HITL ON|hitl.*on"),
                Duration.ofSeconds(10));
    }

    private void verifyFileCreated(PtyTestHarness h, String name) throws Exception {
        java.io.File f = new java.io.File(h.workDir(), name);
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(15).toMillis();
        while (System.currentTimeMillis() < deadline && !f.exists()) {
            Thread.sleep(200);
        }
        assertTrue(f.exists(), "文件应被创建: " + f.getAbsolutePath());
    }

    private void verifyFileNotCreated(PtyTestHarness h, String name) {
        java.io.File f = new java.io.File(h.workDir(), name);
        assertTrue(!f.exists(), "文件不应被创建: " + f.getAbsolutePath());
    }
}