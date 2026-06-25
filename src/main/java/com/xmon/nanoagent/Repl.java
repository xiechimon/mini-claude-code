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

    private static final String PROMPT = "\033[36ms01 >> \033[0m";

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
        writeLine("s01: Agent Loop");
        writeLine("输入问题，回车发送。输入 q 退出。");
        writeLine("");

        while (true) {
            String query;
            try {
                query = input.readLine(PROMPT);
            } catch (EndOfFileException | UserInterruptException ignored) {
                return;
            }

            String normalized = query.strip().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty() || normalized.equals("q") || normalized.equals("exit")) {
                return;
            }

            List<String> answer = agentLoop.respond(query);
            for (String text : answer) {
                writeLine(text);
            }
            writeLine("");
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
