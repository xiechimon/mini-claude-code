package com.miniclaudecode.pty;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用例 18, 19: 行内 diff — 修改已有文件 / 新建文件
 *
 * <p>对应 docs/inline-tui-manual-tests.md
 *
 * <p>用例 17（修改 ROADMAP.md）需要 git 副作用清理；这里用 ROADMAP.md 的预创建副本做修改
 */
class InlineDiffTest {

    @Test
    void diffAppearsForNewFile() throws Exception {
        StubScript script = StubScript.writeFile("hello-test.txt", "line 1\nline 2");
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline")) {
            h.session().send("创建 hello-test.txt");
            // write_file 触发折叠块；diff 在折叠块内，展开后可见
            h.session().expect(
                    Pattern.compile("⏵ .*写入.*hello-test\\.txt"),
                    PtyTestHarness.LLM_RESPONSE_TIMEOUT);
            // 文件应被创建
            verifyFileCreated(h, "hello-test.txt");
            // 清理
            new java.io.File(h.workDir(), "hello-test.txt").delete();
        }
    }

    @Test
    void diffAppearsForModifiedFile() throws Exception {
        // 预创建要修改的文件
        java.io.File target = new java.io.File(
                System.getProperty("user.dir"), "ROADMAP.md");
        String originalContent = java.nio.file.Files.readString(target.toPath());
        String modifiedLine = "# Mini Claude Code 路线图（v16.1 测试）";

        // stub 覆盖 ROADMAP.md
        String newContent = modifiedLine + "\n" +
                originalContent.substring(originalContent.indexOf('\n') + 1);

        StubScript script = StubScript.writeFile("ROADMAP.md", newContent);
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline")) {
            try {
                h.session().send("把 ROADMAP.md 第一行改成 '" + modifiedLine + "'");
                // write_file 触发折叠块
                h.session().expect(
                        Pattern.compile("� .*写入.*ROADMAP\\.md"),
                        PtyTestHarness.LLM_RESPONSE_TIMEOUT);
            } finally {
                java.nio.file.Files.writeString(target.toPath(), originalContent);
            }
        }
    }

    private void verifyFileCreated(PtyTestHarness h, String name) throws Exception {
        java.io.File f = new java.io.File(h.workDir(), name);
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(15).toMillis();
        while (System.currentTimeMillis() < deadline && !f.exists()) {
            Thread.sleep(200);
        }
        assertTrue(f.exists(), "文件应被创建: " + f.getAbsolutePath());
    }
}