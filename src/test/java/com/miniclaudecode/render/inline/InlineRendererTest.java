package com.miniclaudecode.render.inline;

import com.miniclaudecode.hitl.ApprovalRequest;
import com.miniclaudecode.hitl.ApprovalResult;
import com.miniclaudecode.llm.LlmClient;
import com.miniclaudecode.render.StatusInfo;
import org.jline.reader.LineReader;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InlineRendererTest {

    private static LlmClient.ToolCall tc(String name, String args) {
        return new LlmClient.ToolCall(name + "-id", new LlmClient.ToolCall.Function(name, args));
    }

    @Test
    void onAnsiTerminalEnablesStatusBar() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertTrue(renderer.hasStatusBar());
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
        } finally {
            renderer.close();
        }
    }

    @Test
    void onSmallTerminalDisablesStatusBar() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(40, 4));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertFalse(renderer.hasStatusBar());
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamReturnsSystemOut() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertNotNull(renderer.stream());
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamUsesPrintAboveWhenLineReaderIsReading() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        LineReader lineReader = Mockito.mock(LineReader.class);
        Mockito.when(lineReader.isReading()).thenReturn(true);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.bindLineReader(lineReader);
            renderer.beginTurn();
            renderer.stream().println("异步通知");

            Mockito.verify(lineReader).printAbove("异步通知\n");
            assertFalse(sink.toString(StandardCharsets.UTF_8).contains("异步通知"));
        } finally {
            renderer.close();
        }
    }

    @Test
    void startupActionRunsAfterLogoIsPrinted() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        LineReader lineReader = Mockito.mock(LineReader.class);
        var widgets = new HashMap<String, org.jline.reader.Widget>();
        Mockito.when(lineReader.getWidgets()).thenReturn(widgets);
        List<String> events = new ArrayList<>();
        Mockito.doAnswer(invocation -> {
            events.add("logo");
            return null;
        }).when(lineReader).printAbove(Mockito.anyString());

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            renderer.bindLineReader(lineReader);
            renderer.installStartupScreen(List.of("Mini Claude Code"), () -> events.add("mcp"));

            assertTrue(widgets.get(LineReader.CALLBACK_INIT).apply());
            assertEquals(List.of("logo", "mcp"), events);
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamedCodeBlockUsesCollapsedHeaderWithPrintAbove() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        LineReader lineReader = Mockito.mock(LineReader.class);
        Mockito.when(lineReader.isReading()).thenReturn(true);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.bindLineReader(lineReader);
            renderer.beginTurn();
            renderer.stream().println("┌─ code: bash");
            renderer.stream().println("    echo hi");
            renderer.stream().println("└─ end");

            ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
            Mockito.verify(lineReader).printAbove(output.capture());
            String rendered = output.getValue();
            assertTrue(rendered.contains("⏵"), rendered);
            assertTrue(rendered.contains("code: bash"), rendered);
            assertTrue(rendered.contains("1 行"), rendered);
            assertFalse(rendered.contains("echo hi"), rendered);
            assertFalse(sink.toString(StandardCharsets.UTF_8).contains("echo hi"));
        } finally {
            renderer.close();
        }
    }

    @Test
    void inlineRendererKeepsPromptInTranscriptFlow() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), true);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.start();
            sink.reset();
            renderer.beforeInput();
            renderer.afterInput();

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertEquals("* ", renderer.inputPrompt());
            assertTrue(renderer.inputRightPrompt().contains("@path"));
            assertFalse(emitted.contains("[39;1H"), "LineReader should own the input row: " + emitted);
            assertFalse(emitted.contains("[37;1H"), "renderer should not force transcript cursor rows: " + emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void thinkingPanelRendersJLineActivityReasoningAndClears() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), true);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginThinking("Thinking");
            renderer.appendThinking("先分析用户输入\n再检查状态栏边界");

            String rendered = sink.toString(StandardCharsets.UTF_8);
            assertTrue(renderer.supportsThinkingPanel());
            assertTrue(rendered.contains("Thinking"), rendered);
            assertTrue(rendered.contains("先分析用户输入"), rendered);
            assertTrue(rendered.contains("再检查状态栏边界"), rendered);
            assertTrue(rendered.contains("|") || rendered.contains("│"),
                    "activity display should show live reasoning quote content: " + rendered);

            sink.reset();
            renderer.endThinking();
            String cleared = sink.toString(StandardCharsets.UTF_8);
            assertFalse(cleared.contains(AnsiSeq.CLEAR_TO_EOS),
                    "activity clearing must not clear to screen end and erase transcript scrollback: " + cleared);
        } finally {
            renderer.close();
        }
    }

    @Test
    void activityPanelOmitsCancelHintForNonCancelableWork() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), true);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginActivity("Compacting conversation", "正在整理早期对话并生成摘要");

            String rendered = sink.toString(StandardCharsets.UTF_8);
            assertTrue(renderer.supportsActivityPanel());
            assertTrue(rendered.contains("Compacting conversation"), rendered);
            assertTrue(rendered.contains("▰"), rendered);
            assertTrue(rendered.contains("▱"), rendered);
            assertTrue(rendered.contains("%"), rendered);
            assertFalse(rendered.contains("正在整理早期对话"), rendered);
            assertFalse(rendered.contains("esc to cancel"), rendered);
        } finally {
            renderer.endActivity();
            renderer.close();
        }
    }

    @Test
    void toggleLastBlockRedrawsTranscriptAroundToolBlock() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 4));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            renderer.stream().println("before");
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            renderer.stream().println("after");

            sink.reset();
            assertTrue(renderer.toggleLastBlock());

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("before"), emitted);
            assertTrue(emitted.contains("README.md"), emitted);
            assertTrue(emitted.contains("after"), emitted);
            assertTrue(emitted.contains("collapse"), emitted);
            assertTrue(emitted.contains(AnsiSeq.CLEAR_TO_EOS), emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void closeIsIdempotent() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        renderer.start();
        renderer.close();
        renderer.close();
    }

    @Test
    void promptApprovalDelegatesToFallback() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            // 非 TTY 会回退 PlainRenderer，此处只验证空 stdin 可安全拒绝
            ApprovalRequest req = ApprovalRequest.of("write_file", "{}", "test");
            ApprovalResult result = renderer.promptApproval(req);
            assertNotNull(result);
        } finally {
            renderer.close();
        }
    }

    @Test
    void openPaletteReturnsMinusOneOnNoInput() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            int idx = renderer.openPalette("title", java.util.List.of("a", "b"));
            assertEquals(-1, idx);
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamedCodeBlockCollapsesIntoFoldableHeader() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            // 模拟 TerminalMarkdownRenderer 输出的代码块（手写预渲染好的 markup）
            renderer.stream().println("┌─ code: java");
            renderer.stream().println("    public class Main {");
            renderer.stream().println("    }");
            renderer.stream().println("└─ end");

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("⏵"), "应该出现折叠箭头: " + emitted);
            assertTrue(emitted.contains("code: java"), emitted);
            assertTrue(emitted.contains("2 行"), "应统计 body 行数: " + emitted);
            assertTrue(emitted.contains("ctrl+o"), emitted);
            // 代码体只进入缓冲，输出应仅包含折叠标记与 ANSI 重绘序列
            assertFalse(emitted.contains("public class Main {"),
                    "代码体应被折叠后不再可见: " + emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamedCodeBlockTogglesToExpandedOnRedraw() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            renderer.stream().println("┌─ code: bash");
            renderer.stream().println("    echo hi");
            renderer.stream().println("└─ end");

            sink.reset();
            assertTrue(renderer.toggleLastBlock(), "代码块应可 toggle");

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("echo hi"), "展开后应看到代码体: " + emitted);
            assertTrue(emitted.contains("┌─ code: bash"), emitted);
            assertTrue(emitted.contains("└─ end"), emitted);
            assertTrue(emitted.contains("⏷"), "展开态应显示 collapse 提示: " + emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void nonCodeStreamingTextStillFlowsThrough() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            renderer.stream().println("普通段落 1");
            renderer.stream().println("普通段落 2");

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("普通段落 1"), emitted);
            assertTrue(emitted.contains("普通段落 2"), emitted);
            assertFalse(emitted.contains("⏵"));
        } finally {
            renderer.close();
        }
    }
}
