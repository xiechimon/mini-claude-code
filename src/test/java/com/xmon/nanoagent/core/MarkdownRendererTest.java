package com.xmon.nanoagent.core;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试 Markdown 增量渲染器的落定策略与 AST → 文本行映射
 *
 * <p>用一个恒等主题替换 ANSI 着色，把样式调用还原成纯文本标记，
 * 使断言聚焦在增量输出与节点分发上，而不是具体的转义序列。
 */
final class MarkdownRendererTest {

    private final StringWriter out = new StringWriter();
    private final MarkdownRenderer renderer =
            new MarkdownRenderer(new PrintWriter(out), new IdentityTheme());

    @Test
    void trailingIncompleteLineIsHeldBackUntilCompleted() {
        renderer.append("hello");

        assertEquals("", out.toString());

        renderer.append(" world\n");

        assertEquals("hello world\n", out.toString());
    }

    @Test
    void flushForcesOutTheHeldBackTrailingLine() {
        renderer.append("incomplete");
        renderer.flush();

        assertEquals("incomplete\n", out.toString());
    }

    @Test
    void flushIsANoOpWhenNothingIsBuffered() {
        renderer.flush();

        assertEquals("", out.toString());
    }

    @Test
    void flushAfterACompletedLineDoesNotDuplicateIt() {
        renderer.append("done\n");
        renderer.flush();

        assertEquals("done\n", out.toString());
    }

    @Test
    void multipleCompletedParagraphsEmitInOrder() {
        renderer.append("first\n\nsecond\n");

        assertEquals("first\nsecond\n", out.toString());
    }

    @Test
    void headingLevelIsPassedToTheTheme() {
        renderer.append("# Title\n## Sub\n### Deep\n");

        assertEquals(
                "heading(Title,1)\nheading(Sub,2)\nheading(Deep,3)\n",
                out.toString());
    }

    @Test
    void fencedCodeBlockKeepsItsLanguageAndContents() {
        renderer.append("```java\nint x = 1;\n```\n");

        assertEquals("border(```java)\n  int x = 1;\nborder(```)\n", out.toString());
    }

    @Test
    void bulletAndOrderedListsRenderTheirMarkers() {
        renderer.append("- alpha\n- beta\n\n1. first\n2. second\n");

        assertEquals(
                "bullet(• )alpha\nbullet(• )beta\n"
                        + "bullet(1. )first\nbullet(2. )second\n",
                out.toString());
    }

    @Test
    void blockquoteAndThematicBreakMapToTheirOwnStyles() {
        renderer.append("> quoted\n\n---\n");

        assertEquals("quote(│ quoted)\n<hr>\n", out.toString());
    }

    @Test
    void inlineEmphasisCodeStrikethroughAndLinksAreDispatched() {
        renderer.append("**bold**, *italic*, `code`, ~~gone~~, [text](https://x)\n");

        assertEquals(
                "bold(bold), italic(italic), code(code), strike(gone), link(text|https://x)\n",
                out.toString());
    }

    @Test
    void softLineBreaksWithinAParagraphJoinWithASpace() {
        renderer.append("first\nsecond\n");

        assertEquals("first second\n", out.toString());
    }

    @Test
    void streamingCodeBlockDoesNotLoseContent() {
        // 模拟流式到达：```bash\nmvn test\n```\n
        renderer.append("可以执行：\n\n");
        renderer.append("```bash\n");
        renderer.append("mvn test\n");
        renderer.append("```\n");
        renderer.append("需要我运行看看吗？\n");

        String output = out.toString();
        assertTrue(output.contains("mvn test"), "code block content should not be lost: " + output);
        assertTrue(output.contains("需要我运行看看吗？"), "text after code block should appear: " + output);
    }

    @Test
    void streamingUnclosedCodeBlockDoesNotDuplicateContent() {
        // 模拟代码块关闭前段落被渲染，关闭后变为代码块
        renderer.append("text\n\n");
        renderer.append("```bash\n");
        renderer.append("mvn test\n");
        // 此时无关闭 fence，flexmark 的行为决定输出
        renderer.flush();
        String afterFlush = out.toString();
        // flush 修补后不应有重复
        long bashCount = afterFlush.lines().filter(l -> l.contains("```bash")).count();
        assertTrue(bashCount <= 1, "```bash should not appear twice: " + afterFlush);
    }

