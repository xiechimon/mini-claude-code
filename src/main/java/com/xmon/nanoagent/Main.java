package com.xmon.nanoagent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.PrintWriter;
import java.nio.file.Path;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        EffectiveEnvironment environment = EffectiveEnvironment.load(workingDirectory, System.getenv());
        String modelId = environment.require("MODEL_ID");
        AnthropicClient client = createClient(environment);

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            PrintWriter output = terminal.writer();
            LineReader input = LineReaderBuilder.builder().terminal(terminal).build();
            BashTool bashTool = BashTool.production(workingDirectory, environment.values());
            ModelClient modelClient = client.messages()::create;
            AgentLoop agentLoop = new AgentLoop(modelClient, bashTool, modelId, workingDirectory, output);
            new Repl(input, output, agentLoop).run();
        } finally {
            client.close();
        }
    }

    private static AnthropicClient createClient(EffectiveEnvironment environment) {
        String apiKey = environment.get("ANTHROPIC_API_KEY");
        String authToken = environment.get("ANTHROPIC_AUTH_TOKEN");
        if (apiKey == null && authToken == null) {
            throw new IllegalStateException(
                    "Missing required environment variable: ANTHROPIC_API_KEY or ANTHROPIC_AUTH_TOKEN");
        }

        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder();
        String baseUrl = environment.get("ANTHROPIC_BASE_URL");
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        if (apiKey != null) {
            builder.apiKey(apiKey);
        }
        if (authToken != null) {
            builder.authToken(authToken);
        }
        return builder.build();
    }
}
