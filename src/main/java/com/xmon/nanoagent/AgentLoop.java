package com.xmon.nanoagent;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class AgentLoop {

    private static final long MAX_TOKENS = 8_000L;
    private static final int MAX_PREVIEW_CODE_POINTS = 200;
    private static final String YELLOW = "\033[33m";
    private static final String RESET = "\033[0m";
    private static final Tool BASH_DEFINITION = createBashDefinition();

    private final ModelClient modelClient;
    private final BashTool bashTool;
    private final String modelId;
    private final String systemPrompt;
    private final PrintWriter terminal;
    private final List<MessageParam> history = new ArrayList<>();

    AgentLoop(
            ModelClient modelClient,
            BashTool bashTool,
            String modelId,
            Path workingDirectory,
            PrintWriter terminal) {
        this.modelClient = Objects.requireNonNull(modelClient);
        this.bashTool = Objects.requireNonNull(bashTool);
        this.modelId = Objects.requireNonNull(modelId);
        this.systemPrompt = "You are a coding agent at " + Objects.requireNonNull(workingDirectory)
                + ". Use bash to solve tasks. Act, don't explain.";
        this.terminal = Objects.requireNonNull(terminal);
    }

    List<String> respond(String rawInput) throws InterruptedException {
        history.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(rawInput)
                .build());

        while (true) {
            Message response = modelClient.create(createRequest());
            history.add(response.toParam());

            if (response.stopReason().filter(StopReason.TOOL_USE::equals).isEmpty()) {
                return response.content().stream()
                        .flatMap(block -> block.text().stream())
                        .map(text -> text.text())
                        .toList();
            }

            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                if (block.toolUse().isEmpty()) {
                    continue;
                }
                ToolUseBlock toolUse = block.toolUse().orElseThrow();
                BashInput input = toolUse._input().convert(BashInput.class);
                String command = input.command();

                writeLine(YELLOW + "$ " + command + RESET);
                String output = bashTool.execute(command);
                writeLine(prefixByCodePoint(output, MAX_PREVIEW_CODE_POINTS));

                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(toolUse.id())
                        .content(output)
                        .build()));
            }

            history.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(MessageParam.Content.ofBlockParams(results))
                    .build());
        }
    }

    private MessageCreateParams createRequest() {
        return MessageCreateParams.builder()
                .model(modelId)
                .system(systemPrompt)
                .messages(history)
                .addTool(BASH_DEFINITION)
                .maxTokens(MAX_TOKENS)
                .build();
    }

    private void writeLine(String value) {
        terminal.println(value);
        terminal.flush();
        if (terminal.checkError()) {
            throw new IllegalStateException("Unable to write terminal output");
        }
    }

    private static String prefixByCodePoint(String value, int maximumCodePoints) {
        if (value.codePointCount(0, value.length()) <= maximumCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }

    private static Tool createBashDefinition() {
        Tool.InputSchema.Properties properties = Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("command", JsonValue.from(Map.of("type", "string")))
                .build();
        return Tool.builder()
                .name("bash")
                .description("Run a shell command.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(properties)
                        .required(List.of("command"))
                        .build())
                .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BashInput(String command) {

        private BashInput {
            Objects.requireNonNull(command, "command");
        }
    }
}