    @Test
    void streamingOrderedListDoesNotDuplicateItems() {
        // 模拟模型流式输出有序列表
        renderer.append("请问你想创建什么文件？请告诉我：\n");
        renderer.append("1. 文件名（例如 test.txt）\n");
        renderer.append("2. 内容（需要写入什么）\n");
        renderer.append("我可以帮你创建。\n");

        String output = out.toString();
        long count = output.lines().filter(l -> l.contains("文件名")).count();
        assertEquals(1, count, "ordered list item should not be duplicated: " + output);
    }

    @Test
    void streamingCodeBlockDoesNotDuplicateFences() {
        // 模拟模型输出：段落 + 代码块（含内容）
        renderer.append("已创建文件 hello.txt，内容如下：\n");
        renderer.append("```text\n");
        renderer.append("  你好，世界！\n");
        renderer.append("  这是一个示例文件。\n");
        renderer.append("```\n");

        String output = out.toString();
        long fenceCount = output.lines().filter(l -> l.contains("border")).count();
        assertEquals(2, fenceCount, "should have exactly 2 borders (open+close), got: " + output);
    }

    private static final class IdentityTheme implements MarkdownTheme {

        @Override
        public String heading(String text, int level) {
            return "heading(" + text + "," + level + ")";
        }

        @Override
        public String bold(String text) {
            return "bold(" + text + ")";
        }

        @Override
        public String italic(String text) {
            return "italic(" + text + ")";
        }

        @Override
        public String code(String text) {
            return "code(" + text + ")";
        }

        @Override
        public String codeBlock(String text) {
            return text;
        }

        @Override
        public String codeBlockBorder(String text) {
            return "border(" + text + ")";
        }

        @Override
        public String blockquote(String text) {
            return "quote(" + text + ")";
        }

        @Override
        public String listBullet(String marker) {
            return "bullet(" + marker + ")";
        }

        @Override
        public String link(String text, String url) {
            return "link(" + text + "|" + url + ")";
        }

        @Override
        public String hr() {
            return "<hr>";
        }

        @Override
        public String strikethrough(String text) {
            return "strike(" + text + ")";
        }
    }

    @Test
    void strayClosingFenceAfterFlushIsSwallowed() {
        // 模型文本以未闭合 ```text 结尾 → tool 调用触发 flush → 工具回复后
        // 模型又发出 ``` 闭合。这个孤悬的 ``` 是上一代码块的关闭，不是新代码块的开头。
        StringWriter sw = new StringWriter();
        MarkdownRenderer r = new MarkdownRenderer(new PrintWriter(sw), new DefaultMarkdownTheme());

        r.append("已创建文件 hello.txt，内容如下：\n");
        r.append("```text\n");
        r.append("你好，世界！\n这是一个示例文件。\n");
        r.flush(); // 模拟 tool 触发 flush
        r.append("```\n"); // 工具后输出的孤悬关闭 fence

        String output = sw.toString();
        assertTrue(output.contains("```text"), "should keep opening border: " + output);
        // 只有 ```text 这一个边框标记，不该再有第二个孤悬 ```
        assertFalse(output.contains("\n```\n"),
                "stray fence should not render as a second border line: " + output);
    }

    @Test
    void completedLinesAreNotReprintedWhenAnUnclosedFenceOpensAndClosesInOneTurn() {
        // 整段文本一次流式送达，途中出现一对 ``` 围栏把内容行暂时吞掉（未闭合时不渲染内容），
        // 围栏闭合后再补出。若 lastLineCount 跟着行数回退，闭合后会把围栏前的已完成行再打一遍。
        StringWriter sw = new StringWriter();
        MarkdownRenderer r = new MarkdownRenderer(new PrintWriter(sw), new IdentityTheme());

        String md = "before\n```\nhello\nworld\n```\nafter\n";
        for (int i = 0; i < md.length(); i++) {
            r.append(md.substring(i, i + 1));
        }
        r.flush();

        assertEquals("before\nborder(```)\n  hello\n  world\nborder(```)\nafter\n", sw.toString());
    }
}
