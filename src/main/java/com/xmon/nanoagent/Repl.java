package com.xmon.nanoagent;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 处理命令行交互
 */
final class Repl {

    // 横幅与提示符不带课程编号：这是单一累加 codebase，实现完某一课之后它同时也是之前每一课，
    // 停在任何一个课号上都是错的。进度信息由 git 历史承载。
    private static final String PROMPT = "\033[36mnano-agent >> \033[0m";

    private final LineReader input;
    private final PrintWriter output;
    private final AgentLoop agentLoop;

    /**
     * 创建命令行交互循环
     *
     * @param input 命令行输入
     * @param output 命令行输出
     * @param agentLoop 智能体循环
     */
    Repl(LineReader input, PrintWriter output, AgentLoop agentLoop) {
        this.input = Objects.requireNonNull(input);
        this.output = Objects.requireNonNull(output);
        this.agentLoop = Objects.requireNonNull(agentLoop);
    }

    /**
     * 持续读取并处理用户输入
     *
     * @throws InterruptedException 工具执行被中断
     */
    void run() throws InterruptedException {
        writeLine("nano-agent — 手写 coding agent harness");
        writeLine("输入问题，回车发送。输入 q 退出。");
        writeLine("");

        while (true) {
            // try 覆盖整个回合而不只是提示符：权限审批与这里共用同一个 LineReader，
            // 在审批提示上按 Ctrl-C 或送入 EOF 时，异常从 respond 里抛出，只包住 readLine 接不住。
            try {
                String query = input.readLine(PROMPT);
                String normalized = query.strip().toLowerCase(Locale.ROOT);
                if (normalized.isEmpty() || normalized.equals("q") || normalized.equals("exit")) {
                    return;
                }

                List<String> answer = agentLoop.respond(query);
                for (String text : answer) {
                    writeLine(text);
                }
                writeLine("");
            } catch (EndOfFileException | UserInterruptException ignored) {
                return;
            }
        }
    }

    /**
     * 输出一行文本
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
