package com.miniclaudecode.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalMarkdownRendererTest {
    static {
        System.setProperty("mini-claude-code.render.color", "false");
    }

    @Test
    void rendersHeadingListTableAndCodeBlockToTerminalFriendlyText() {
        String markdown = """
                # 规划思考

                1. **分析请求**
                - 列出当前目录

                | 名称 | 说明 |
                | --- | --- |
                | src | 源码 |
                | pom.xml | Maven 配置 |

                ```java
                System.out.println("hello");
                ```
                """;

        String raw = TerminalMarkdownRenderer.render(markdown);
        // 套件内 AnsiStyle 可能先于本类静态块加载（颜色开）；断言剥离 ANSI 保确定性
        String rendered = raw.replaceAll("\\x1B\\[[0-9;]*m", "");

        assertTrue(rendered.contains("规划思考"));
        assertTrue(rendered.contains("1. 分析请求"));
        assertTrue(rendered.contains("- 列出当前目录"));
        assertTrue(rendered.contains("| 名称"));
        assertTrue(rendered.contains("| src"));
        assertTrue(rendered.contains("源码"));
        assertTrue(rendered.contains("┌─ code: java"));
        assertTrue(rendered.contains("└─ end"));
        assertTrue(rendered.contains("    System.out.println(\"hello\");"));
    }

    @Test
    void supportsIncrementalStreamingAppend() {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.io.PrintStream stream = new java.io.PrintStream(output);
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(stream);

        renderer.append("## 标题\n- 第一");
        renderer.append("项\n- 第二项\n");
        renderer.finish();

        String rendered = output.toString();
        assertTrue(rendered.contains("标题"));
        assertTrue(rendered.contains("- 第一项"));
        assertTrue(rendered.contains("- 第二项"));
    }

    @Test
    void preservesNestedListIndentation() {
        String markdown = """
                1. 总体分析
                  - 第一层补充
                    - 第二层补充
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown);

        assertTrue(rendered.contains("1. 总体分析"));
        assertTrue(rendered.contains("  - 第一层补充"));
        assertTrue(rendered.contains("    - 第二层补充"));
    }

    @Test
    void fallsBackToKeyValueLayoutForLongTwoColumnTable() {
        String markdown = """
                | 目录名 | 说明 |
                | --- | --- |
                | src/main/java/com/miniclaudecode | 这里存放 Mini Claude Code 的主要 Java 源码实现与相关模块 |
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown);

        assertTrue(rendered.contains("目录名 / 说明"));
        assertTrue(rendered.contains("- src/main/java/com/miniclaudecode"));
        assertTrue(rendered.contains("这里存放 Mini Claude Code 的主要 Java 源码实现与相关模块"));
    }

    @Test
    void wrapsWideMultiColumnTableInsideTerminalWidth() {
        String markdown = """
                | 特性 | StepFun (Step) | Kimi | GLM | DeepSeek |
                | --- | --- | --- | --- | --- |
                | 基础 URL | https://api.stepfun.com/v1 | https://api.moonshot.ai/v1 | 动态选择（glm-5v用多模态API，其他用编码API） | https://api.deepseek.com/chat/completions |
                | 推理能力 | ✅（需配置 reasoningformat="deepseek-style"） | ✅（需发送推理历史） | ✅ | ✅ |
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown, 72);

        assertTrue(rendered.contains("| 特性"));
        assertFalse(rendered.contains("https://api.deepseek.com/chat/completions |"));
        for (String line : rendered.split("\\R")) {
            String plain = line.replaceAll("\\[[;\\d]*m", "");
            int width = TerminalMarkdownRenderer.displayWidth(plain);
            assertTrue(width <= 72, "line exceeds table width (" + width + "): " + plain);
        }
    }

    // ---- 缺陷回归测试 ----

    @Test
    void preservesLiteralUnderscoresInPathsAndIdentifiers() {
        String markdown = "读 `file_name.txt` 和 `my_variable`";
        String rendered = TerminalMarkdownRenderer.render(markdown);
        assertTrue(rendered.contains("file_name.txt"),
                "下划线不应被吃掉: " + rendered);
        assertTrue(rendered.contains("my_variable"),
                "标识符下划线不应被吃掉: " + rendered);
    }

    @Test
    void preservesUnderscoresInTableCells() {
        String markdown = "| 路径 | 大小 |\n| --- | --- |\n| src/main/java/com/foo/my_module | 42 |\n";
        String rendered = TerminalMarkdownRenderer.render(markdown);
        assertTrue(rendered.contains("my_module"),
                "表格内下划线不应被吃掉: " + rendered);
    }

    @Test
    void handlesBackslashEscapeForMarkdownMarkers() {
        String markdown = "字面 \\*星号\\* 和 \\_下划线\\_";
        String rendered = TerminalMarkdownRenderer.render(markdown);
        assertTrue(rendered.contains("*星号*"),
                "\\* 应渲染为字面星号: " + rendered);
        assertTrue(rendered.contains("_下划线_"),
                "\\_ 应渲染为字面下划线: " + rendered);
    }

    @Test
    void rendersHorizontalRuleAsDivider() {
        String markdown = "上段\n\n---\n\n下段";
        String rendered = TerminalMarkdownRenderer.render(markdown);
        assertFalse(rendered.contains("\n---\n"),
                "--- 不应作为段落文本出现: " + rendered);
        assertTrue(rendered.contains("─") || rendered.contains("=") || rendered.contains("┄"),
                "水平线应渲染为分隔符: " + rendered);
    }

    @Test
    void rendersAsteriskHorizontalRule() {
        String markdown = "上段\n\n***\n\n下段";
        String rendered = TerminalMarkdownRenderer.render(markdown);
        assertFalse(rendered.contains("\n***\n"),
                "*** 不应作为段落文本出现: " + rendered);
    }

    @Test
    void rendersIndentedCodeBlock() {
        String markdown = "段落\n\n    System.out.println(\"indented\");\n    x = 1\n\n后续";
        String rendered = TerminalMarkdownRenderer.render(markdown);
        assertTrue(rendered.contains("System.out.println(\"indented\");"),
                "4 空格缩进代码块应保留: " + rendered);
        assertTrue(rendered.contains("x = 1"),
                "第二行缩进代码应保留: " + rendered);
    }

    @Test
    void preservesLinkUrl() {
        String markdown = "看 [官方文档](https://example.com/docs) 了解更多";
        String rendered = TerminalMarkdownRenderer.render(markdown);
        assertTrue(rendered.contains("官方文档"),
                "链接文本应保留: " + rendered);
        assertTrue(rendered.contains("https://example.com/docs"),
                "链接 URL 应保留: " + rendered);
    }

    @Test
    void supportsNestedBlockquote() {
        String markdown = "外层\n\n> 一级引用\n>> 二级引用\n>>> 三级引用\n\n后续";
        String rendered = TerminalMarkdownRenderer.render(markdown);
        assertTrue(rendered.contains("一级引用"), "一级引用文本: " + rendered);
        assertTrue(rendered.contains("二级引用"), "二级引用文本: " + rendered);
        assertTrue(rendered.contains("三级引用"), "三级引用文本: " + rendered);
    }

    @Test
    void rendersStrikethroughWithVisualIndicator() {
        String markdown = "这是 ~~已废弃~~ 的方案";
        String rendered = TerminalMarkdownRenderer.render(markdown);
        assertTrue(rendered.contains("已废弃"),
                "删除线文字应保留: " + rendered);
        boolean hasVisualMarker =
                rendered.contains("[~已废弃~]")
                        || rendered.contains("[已废弃]")
                        || rendered.contains("~~已废弃~~")
                        || rendered.matches("(?s).*\\x1B\\[9m.*已废弃.*");
        assertTrue(hasVisualMarker,
                "删除线应有视觉标记 [~text~]: " + rendered);
    }
}