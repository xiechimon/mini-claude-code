package com.miniclaudecode.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaudecode.context.ContextProfile;
import com.miniclaudecode.context.TokenUsageFormatter;
import com.miniclaudecode.image.ImageReferenceParser;
import com.miniclaudecode.llm.LlmClient;
import com.miniclaudecode.llm.LlmTraceLogger;
import com.miniclaudecode.lsp.LspDiagnosticReport;
import com.miniclaudecode.memory.ConversationHistoryCompactor;
import com.miniclaudecode.memory.ExplicitMemoryHints;
import com.miniclaudecode.memory.MemoryManager;
import com.miniclaudecode.prompt.ProjectMemoryLoader;
import com.miniclaudecode.prompt.PromptAssembler;
import com.miniclaudecode.prompt.PromptContext;
import com.miniclaudecode.prompt.PromptMode;
import com.miniclaudecode.render.PlainRenderer;
import com.miniclaudecode.render.Renderer;
import com.miniclaudecode.render.StatusInfo;
import com.miniclaudecode.runtime.CancellationContext;
import com.miniclaudecode.skill.SkillContextBuffer;
import com.miniclaudecode.skill.SkillIndexFormatter;
import com.miniclaudecode.skill.SkillRegistry;
import com.miniclaudecode.tool.ToolRegistry;
import com.miniclaudecode.tool.ToolRegistry.ToolExecutionResult;
import com.miniclaudecode.util.AnsiStyle;
import com.miniclaudecode.util.TerminalMarkdownRenderer;
import com.miniclaudecode.util.TextPreview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/**
 * Agent 核心类 - 实现 ReAct 循环
 */
public class Agent {
    private static final Logger log = LoggerFactory.getLogger(Agent.class);
    private final ToolRegistry toolRegistry;
    private final List<LlmClient.Message> conversationHistory;
    private final MemoryManager memoryManager;
    private final ConversationHistoryCompactor historyCompactor;
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();
    private LlmClient llmClient;
    private Supplier<String> externalContextSupplier = () -> "";
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;
    private Renderer renderer;
    private Supplier<Boolean> hitlEnabledSupplier = () -> false;
    private boolean returnFinalResponseWhenStreamed;

