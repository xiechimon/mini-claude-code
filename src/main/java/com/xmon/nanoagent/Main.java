package com.xmon.nanoagent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.xmon.nanoagent.core.AgentLoop;
import com.xmon.nanoagent.core.ModelClient;
import com.xmon.nanoagent.core.StreamingModelClient;
import com.xmon.nanoagent.host.EffectiveEnvironment;
import com.xmon.nanoagent.host.Workspace;
import com.xmon.nanoagent.permission.PermissionGate;
import com.xmon.nanoagent.permission.PermissionRule;
import com.xmon.nanoagent.permission.TerminalApprovalPrompt;
import com.xmon.nanoagent.tool.BashTool;
import com.xmon.nanoagent.tool.ToolRegistry;
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
        // 解析符号链接后捕获一次工作目录，使 system prompt、Bash 子进程和 Workspace 使用同一种路径表示。
        Path workingDirectory = Path.of("").toAbsolutePath().toRealPath();
        EffectiveEnvironment environment = EffectiveEnvironment.load(workingDirectory, System.getenv());
        String modelId = environment.require("MODEL_ID");
        AnthropicClient client = createClient(environment);

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            PrintWriter output = terminal.writer();
            LineReader input = LineReaderBuilder.builder().terminal(terminal).build();
            BashTool bashTool = BashTool.production(workingDirectory, environment.values());
            Workspace workspace = new Workspace(workingDirectory);
            ToolRegistry toolRegistry = new ToolRegistry(bashTool, workspace);
            PermissionGate permissionGate = new PermissionGate(
                    PermissionRule.defaults(workspace), new TerminalApprovalPrompt(input, output));
            // 默认走流式；非流式实现 BlockingModelClient 同样产出事件流，仅用于测试与对照。
            ModelClient modelClient = new StreamingModelClient(client.messages());
            AgentLoop agentLoop = new AgentLoop(
                    modelClient, toolRegistry, permissionGate, modelId, workingDirectory, output);
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
