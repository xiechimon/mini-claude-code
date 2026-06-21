package com.xmon.nanoagent;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class Repl {

    private static final String PROMPT = "\033[36ms01 >> \033[0m";

    private final LineReader input;
    private final PrintWriter output;
    private final AgentLoop agentLoop;

    Repl(LineReader input, PrintWriter output, AgentLoop agentLoop) {
        this.input = Objects.requireNonNull(input);
        this.output = Objects.requireNonNull(output);
        this.agentLoop = Objects.requireNonNull(agentLoop);
    }

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

    private void writeLine(String value) {
        output.println(value);
        output.flush();
        if (output.checkError()) {
            throw new IllegalStateException("Unable to write terminal output");
        }
    }
}