    public Agent(LlmClient llmClient) {
        this(llmClient, new ToolRegistry());
    }

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.conversationHistory = new ArrayList<>();
        this.memoryManager = new MemoryManager(llmClient);
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        this.memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.toolRegistry.setScopedMemorySaver(memoryManager::storeFact);
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt("")));
    }

    private static String formatLine(String label, int tokens, int window, int count) {
        double pct = window > 0 ? (double) tokens / window * 100 : 0;
        String countLabel = count >= 0 ? String.format("  [%d 条]", count) : "";
        return String.format("    %-18s %8s  (%4.1f%%)%s%n",
                label + ":", formatTokens(tokens), pct, countLabel);
    }

    private static String progressBar(double ratio, int width) {
        ratio = Math.max(0, Math.min(1, ratio));
        int filled = (int) Math.round(ratio * width);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? '█' : '░');
        }
        bar.append("]");
        return bar.toString();
    }

    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000) return String.format("%.1fk", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.memoryManager.setLlmClient(llmClient);
        this.historyCompactor.setLlmClient(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public void setSkillContextBuffer(SkillContextBuffer skillContextBuffer) {
        this.skillContextBuffer = skillContextBuffer;
    }

    public void setRenderer(Renderer renderer) {
        this.renderer = renderer;
    }

    public void setReturnFinalResponseWhenStreamed(boolean returnFinalResponseWhenStreamed) {
        this.returnFinalResponseWhenStreamed = returnFinalResponseWhenStreamed;
    }

    /**
     * 注入 HITL 启用状态的快照源，用于状态栏 / StatusInfo 显示
     * Main 启动后用 {@code reactAgent.setHitlEnabledSupplier(hitlHandler::isEnabled)} 接进来
     */
    public void setHitlEnabledSupplier(Supplier<Boolean> supplier) {
        this.hitlEnabledSupplier = supplier == null ? () -> false : supplier;
    }

    /**
     * 获取渲染器；首次调用时如果未设置，懒加载一个 {@link PlainRenderer} 兜底，
     * 保证旧调用方（构造 Agent 后没有 setRenderer 的代码、单测等）行为不变
     */
    private Renderer renderer() {
        if (renderer == null) {
            renderer = new PlainRenderer();
        }
        return renderer;
    }

    public String run(String userInput) {
        log.info("ReAct run started: inputLength={}", userInput == null ? 0 : userInput.length());
        pruneHistoricalImagePayloads();
        memoryManager.addUserMessage(userInput);
        storeExplicitBrowserMemoryHint(userInput);

        ContextProfile contextProfile = memoryManager.getContextProfile();
        String memoryContext = memoryManager.buildContextForQuery(userInput, contextProfile.memoryContextTokens());
        updateSystemPromptWithMemory(memoryContext);

        String userMessageContent = prependSkillBodies(userInput);
        conversationHistory.add(ImageReferenceParser.userMessage(
                userMessageContent,
                Path.of(toolRegistry.getProjectPath())));
        StringBuilder reasoningTranscript = new StringBuilder();
        StreamRenderer streamRenderer = new StreamRenderer(renderer());

        long startNanos = System.nanoTime();
        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);
        pushStatus(budget, startNanos, "running");

        // 主退出条件 = LLM 自己决定（不再调用工具就返回）；
        // budget 仅在 token 用尽 / 检测到死循环 / 超出硬轮数时兜底
        while (true) {
            if (CancellationContext.isCancelled()) {
                log.info("ReAct run cancelled before iteration");
                pushStatus(budget, startNanos, "idle");
                return "⏹️ 已取消当前任务。";
            }
            // conversationHistory 决定下一轮输入，必须独立于 shortTermMemory 接近窗口时压缩
            injectPendingLspDiagnostics();
            maybeCompactHistory();
            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                String description = budget.describeExit(exitReason);
                log.warn("ReAct run exhausted budget: reason={}, iteration={}, tokens={}/{}",
                        exitReason, budget.iteration(),
                        budget.totalInputTokens() + budget.totalOutputTokens(), budget.tokenBudget());
                pushStatus(budget, startNanos, "idle");
                return "❌ " + description;
            }

            int iteration = budget.beginIteration();

            try {
                List<LlmClient.Tool> toolDefinitions = llmClient.supportsTools()
                        ? toolRegistry.getToolDefinitions()
                        : null;
                logRequestContext("react iteration=" + iteration, toolDefinitions);
                streamRenderer.beginThinking();
                LlmClient.ChatResponse response = llmClient.chat(
                        conversationHistory,
                        toolDefinitions,
                        streamRenderer
                );
                LlmTraceLogger.logReasoning(log, "react iteration=" + iteration, llmClient, response.reasoningContent());
                if (CancellationContext.isCancelled()) {
                    log.info("ReAct run cancelled after LLM response");
                    streamRenderer.finish();
                    pushStatus(budget, startNanos, "idle");
                    return "⏹️ 已取消当前任务。";
                }

                budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());

                if (response.hasToolCalls()) {
                    appendReasoning(reasoningTranscript, response.reasoningContent());
                    log.info("LLM requested {} tool call(s) in iteration {}", response.toolCalls().size(), iteration);
                    budget.recordToolCalls(response.toolCalls());
                    conversationHistory.add(LlmClient.Message.assistant(
                            response.reasoningContent(),
                            response.content(),
                            response.toolCalls()
                    ));

                    // 在工具执行前就 flush 本轮流式渲染器，避免 TerminalMarkdownRenderer
                    // 内部 pending 缓冲区（仅按换行 flush）里的文本被 HITL 提示"跨过"
                    // 造成标题和内容错位；重置后下一轮迭代的 reasoning/content 会重新打印标题
                    streamRenderer.resetBetweenIterations();
                    renderer().appendToolCalls(response.toolCalls());

                    List<ToolExecutionResult> toolResults = ToolCallRunner.execute(
                            log, "iteration=" + iteration, toolRegistry, response.toolCalls(),
                            ToolResultSummaries.forStream(renderer().stream()));
                    for (ToolExecutionResult toolResult : toolResults) {
                        memoryManager.addToolResult(toolResult.name(), toolResult.result());
                        conversationHistory.add(LlmClient.Message.tool(toolResult.id(), toolResult.result()));
                    }
                    ToolCallRunner.appendImageMessages(conversationHistory, toolResults);
                    pushStatus(budget, startNanos, "running");

                    continue;
                }

                appendReasoning(reasoningTranscript, response.reasoningContent());
                conversationHistory.add(LlmClient.Message.assistant(response.content()));

                memoryManager.addAssistantMessage(response.content());

                memoryManager.recordTokenUsage(budget.totalInputTokens(), budget.totalOutputTokens(), budget.totalCachedInputTokens());
                pushStatus(budget, startNanos, "idle");
                log.info("ReAct run finished: inputTokens={}, outputTokens={}, reasoningChars={}, answerChars={}",
                        budget.totalInputTokens(),
                        budget.totalOutputTokens(),
                        response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                        response.content() == null ? 0 : response.content().length());
                if (log.isDebugEnabled()) {
                    log.debug("Assistant answer preview: {}", TextPreview.of(response.content(), 500));
                }

                if (streamRenderer.hasStreamedOutput()) {
                    streamRenderer.finish();
                    return returnFinalResponseWhenStreamed ? (response.content() == null ? "" : response.content().trim()) : "";
                }
                streamRenderer.clearThinkingPanel();
                return formatUserFacingResponse(reasoningTranscript.toString(), response.content());

            } catch (IOException e) {
                log.error("LLM call failed in ReAct loop", e);
                streamRenderer.finish();
                return "❌ 调用 LLM 失败: " + e.getMessage();
            }
        }
    }

    /**
     * 清空对话历史并重建基础系统提示，不影响长期记忆条目
     */
    public void clearHistory() {
        conversationHistory.clear();
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt("")));

        memoryManager.clearShortTerm();
        if (skillContextBuffer != null) {
            skillContextBuffer.clear();
        }
    }

    /**
     * 手动压缩当前 ReAct 对话历史，不等待上下文窗口阈值触发
     */
    public CompactionResult compactHistoryNow() {
        long beforeTokens = estimateCurrentContextTokens();
        try {
            boolean compacted = historyCompactor.compactNow(conversationHistory);
            return new CompactionResult(compacted, beforeTokens, estimateCurrentContextTokens(), null);
        } catch (Exception e) {
            log.warn("manual conversationHistory compaction failed", e);
            return new CompactionResult(false, beforeTokens, estimateCurrentContextTokens(), e.getMessage());
        }
    }

    /**
     * 当前状态栏快照：ctx 表示下一轮请求仍会携带的上下文估算，不含累计 in/out 用量
     */
    public StatusInfo currentStatus(String phase) {
        String normalizedPhase = phase == null || phase.isBlank() ? "idle" : phase;
        String model = llmClient == null ? "—" : llmClient.getModelName();
        long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
        boolean hitl = Boolean.TRUE.equals(hitlEnabledSupplier.get());
        long contextTokens = estimateCurrentContextTokens();
        if ("idle".equals(normalizedPhase)) {
            return StatusInfo.idle(model, contextWindow, contextTokens, hitl);
        }
        return StatusInfo.active(model, contextWindow, contextTokens, hitl, normalizedPhase);
    }

    private void updateSystemPromptWithMemory(String memoryContext) {
        conversationHistory.set(0, LlmClient.Message.system(buildSystemPrompt(memoryContext)));
    }

    private String buildSystemPrompt(String memoryContext) {
        return promptAssembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .projectMemoryContext(buildProjectMemoryContext())
                .memoryContext(memoryContext)
                .externalContext(buildExternalContext())
                .skillIndex(buildSkillIndex())
                .toolsEnabled(llmClient == null || llmClient.supportsTools())
                .build());
    }

    private void maybeCompactHistory() {
        if (historyCompactor == null) return;
        int trigger = memoryManager.getContextProfile().compressionTriggerTokens();
        try {
            boolean compacted = historyCompactor.compactIfNeeded(conversationHistory, trigger);
            if (compacted) {
                renderer().stream().println("📦 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
            }
        } catch (Exception e) {
            log.warn("conversationHistory compaction failed", e);
        }
    }

    private void pruneHistoricalImagePayloads() {
        int messageCount = 0;
        int imageCount = 0;
        for (int i = 0; i < conversationHistory.size(); i++) {
            LlmClient.Message message = conversationHistory.get(i);
            int images = message.imagePartCount();
            if (images <= 0) {
                continue;
            }
            conversationHistory.set(i, message.withoutImageContent());
            messageCount++;
            imageCount += images;
        }
        if (imageCount > 0) {
            log.info("Pruned historical image payloads before new ReAct turn: messages={}, images={}",
                    messageCount, imageCount);
        }
    }

    private void injectPendingLspDiagnostics() {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        conversationHistory.add(LlmClient.Message.user(report.promptText()));
        renderer().stream().println(report.displayText());
        log.info("Injected LSP diagnostics into ReAct conversation");
    }

    private String buildSkillIndex() {
        if (skillRegistry == null) return "";
        try {
            return SkillIndexFormatter.format(skillRegistry.enabledSkills());
        } catch (Exception e) {
            log.warn("Failed to build skill index", e);
            return "";
        }
    }

    private String prependSkillBodies(String userInput) {
        if (skillContextBuffer == null || skillContextBuffer.isEmpty()) {
            return userInput;
        }
        String drained = skillContextBuffer.drain();
        if (drained.isEmpty()) return userInput;
        return drained + "\n用户输入：\n" + userInput;
    }

    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context", e);
            return "";
        }
    }

    private String buildProjectMemoryContext() {
        try {
            return ProjectMemoryLoader.createDefault(Path.of(toolRegistry.getProjectPath())).loadForPrompt();
        } catch (Exception e) {
            log.warn("Failed to load MCC.md project memory", e);
            return "";
        }
    }

    public List<LlmClient.Message> getConversationHistory() {
        return new ArrayList<>(conversationHistory);
    }

    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    private void storeExplicitBrowserMemoryHint(String userInput) {
        List<String> recentTexts = conversationHistory.stream()
                .map(LlmClient.Message::content)
                .filter(content -> content != null && !content.isBlank())
                .toList();
        String fact = ExplicitMemoryHints.browserLoginFact(userInput, recentTexts);
        if (fact != null && !fact.isBlank()) {
            memoryManager.storeFact(fact, "global");
        }
    }

    public String getContextStatus() {
        com.miniclaudecode.context.ContextProfile profile = memoryManager.getContextProfile();
        int window = profile.maxContextWindow();
        int triggerTokens = profile.compressionTriggerTokens();

        int systemTokens = 0, userTokens = 0, assistantTokens = 0, toolTokens = 0;
        int systemCount = 0, userCount = 0, assistantCount = 0, toolCount = 0;
        for (LlmClient.Message msg : conversationHistory) {
            int t = com.miniclaudecode.memory.TokenBudget.estimateMessagesTokens(java.util.List.of(msg));
            switch (msg.role()) {
                case "system" -> {
                    systemTokens += t;
                    systemCount++;
                }
                case "user" -> {
                    userTokens += t;
                    userCount++;
                }
                case "assistant" -> {
                    assistantTokens += t;
                    assistantCount++;
                }
                case "tool" -> {
                    toolTokens += t;
                    toolCount++;
                }
            }
        }
        int messagesTokens = userTokens + assistantTokens + toolTokens;
        int toolsSchemaTokens = estimateToolsSchemaTokens();
        int total = systemTokens + messagesTokens + toolsSchemaTokens;
        double ratio = window > 0 ? (double) total / window : 0;
        int triggerRemaining = Math.max(0, triggerTokens - total);

        String sb = String.format("📊 Context Usage   %s   window: %s%n",
                modelLabel(), formatTokens(window)) +
                "\n  " + progressBar(ratio, 30) +
                String.format("  %d%%  (%s / %s)%n",
                        (int) Math.round(ratio * 100), formatTokens(total), formatTokens(window)) +
                "\n  当前占用细分:\n" +
                formatLine("System prompt", systemTokens, window, systemCount) +
                formatLine("Tools schema", toolsSchemaTokens, window, -1) +
                formatLine("Conversation", messagesTokens, window,
                        userCount + assistantCount + toolCount) +
                "    ─────────────────────────────────\n" +
                String.format("    合计:              %8s  (%4.1f%%)%n",
                        formatTokens(total), ratio * 100) +
                String.format("%n  压缩阈值: %s (%d%%)   距压缩还有: %s%n",
                        formatTokens(triggerTokens),
                        (int) (profile.compressionTriggerRatio() * 100),
                        formatTokens(triggerRemaining)) +
                "  MCP resources 自动索引: " +
                (profile.mcpResourceIndexEnabled() ? "开启" : "关闭（window 不足 32k）") +
                "\n" +
                "  prompt cache: " + profile.promptCacheMode() + "\n" +
                "\n" +
                memoryManager.getSystemStatus();
        return sb;
    }

    private String modelLabel() {
        if (llmClient == null) return "(no model)";
        return llmClient.getModelName() + " (" + llmClient.getProviderName() + ")";
    }

    private int estimateToolsSchemaTokens() {
        try {
            return com.miniclaudecode.memory.MemoryEntry.estimateTokens(
                    new ObjectMapper().writeValueAsString(toolRegistry.getToolDefinitions()));
        } catch (Exception e) {
            return 0;
        }
    }

    private long estimateCurrentContextTokens() {
        long messageTokens = com.miniclaudecode.memory.TokenBudget.estimateMessagesTokens(conversationHistory);
        return Math.max(0L, messageTokens + estimateToolsSchemaTokens());
    }

    private void logRequestContext(String scope, List<LlmClient.Tool> tools) {
        if (!log.isInfoEnabled()) {
            return;
        }
        int systemTokens = 0;
        int userTokens = 0;
        int assistantTokens = 0;
        int toolMessageTokens = 0;
        int imageParts = 0;
        int messages = 0;
        StringBuilder imageDetails = new StringBuilder();
        for (int messageIndex = 0; messageIndex < conversationHistory.size(); messageIndex++) {
            LlmClient.Message msg = conversationHistory.get(messageIndex);
            messages++;
            int tokens = com.miniclaudecode.memory.TokenBudget.estimateMessagesTokens(List.of(msg));
            imageParts += msg.imagePartCount();
            appendImageDetails(imageDetails, msg, messageIndex);
            switch (msg.role()) {
                case "system" -> systemTokens += tokens;
                case "user" -> userTokens += tokens;
                case "assistant" -> assistantTokens += tokens;
                case "tool" -> toolMessageTokens += tokens;
                default -> {
                }
            }
        }
        int toolsSchemaTokens = 0;
        int toolCount = tools == null ? 0 : tools.size();
        if (tools != null && !tools.isEmpty()) {
            try {
                toolsSchemaTokens = com.miniclaudecode.memory.MemoryEntry.estimateTokens(
                        new ObjectMapper().writeValueAsString(tools));
            } catch (Exception e) {
                log.debug("Failed to estimate tools schema tokens", e);
            }
        }
        int estimatedTotal = systemTokens + userTokens + assistantTokens + toolMessageTokens + toolsSchemaTokens;
        log.info("LLM request context [{}]: messages={}, images={}, systemTokens={}, userTokens={}, assistantTokens={}, toolMessageTokens={}, tools={}, toolsSchemaTokens={}, estimatedTotal={}",
                scope, messages, imageParts, systemTokens, userTokens, assistantTokens, toolMessageTokens,
                toolCount, toolsSchemaTokens, estimatedTotal);
        if (!imageDetails.isEmpty()) {
            log.info("LLM request images [{}]: {}", scope, imageDetails);
        }
    }

    private void appendImageDetails(StringBuilder sb, LlmClient.Message msg, int messageIndex) {
        if (msg == null || !msg.hasContentParts()) {
            return;
        }
        for (int partIndex = 0; partIndex < msg.contentParts().size(); partIndex++) {
            LlmClient.ContentPart part = msg.contentParts().get(partIndex);
            if (part == null || !part.isImage()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            String payload = "image_url".equals(part.type()) ? part.imageUrl() : part.imageBase64();
            sb.append("#").append(messageIndex)
                    .append(".").append(partIndex)
                    .append(" role=").append(msg.role())
                    .append(" type=").append(part.type())
                    .append(" mime=").append(part.mimeType() == null ? "-" : part.mimeType())
                    .append(" payloadChars=").append(payload == null ? 0 : payload.length())
                    .append(" sha256=").append(shortSha256(payload));
        }
    }

    private String shortSha256(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    /**
     * @return 已注入的渲染器；未注入时为 null，调用方自行决定回退行为
     */
    public Renderer getRenderer() {
        return renderer;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    private void pushStatus(AgentBudget budget, long startNanos, String phase) {
        try {
            String model = llmClient == null ? "—" : llmClient.getModelName();
            long totalTokens = budget == null ? 0L
                    : (long) (budget.totalInputTokens() + budget.totalOutputTokens());
            long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
            boolean hitl = Boolean.TRUE.equals(hitlEnabledSupplier.get());
            long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
            String cost = budget == null ? null : TokenUsageFormatter.estimatedCostCny(
                    llmClient,
                    budget.totalInputTokens(),
                    budget.totalOutputTokens(),
                    budget.totalCachedInputTokens());
            renderer().updateStatus(StatusInfo.tokens(
                    model,
                    contextWindow,
                    estimateCurrentContextTokens(),
                    budget == null ? 0L : budget.totalInputTokens(),
                    budget == null ? 0L : budget.totalOutputTokens(),
                    budget == null ? 0L : budget.totalCachedInputTokens(),
                    cost,
                    hitl,
                    elapsed,
                    phase == null || phase.isBlank()
                            ? (totalTokens > 0 || elapsed > 0 ? "running" : "idle")
                            : phase));
        } catch (Exception e) {
            log.debug("status push failed", e);
        }
    }

    private void appendReasoning(StringBuilder reasoningTranscript, String reasoningContent) {
        if (reasoningContent == null || reasoningContent.isBlank()) {
            return;
        }
        if (!reasoningTranscript.isEmpty()) {
            reasoningTranscript.append("\n\n");
        }
        reasoningTranscript.append(reasoningContent.trim());
    }

    private String formatUserFacingResponse(String reasoningContent, String answer) {
        String normalizedReasoning = reasoningContent == null ? "" : reasoningContent.trim();
        String normalizedAnswer = answer == null ? "" : answer.trim();

        if (!renderer().rendersReasoning() || normalizedReasoning.isEmpty()) {
            return normalizedAnswer;
        }
        if (normalizedAnswer.isEmpty()) {
            return "🧠 思考过程:\n" + normalizedReasoning;
        }
        return "🧠 思考过程:\n" + normalizedReasoning + "\n\n▪ " + normalizedAnswer;
    }

    public record CompactionResult(boolean compacted, long beforeTokens, long afterTokens, String error) {
    }

    /**
     * 流式输出渲染器，将 reasoning_content 与 content 分区展示
     * <p>
     * 服务器可能把 reasoning_content 切成多段下发，甚至在 content 开始之后追加 reasoning；
     * 终端是线性的，无法回头修改已写出的文字；渲染策略：
     * <p>
     * 1. 在 content 出现之前，只要 reasoning 有实质内容（非空白），就立刻流式打印在"🧠 思考过程"下
     * 同一次用户输入只打印一次"🧠 思考过程"标题；工具调用后的后续推理继续归在同一块下
     * 2. 仅空白的 reasoning delta 会先暂存，不触发标题——避免出现"空的思考过程"
     * 3. content 一出现就收尾 reasoning 区，用低调标记进入正文并流式输出 content
     * 4. 如果 content 启动之后又收到 reasoning（服务器把思考内容追加在答案之后），
     * 缓冲到 lateReasoning，最终在 finish() 用"🧠 补充思考"标题独立展示，不会污染回复区
     */
    private static final class StreamRenderer implements LlmClient.StreamListener {
        private final Renderer renderer;
        private final PrintStream boundOut;  // null 表示延迟读取 System.out（保持旧测试兼容）
        private final StringBuilder pendingReasoning = new StringBuilder();
        private final StringBuilder visibleReasoning = new StringBuilder();
        private final StringBuilder lateReasoning = new StringBuilder();
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningHeadingPrinted;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean thinkingQuotePrinted;
        private boolean streamedOutput;

        StreamRenderer() {
            this.renderer = null;
            this.boundOut = null;
        }

        StreamRenderer(PrintStream out) {
            this.renderer = null;
            this.boundOut = out;
        }

        StreamRenderer(Renderer renderer) {
            this.renderer = renderer;
            this.boundOut = renderer == null ? null : renderer.stream();
        }

        private PrintStream out() {
            return boundOut != null ? boundOut : System.out;
        }

        private boolean hasThinkingPanel() {
            return renderer != null && renderer.supportsThinkingPanel();
        }

        private boolean rendersReasoning() {
            return renderer == null || renderer.rendersReasoning();
        }

        private void beginThinking() {
            if (hasThinkingPanel()) {
                renderer.beginThinking("Thinking");
            }
        }

        private void clearThinkingPanel() {
            if (hasThinkingPanel()) {
                renderer.endThinking();
                pendingReasoning.setLength(0);
            }
        }

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!rendersReasoning()) {
                return;
            }
            if (contentStarted) {
                // content 已开始，无法回头；缓冲到"补充思考"
                lateReasoning.append(delta);
                return;
            }
            visibleReasoning.append(delta);
            if (hasThinkingPanel()) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;
                }
                renderer.appendThinking(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                return;
            }
            if (!reasoningStarted) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;  // 还没攒出实质内容，等
                }
                if (!containsLineBreak(pendingReasoning)) {
                    return;  // 避免先打印一个空标题，等有完整行或迭代切换时再 flush
                }
                printReasoningHeadingIfNeeded();
                reasoningRenderer = newMarkdownRenderer();
                reasoningRenderer.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
            } else {
                if (hasThinkingPanel()) {
                    renderer.appendThinking(delta);
                } else {
                    reasoningRenderer.append(delta);
                }
            }
            out().flush();
        }

        @Override
        public void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (hasThinkingPanel()) {
                    finishThinkingPanelAndPrintQuote();
                } else if (reasoningStarted && reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    out().println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    printReasoningHeadingIfNeeded();
                    TerminalMarkdownRenderer r = newMarkdownRenderer();
                    r.append(pendingReasoning.toString());
                    r.finish();
                    out().println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }
                out().print(AnsiStyle.answerMarker() + " ");
                contentRenderer = newMarkdownRenderer();
                contentStarted = true;
                streamedOutput = true;
            }
            contentRenderer.append(delta);
            if (renderer != null) {
                renderer.appendAssistantContentDelta(delta);
            }
            out().flush();
        }

        private boolean hasStreamedOutput() {
            return streamedOutput;
        }

        private void resetBetweenIterations() {
            if (hasThinkingPanel()) {
                finishThinkingPanelAndPrintQuote();
            }
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            } else if (!hasThinkingPanel()) {
                flushPendingReasoning();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            if (renderer != null) {
                renderer.finishAssistantContent();
            }
            String late = lateReasoning.toString().trim();
            if (rendersReasoning() && !late.isEmpty()) {
                out().println();
                out().println(AnsiStyle.heading("🧠 补充思考"));
                TerminalMarkdownRenderer r = newMarkdownRenderer();
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            pendingReasoning.setLength(0);
            visibleReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            thinkingQuotePrinted = false;
            if (streamedOutput) {
                out().println();
            }
        }

        private void finish() {
            if (hasThinkingPanel()) {
                finishThinkingPanelAndPrintQuote();
            }
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
            } else if (!hasThinkingPanel()) {
                flushPendingReasoning();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
            }
            if (renderer != null) {
                renderer.finishAssistantContent();
            }
            String late = lateReasoning.toString().trim();
            if (rendersReasoning() && !late.isEmpty()) {
                out().println();
                out().println(AnsiStyle.heading("🧠 补充思考"));
                TerminalMarkdownRenderer r = newMarkdownRenderer();
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            if (streamedOutput) {
                out().println();
            }
        }

        private boolean containsLineBreak(CharSequence content) {
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                if (ch == '\n' || ch == '\r') {
                    return true;
                }
            }
            return false;
        }

        private void flushPendingReasoning() {
            String pending = pendingReasoning.toString();
            if (pending.isBlank()) {
                pendingReasoning.setLength(0);
                return;
            }
            printReasoningHeadingIfNeeded();
            TerminalMarkdownRenderer renderer = newMarkdownRenderer();
            renderer.append(pending);
            renderer.finish();
            pendingReasoning.setLength(0);
            streamedOutput = true;
        }

        private TerminalMarkdownRenderer newMarkdownRenderer() {
            if (renderer != null) {
                return new TerminalMarkdownRenderer(out(), renderer::terminalColumns);
            }
            return new TerminalMarkdownRenderer(out());
        }

        private void finishThinkingPanelAndPrintQuote() {
            if (!hasThinkingPanel()) {
                return;
            }
            if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                renderer.appendThinking(pendingReasoning.toString());
            }
            renderer.endThinking();
            pendingReasoning.setLength(0);
            printThinkingQuoteIfNeeded();
        }

        private void printThinkingQuoteIfNeeded() {
            if (thinkingQuotePrinted) {
                return;
            }
            if (!rendersReasoning()) {
                return;
            }
            String reasoning = visibleReasoning.toString()
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim();
            if (reasoning.isEmpty()) {
                return;
            }
            out().println(AnsiStyle.thinking("Thinking..."));
            for (String line : reasoning.split("\\R+")) {
                String normalized = line.replaceAll("\\s+", " ").trim();
                if (!normalized.isEmpty()) {
                    out().println(AnsiStyle.subtle("│ " + normalized));
                }
            }
            out().println();
            thinkingQuotePrinted = true;
            streamedOutput = true;
        }

        private void printReasoningHeadingIfNeeded() {
            if (!reasoningHeadingPrinted) {
                if (!rendersReasoning()) {
                    return;
                }
                out().println(AnsiStyle.heading("🧠 思考过程"));
                reasoningHeadingPrinted = true;
            }
        }
    }
}
