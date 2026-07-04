package com.xmon.nanoagent;

import com.anthropic.core.JsonValue;
import org.jline.reader.LineReader;

import java.io.PrintWriter;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 在终端上阻塞询问用户是否批准工具调用
 *
 * <p>与 REPL 共用同一个 {@link LineReader}：输入流结束或用户中断时，JLine 抛出的异常沿调用链冒泡，
 * 由 {@link Repl} 包住整个回合的退出分支接住，整个会话随之结束。这与课程行为一致，区别只在于此处的
 * 异常是有类型的。注意这条依赖 {@code Repl} 的 try 覆盖 {@code respond} 调用——只包住主提示符是接不住的。
 */
final class TerminalApprovalPrompt implements ApprovalPrompt {

    private static final Set<String> AFFIRMATIVE = Set.of("y", "yes");
    private static final String YELLOW = "\033[33m";
    private static final String RESET = "\033[0m";

    private final LineReader input;
    private final PrintWriter output;

    /**
     * 创建终端审批器
     *
     * @param input 命令行输入，与 REPL 共用
     * @param output 命令行输出
     */
    TerminalApprovalPrompt(LineReader input, PrintWriter output) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
    }

    /**
     * 打印待批准的调用并读取一行答复
     *
     * @param toolName 模型给出的工具名
     * @param toolInput 模型给出的工具输入
     * @param reason 命中规则给出的询问原因
     * @return 答复去除首尾空白并转小写后为 {@code y} 或 {@code yes} 时为 {@code true}
     */
    @Override
    public boolean approve(String toolName, JsonValue toolInput, String reason) {
        writeLine("");
        writeLine(YELLOW + "[permission] " + reason + RESET);
        writeLine("   Tool: " + toolName + "(" + toolInput + ")");
        String answer = input.readLine("   Allow? [y/N] ");
        return AFFIRMATIVE.contains(answer.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * 输出一行终端文本
     *
     * @param value 文本内容
     * @throws IllegalStateException 输出失败
     */
    private void writeLine(String value) {
        output.println(value);
        output.flush();
        if (output.checkError()) {
            throw new IllegalStateException("Unable to write terminal output");
        }
    }
}
