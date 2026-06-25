package com.xmon.nanoagent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.PrintWriter;
import java.nio.file.Path;

/**
 * 启动命令行智能体
 */
public final class Main {

    /**
     * 禁止实例化
     */
    private Main() {
    }

    /**
     * 启动程序
     *
     * @param args 命令行参数
     * @throws Exception 初始化或运行失败
     */
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

    /**
     * 创建模型客户端
     *
     * @param environment 环境变量
     * @return 模型客户端
     * @throws IllegalStateException 未配置认证信息
     */
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
