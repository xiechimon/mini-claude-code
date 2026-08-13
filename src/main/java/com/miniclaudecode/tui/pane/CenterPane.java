package com.miniclaudecode.tui.pane;

import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.LinearLayout.Alignment;
import com.googlecode.lanterna.gui2.LinearLayout.GrowPolicy;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.miniclaudecode.llm.LlmClient;
import com.miniclaudecode.tui.highlight.CodeHighlighter;
import com.miniclaudecode.util.AnsiStyle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 显示 Lanterna TUI 的对话、工具结果和流式输出 */
public class CenterPane extends Panel {

    private final LlmClient llmClient;
    private final TextBox chatArea;
    private final StringBuilder assistantBuffer;

    public CenterPane(com.miniclaudecode.config.MiniClaudeCodeConfig config, LlmClient llmClient) {
        super();
        this.llmClient = llmClient;
        this.assistantBuffer = new StringBuilder();

        setLayoutManager(new LinearLayout(Direction.VERTICAL));

        this.chatArea = new TextBox("对话开始...\n\n💡 提示：\n  - 在底部输入框输入问题\n  - Ctrl+O 折叠/展开代码块\n  - Ctrl+P 查看历史对话\n  - Ctrl+\\ 显示/隐藏文件树");
        chatArea.setReadOnly(true);

        addComponent(chatArea.setLayoutData(
                LinearLayout.createLayoutData(Alignment.Fill, GrowPolicy.CanGrow)));
    }

    /**
     * 用户消息回调（从 RootPane 转发）
     *
     * @param message 用户输入的消息
     */
    public void onUserMessage(String message) {
        appendUserMessage(message);
    }

    public void appendSystemMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        chatArea.setText(chatArea.getText() + "\n💡 系统:\n" + message.trim() + "\n");
        scrollToBottom();
    }

    public void appendAssistantOutput(String output) {
        if (output == null || output.isBlank()) {
            return;
        }
        chatArea.setText(chatArea.getText() + "\n🤖 Mini Claude Code:\n" + output.trim() + "\n");
        scrollToBottom();
    }

    private void appendUserMessage(String message) {
        String rendered = renderMarkdown(message);
        chatArea.setText(chatArea.getText() + "\n👤 你:\n" + rendered + "\n");
        scrollToBottom();
    }

    public void appendAssistantChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        assistantBuffer.append(chunk);
        flushAssistantBuffer();
    }

    private synchronized void flushAssistantBuffer() {
        if (assistantBuffer.length() == 0) {
            return;
        }

        String content = assistantBuffer.toString();
        assistantBuffer.setLength(0);

        String rendered = renderMarkdown(content);
        chatArea.setText(chatArea.getText() + rendered);
        scrollToBottom();
    }

    public void appendToolCall(String toolName, String args) {
        String toolBlock = "🔧 工具调用: " + (toolName != null ? toolName : "unknown") + "\n"
                + (args != null ? "  参数: " + args : "")
                + "\n";
        chatArea.setText(chatArea.getText() + "\n" + toolBlock);
        scrollToBottom();
    }

    public void appendToolResult(String result) {
        String truncated = truncateResult(result, 500);
        String resultBlock = "📤 工具结果:\n" + truncated + "\n";
        chatArea.setText(chatArea.getText() + resultBlock);
        scrollToBottom();
    }

    private String renderMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        text = highlightCodeBlocks(text);

        text = replaceAllRegex(text, "\\*\\*(.+?)\\*\\*", m -> AnsiStyle.emphasis(m.group(1)));

        text = replaceAllRegex(text, "`(.+?)`", m -> AnsiStyle.codeLabel(m.group(1)));

        return text;
    }

    private static String replaceAllRegex(String text, String regex, java.util.function.Function<Matcher, String> replacer) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, replacer.apply(matcher));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String highlightCodeBlocks(String text) {
        StringBuilder result = new StringBuilder();
        int i = 0;

        while (i < text.length()) {
            // 查找代码块开始 ```lang
            if (i < text.length() - 2 && text.charAt(i) == '`' && text.charAt(i + 1) == '`' && text.charAt(i + 2) == '`') {
                int start = i + 3;
                int langEnd = text.indexOf('\n', start);
                String lang = "text";
                int codeStart;
                if (langEnd > 0) {
                    lang = text.substring(start, langEnd).trim();
                    codeStart = langEnd + 1;
                } else {
                    codeStart = start;
                }

                // 查找代码块结束
                int codeEnd = text.indexOf("```", codeStart);
                if (codeEnd < 0) {
                    codeEnd = text.length();
                }

                String code = text.substring(codeStart, codeEnd);
                String highlighted = CodeHighlighter.highlight(code, lang);

                result.append("\n").append(highlighted);
                i = codeEnd + 3;
            } else {
                result.append(text.charAt(i));
                i++;
            }
        }

        return result.toString();
    }

    private static String truncateResult(String result, int maxLength) {
        if (result == null) {
            return "null";
        }
        if (result.length() <= maxLength) {
            return result;
        }
        return result.substring(0, maxLength) + "\n... (截断，共 " + result.length() + " 字符)";
    }

    private void scrollToBottom() {
    }

    public void clear() {
        chatArea.setText("");
        assistantBuffer.setLength(0);
    }
}
