package com.miniclaudecode.pty;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用例 18, 19: 行内 diff — 修改已有文件 / 新建文件
 *
 * <p>对应 docs/inline-tui-manual-tests.md
 */
class InlineDiffTest {

    private static final String FOLD_HEADER = "\\u23F5 写入 1 个文件"; // ⏵ 写入 1 个文件

    @Test
    void diffAppearsForNewFile() throws Exception {
        StubScript script = StubScript.writeFile("hello-test.txt", "line 1\\nline 2");
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline")) {
            h.session().send("创建 hello-test.txt");
            h.session().expect(
                    Pattern.compile(FOLD_HEADER),
                    PtyTestHarness.LLM_RESPONSE_TIMEOUT);
            verifyFileCreated(h, "hello-test.txt");
            new java.io.File(h.workDir(), "hello-test.txt").delete();
        }
    }

    @Test
    void diffAppearsForModifiedFile() throws Exception {
        java.io.File target = new java.io.File(
                System.getProperty("user.dir"), "ROADMAP.md");
        String originalContent = java.nio.file.Files.readString(target.toPath());
        String modifiedLine = "# Mini Claude Code 路线图（v16.1 测试）";
        String newContent = modifiedLine + "\n" +
                originalContent.substring(originalContent.indexOf('\n') + 1);

        StubScript script = StubScript.writeFile("ROADMAP.md", newContent);
        try (PtyTestHarness h = PtyTestHarness.start(script, "inline")) {
            try {
                h.session().send("把 ROADMAP.md 第一行改成 '" + modifiedLine + "'");
                h.session().expect(
                        Pattern.compile(FOLD_HEADER),
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
        if (!f.exists()) {
            String output = PtyCliSession.stripAnsi(h.session().currentOutput());
            throw new AssertionError("文件应被创建: " + f.getAbsolutePath()
                    + "\n当前 buffer 末尾 2000 字符:\n"
                    + output.substring(Math.max(0, output.length() - 2000)));
        }
    }
}