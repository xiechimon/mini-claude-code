package com.miniclaudecode.agent;

import com.miniclaudecode.image.ImageReferenceParser;
import com.miniclaudecode.llm.LlmClient;
import com.miniclaudecode.llm.LlmTraceLogger;
import com.miniclaudecode.lsp.LspDiagnosticReport;
import com.miniclaudecode.memory.ConversationHistoryCompactor;
import com.miniclaudecode.memory.MemoryManager;
import com.miniclaudecode.plan.ExecutionPlan;
import com.miniclaudecode.plan.Planner;
import com.miniclaudecode.plan.Task;
import com.miniclaudecode.prompt.ProjectMemoryLoader;
import com.miniclaudecode.prompt.PromptAssembler;
import com.miniclaudecode.prompt.PromptContext;
import com.miniclaudecode.prompt.PromptMode;
import com.miniclaudecode.render.Renderer;
import com.miniclaudecode.render.ToolCallLabels;
import com.miniclaudecode.runtime.CancellationContext;
import com.miniclaudecode.skill.SkillContextBuffer;
import com.miniclaudecode.skill.SkillIndexFormatter;
import com.miniclaudecode.skill.SkillRegistry;
import com.miniclaudecode.tool.ToolRegistry;
import com.miniclaudecode.tool.ToolRegistry.ToolExecutionResult;
import com.miniclaudecode.util.AnsiStyle;
import com.miniclaudecode.util.TerminalMarkdownRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute Agent - 先规划后执行
 */
public class PlanExecuteAgent {
    private static final Logger log = LoggerFactory.getLogger(PlanExecuteAgent.class);
    private static final int MAX_TASK_ITERATIONS = 5;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;
    private final PlanReviewHandler reviewHandler;
    private final MemoryManager memoryManager;
    private final ConversationHistoryCompactor historyCompactor;
    private final PrintStream out;
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();
    private Supplier<String> externalContextSupplier = () -> "";
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;
    private Renderer renderer;
    public PlanExecuteAgent(LlmClient llmClient) {
        this(llmClient, (goal, plan) -> PlanReviewDecision.execute());
    }
    public PlanExecuteAgent(LlmClient llmClient, PlanReviewHandler reviewHandler) {
        this(llmClient, new ToolRegistry(), null, null, reviewHandler);
    }
    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler);
    }
    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler,
                            PrintStream out) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler, out);
    }
    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(llmClient, toolRegistry, planner, memoryManager, reviewHandler, null);
    }

    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler, PrintStream out) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry != null ? toolRegistry : new ToolRegistry();
        this.out = out == null ? deferredSystemOut() : out;
        this.planner = planner != null ? planner : new Planner(llmClient, this.out);
        this.reviewHandler = reviewHandler == null ? (goal, plan) -> PlanReviewDecision.execute() : reviewHandler;
        this.memoryManager = memoryManager != null ? memoryManager : new MemoryManager(llmClient);
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        this.toolRegistry.setContextProfile(this.memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        this.memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.toolRegistry.setScopedMemorySaver(this.memoryManager::storeFact);
        this.planner.setProjectMemorySupplier(this::buildProjectMemoryContext);
    }

    private static PrintStream deferredSystemOut() {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                System.out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                System.out.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                System.out.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }

    /**
     * 注入渲染器后，工具调用展示改走 {@link Renderer#appendToolCalls}（inline 终端获得折叠块）；
     * 未注入时保持展开态直写 out，TUI 与单测路径行为不变
     */
    public void setRenderer(Renderer renderer) {
        this.renderer = renderer;
    }

    void renderToolCalls(PrintStream out, List<LlmClient.ToolCall> toolCalls) {
        if (renderer != null) {
            renderer.appendToolCalls(toolCalls);
            return;
        }
        ToolCallLabels.printExpanded(out, toolCalls);
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

    private void maybeCompactHistory(List<LlmClient.Message> messages, PrintStream out) {
        if (historyCompactor == null) return;
        int trigger = memoryManager.getContextProfile().compressionTriggerTokens();
        try {
            boolean compacted = historyCompactor.compactIfNeeded(messages, trigger);
            if (compacted && out != null) {
                out.println("📦 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
            }
        } catch (Exception e) {
            log.warn("conversationHistory compaction failed", e);
        }
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

    private String prependSkillBodies(String content) {
        if (skillContextBuffer == null || skillContextBuffer.isEmpty()) {
            return content;
        }
        String drained = skillContextBuffer.drain();
        if (drained.isEmpty()) return content;
        return drained + "\n" + content;
    }

    /**
     * 运行任务（自动判断是否需要规划）
     */
    public String run(String userInput) {
        log.info("Plan run started: inputLength={}", userInput == null ? 0 : userInput.length());
        memoryManager.addUserMessage(userInput);
        StreamState streamState = new StreamState();
        try {
            if (CancellationContext.isCancelled()) {
                return "⏹️ 已取消当前计划执行。";
            }
            PlanRunOutcome outcome = runWithPlan(userInput, streamState);
            if (outcome.persistAssistantMessage() && outcome.result() != null && !outcome.result().isBlank()) {
                memoryManager.addAssistantMessage("[计划结果] " + outcome.result());
            }
            if (streamState.hasStreamedOutput() && (outcome.result() == null || outcome.result().isBlank())) {
                return "";
            }
            return outcome.result();
        } catch (Exception e) {
            log.error("Plan run failed", e);
            String errorMessage = "❌ 执行失败: " + e.getMessage();
            memoryManager.addAssistantMessage(errorMessage);
            return errorMessage;
        }
    }

    private PlanRunOutcome runWithPlan(String goal, StreamState streamState) throws IOException {
        ExecutionPlan plan = planner.createPlan(goal);
        return reviewAndExecutePlan(plan, streamState);
    }

    private PlanRunOutcome reviewAndExecutePlan(ExecutionPlan plan, StreamState streamState) throws IOException {
        while (true) {
            PlanReviewDecision decision = reviewHandler.review(plan.getGoal(), plan);
            if (decision == null || decision.action() == PlanReviewAction.EXECUTE) {
                return PlanRunOutcome.executed(executePlan(plan, streamState));
            }

            if (decision.action() == PlanReviewAction.CANCEL) {
                return PlanRunOutcome.canceled("⏹️ 已取消本次计划执行。");
            }

            String feedback = decision.feedback() == null ? "" : decision.feedback().trim();
            if (feedback.isEmpty()) {
                return PlanRunOutcome.executed(executePlan(plan, streamState));
            }

            out.println("📝 已收到补充要求，正在重新规划...\n");
            plan = planner.createPlan(plan.getGoal() + "\n补充要求：" + feedback);
        }
    }

    private String executePlan(ExecutionPlan plan, StreamState streamState) throws IOException {
        log.info("Executing plan: goal='{}', taskCount={}", plan.getGoal(), plan.getAllTasks().size());
        out.println("🚀 开始执行计划...\n");

        plan.markStarted();
        StringBuilder finalResult = new StringBuilder();
        Map<String, Boolean> streamedTaskOutputs = new HashMap<>();

        while (true) {
            if (CancellationContext.isCancelled()) {
                return "⏹️ 已取消当前计划执行。";
            }
            List<Task> executableTasks = getExecutableTasksInOrder(plan);
            if (executableTasks.isEmpty()) {
                break;
            }

            List<TaskExecutionResult> batchResults = executeTaskBatch(plan, executableTasks, streamState);
            for (TaskExecutionResult batchResult : batchResults) {
                Task task = batchResult.task();

                if (!batchResult.failed()) {
                    task.markCompleted(batchResult.result());
                    streamedTaskOutputs.put(task.getId(), batchResult.streamedOutput());
                    log.info("Task completed: {} status={} resultChars={}",
                            task.getId(), task.getStatus(), batchResult.result() == null ? 0 : batchResult.result().length());
                    if (batchResult.streamedOutput() || batchResult.result() == null || batchResult.result().isBlank()) {
                        out.println("✅ 完成 [" + task.getId() + "]\n");
                    } else {
                        out.println("✅ 完成 [" + task.getId() + "]: "
                                + batchResult.result().substring(0, Math.min(100, batchResult.result().length())) + "\n");
                    }
                    continue;
                }

                Exception error = batchResult.error();
                task.markFailed(error.getMessage());
                log.warn("Task failed: {} error={}", task.getId(), error.getMessage());
                out.println("❌ 失败 [" + task.getId() + "]: " + error.getMessage() + "\n");

                if (plan.getProgress() < 0.5) {
                    out.println("🔄 尝试重新规划...\n");
                    ExecutionPlan replanned = planner.replan(plan, error.getMessage());
                    return reviewAndExecutePlan(replanned, streamState).result();
                }

                if (!finalResult.isEmpty()) {
                    finalResult.append("\n");
                }
                finalResult.append("任务 ").append(task.getId()).append(" 失败: ").append(error.getMessage());
            }
        }

        if (!plan.isAllCompleted() && !plan.hasFailed()) {
            plan.markFailed();
            return "⚠️ 计划未能继续推进，存在未满足依赖的任务。";
        }

        String planSummary = finalResult.isEmpty()
                ? buildFinalResult(plan, streamedTaskOutputs)
                : finalResult.toString();

        if (plan.hasFailed()) {
            plan.markFailed();
            if (planSummary.isBlank()) {
                return "⚠️ 计划部分完成，有任务失败。";
            }
            return "⚠️ 计划部分完成，有任务失败。\n" + planSummary;
        }

        plan.markCompleted();
        if (planSummary.isBlank()) {
            return "✅ 计划执行完成！";
        }
        return "✅ 计划执行完成！\n" + planSummary;
    }

    private List<Task> getExecutableTasksInOrder(ExecutionPlan plan) {
        Set<String> executableIds = plan.getExecutableTasks().stream()
                .map(Task::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return plan.getExecutionOrder().stream()
                .filter(executableIds::contains)
                .map(plan::getTask)
                .toList();
    }

    private List<TaskExecutionResult> executeTaskBatch(ExecutionPlan plan, List<Task> executableTasks,
                                                       StreamState streamState) {
        if (executableTasks.size() == 1) {
            Task task = executableTasks.get(0);
            log.info("Executing single task: {} type={}", task.getId(), task.getType());
            out.println("▶️ 执行任务 [" + task.getId() + "]: " + task.getDescription());
            task.markStarted();

            try {
                return List.of(TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task, streamState, out)));
            } catch (Exception e) {
                return List.of(TaskExecutionResult.failure(task, e));
            }
        }

        String parallelTaskIds = executableTasks.stream()
                .map(Task::getId)
                .collect(Collectors.joining(", "));
        log.info("Executing parallel batch: {}", parallelTaskIds);
        out.println("⚡ 本轮并行执行 " + executableTasks.size() + " 个任务: " + parallelTaskIds);

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(executableTasks.size(), 4), r -> {
            Thread t = new Thread(r, "mini-claude-code-plan-executor");
            t.setDaemon(true);
            return t;
        });
        try {
            Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();
            List<Future<TaskExecutionResult>> futures = new ArrayList<>();
            for (Task task : executableTasks) {
                out.println("▶️ 并行任务 [" + task.getId() + "]: " + task.getDescription());
                task.markStarted();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                buffers.put(task.getId(), baos);
                PrintStream taskOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
                futures.add(executor.submit(() -> {
                    try {
                        return TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task, streamState, taskOut));
                    } catch (Exception e) {
                        return TaskExecutionResult.failure(task, e);
                    }
                }));
            }

            List<TaskExecutionResult> results = new ArrayList<>();
            for (Future<TaskExecutionResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), e));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    Exception error = cause instanceof Exception exception
                            ? exception
                            : new RuntimeException(cause);
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), error));
                }
            }

            // 按任务顺序 flush 各缓冲区到 stdout，避免并行输出交错
            for (Task task : executableTasks) {
                ByteArrayOutputStream buf = buffers.get(task.getId());
                if (buf != null && buf.size() > 0) {
                    out.print(buf.toString(StandardCharsets.UTF_8));
                    out.flush();
                }
            }

            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 单个任务可经历多轮 tool call，直到模型给出最终结果或达到轮次上限
     */
    private TaskRunResult executeTask(String goal, ExecutionPlan plan, Task task,
                                      StreamState streamState, PrintStream out) throws IOException {
        String prompt = promptAssembler.assemble(PromptMode.PLAN, PromptContext.builder()
                .projectMemoryContext(buildProjectMemoryContext())
                .variable("taskType", task.getType())
                .variable("taskDescription", task.getDescription())
                .externalContext(buildExternalContext())
                .skillIndex(buildSkillIndex())
                .toolsEnabled(llmClient == null || llmClient.supportsTools())
                .build());

        String memoryContext = memoryManager.buildContextForQuery(
                task.getDescription(),
                memoryManager.getContextProfile().memoryContextTokens());
        String taskInput = buildTaskContext(goal, plan, task);
        if (!memoryContext.isEmpty()) {
            taskInput = taskInput + "\n\n" + memoryContext;
        }
        taskInput = prependSkillBodies(taskInput);

        List<LlmClient.Message> messages = new ArrayList<>(Arrays.asList(
                LlmClient.Message.system(prompt),
                ImageReferenceParser.userMessage(
                        taskInput,
                        Path.of(toolRegistry.getProjectPath()))
        ));

        StringBuilder allResults = new StringBuilder();
        int iteration = 0;
        TaskStreamRenderer streamRenderer = new TaskStreamRenderer(task.getId(), streamState, out);

        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int totalCachedInputTokens = 0;

        while (iteration < MAX_TASK_ITERATIONS) {
            if (CancellationContext.isCancelled()) {
                streamRenderer.finish();
                return TaskRunResult.of("⏹️ 已取消任务 [" + task.getId() + "]。", streamRenderer.hasStreamedOutput());
            }
            iteration++;

            // messages 接近窗口时先压缩早期消息
            injectPendingLspDiagnostics(messages, out);
            maybeCompactHistory(messages, out);

            LlmClient.ChatResponse response = llmClient.chat(
                    messages,
                    llmClient.supportsTools() ? toolRegistry.getToolDefinitions() : null,
                    streamRenderer
            );
            LlmTraceLogger.logReasoning(log,
                    "plan-task task=" + task.getId() + " iteration=" + iteration,
                    llmClient,
                    response.reasoningContent());
            if (CancellationContext.isCancelled()) {
                streamRenderer.finish();
                return TaskRunResult.of("⏹️ 已取消任务 [" + task.getId() + "]。", streamRenderer.hasStreamedOutput());
            }

            totalInputTokens += response.inputTokens();
            totalOutputTokens += response.outputTokens();
            totalCachedInputTokens += response.cachedInputTokens();

            log.info("Task {} iteration {} response: toolCalls={}, reasoningChars={}, contentChars={}",
                    task.getId(),
                    iteration,
                    response.toolCalls() == null ? 0 : response.toolCalls().size(),
                    response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                    response.content() == null ? 0 : response.content().length());

            if (!response.hasToolCalls()) {
                memoryManager.recordTokenUsage(totalInputTokens, totalOutputTokens, totalCachedInputTokens);
                if (!allResults.isEmpty() && (response.content() == null || response.content().isBlank())) {
                    String toolOnlyResult = allResults.toString().trim();
                    if (!toolOnlyResult.isBlank()) {
                        memoryManager.addAssistantMessage("[计划任务 " + task.getId() + "] " + toolOnlyResult);
                    }
                    streamRenderer.finish();
                    return TaskRunResult.of(toolOnlyResult, streamRenderer.hasStreamedOutput());
                }
                if (response.content() != null && !response.content().isBlank()) {
                    memoryManager.addAssistantMessage("[计划任务 " + task.getId() + "] " + response.content());
                }
                streamRenderer.finish();
                return TaskRunResult.of(response.content(), streamRenderer.hasStreamedOutput());
            }

            renderToolCalls(out, response.toolCalls());
            messages.add(LlmClient.Message.assistant(
                    response.reasoningContent(),
                    response.content(),
                    response.toolCalls()
            ));

            // 在工具执行前 flush 并重置流式渲染器：避免 Markdown renderer pending 文本
            // 被 HITL 提示"跨过"导致 🧠 / 🤖 标题与内容错位
            streamRenderer.resetBetweenIterations();

            List<ToolExecutionResult> toolResults = ToolCallRunner.execute(
                    log, "task " + task.getId(), toolRegistry, response.toolCalls(),
                    ToolResultSummaries.forStream(out));
            for (ToolExecutionResult toolResult : toolResults) {
                memoryManager.addToolResult(toolResult.name(), toolResult.result());
                allResults.append(toolResult.result()).append("\n");
                messages.add(LlmClient.Message.tool(toolResult.id(), toolResult.result()));
            }
            ToolCallRunner.appendImageMessages(messages, toolResults);
        }

        String fallbackResult = allResults.toString().trim();
        if (!fallbackResult.isBlank()) {
            memoryManager.addAssistantMessage("[计划任务 " + task.getId() + "] " + fallbackResult);
        }
        streamRenderer.finish();
        return TaskRunResult.of(fallbackResult, streamRenderer.hasStreamedOutput());
    }

    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context for plan task", e);
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

    private void injectPendingLspDiagnostics(List<LlmClient.Message> messages, PrintStream out) {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        messages.add(LlmClient.Message.user(report.promptText()));
        out.println(report.displayText());
        log.info("Injected LSP diagnostics into plan task conversation");
    }

    private String buildTaskContext(String goal, ExecutionPlan plan, Task task) {
        StringBuilder context = new StringBuilder();
        context.append("总目标：").append(goal).append("\n");
        context.append("当前任务：").append(task.getDescription()).append("\n");

        if (task.getDependencies().isEmpty()) {
            context.append("依赖任务：无\n");
        } else {
            context.append("依赖任务结果：\n");
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTask(depId);
                if (dep == null) {
                    continue;
                }
                context.append("- ").append(dep.getId())
                        .append(" / ").append(dep.getDescription())
                        .append(" / 状态=").append(dep.getStatus())
                        .append("\n");
                if (dep.getResult() != null && !dep.getResult().isBlank()) {
                    context.append(dep.getResult()).append("\n");
                }
            }
        }

        context.append("请执行此任务。如果是ANALYSIS或VERIFICATION类型，请基于以上上下文直接给出结果。");
        return context.toString();
    }

    private String buildFinalResult(ExecutionPlan plan, Map<String, Boolean> streamedTaskOutputs) {
        StringBuilder result = new StringBuilder();
        List<Task> leafTasks = plan.getAllTasks().stream()
                .filter(task -> task.getDependents().isEmpty())
                .toList();

        for (Task task : leafTasks) {
            if (Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId()))) {
                continue;
            }
            if (task.getResult() == null || task.getResult().isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append("\n");
            }
            result.append("[").append(task.getId()).append("] ").append(task.getResult());
        }

        if (!result.isEmpty()) {
            return result.toString();
        }

        return plan.getAllTasks().stream()
                .filter(task -> !Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId())))
                .filter(task -> task.getResult() != null && !task.getResult().isBlank())
                .reduce((first, second) -> second)
                .map(Task::getResult)
                .orElse("");
    }

    public enum PlanReviewAction {
        EXECUTE,
        SUPPLEMENT,
        CANCEL
    }

    public interface PlanReviewHandler {
        PlanReviewDecision review(String goal, ExecutionPlan plan);
    }

    private record PlanRunOutcome(String result, boolean persistAssistantMessage) {
        static PlanRunOutcome executed(String result) {
            return new PlanRunOutcome(result, true);
        }

        static PlanRunOutcome canceled(String result) {
            return new PlanRunOutcome(result, false);
        }

        static PlanRunOutcome failed(String result) {
            return new PlanRunOutcome(result, true);
        }
    }

    private record TaskRunResult(String result, boolean streamedOutput) {
        static TaskRunResult of(String result, boolean streamedOutput) {
            return new TaskRunResult(result, streamedOutput);
        }
    }

    private record TaskExecutionResult(Task task, String result, boolean streamedOutput, Exception error) {
        static TaskExecutionResult success(Task task, TaskRunResult taskRunResult) {
            return new TaskExecutionResult(task, taskRunResult.result(), taskRunResult.streamedOutput(), null);
        }

        static TaskExecutionResult failure(Task task, Exception error) {
            return new TaskExecutionResult(task, null, false, error);
        }

        boolean failed() {
            return error != null;
        }
    }

    public record PlanReviewDecision(PlanReviewAction action, String feedback) {
        public static PlanReviewDecision execute() {
            return new PlanReviewDecision(PlanReviewAction.EXECUTE, null);
        }

        public static PlanReviewDecision supplement(String feedback) {
            return new PlanReviewDecision(PlanReviewAction.SUPPLEMENT, feedback);
        }

        public static PlanReviewDecision cancel() {
            return new PlanReviewDecision(PlanReviewAction.CANCEL, null);
        }
    }

    private static final class StreamState {
        private volatile boolean streamedOutput;

        private void markStreamed() {
            this.streamedOutput = true;
        }

        private boolean hasStreamedOutput() {
            return streamedOutput;
        }
    }

    private static final class TaskStreamRenderer implements LlmClient.StreamListener {
        private final String taskId;
        private final StreamState streamState;
        private final PrintStream out;
        private final StringBuilder pendingReasoning = new StringBuilder();
        private final StringBuilder lateReasoning = new StringBuilder();
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean streamedOutput;

        private TaskStreamRenderer(String taskId, StreamState streamState, PrintStream out) {
            this.taskId = taskId;
            this.streamState = streamState;
            this.out = out;
        }

        @Override
        public synchronized void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (contentStarted) {
                lateReasoning.append(delta);
                return;
            }
            if (!reasoningStarted) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;
                }
                out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
                reasoningRenderer = new TerminalMarkdownRenderer(out);
                reasoningRenderer.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
                streamState.markStreamed();
            } else {
                reasoningRenderer.append(delta);
            }
            out.flush();
        }

        @Override
        public synchronized void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (reasoningStarted && reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    out.println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
                    TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                    r.append(pendingReasoning.toString());
                    r.finish();
                    out.println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }
                // content 可能只是 tool-call 前的叙述，也可能是最终回答，用"输出"避免误导
                out.println(AnsiStyle.section("🤖 任务输出 [" + taskId + "]"));
                contentRenderer = new TerminalMarkdownRenderer(out);
                contentStarted = true;
                streamedOutput = true;
                streamState.markStreamed();
            }
            contentRenderer.append(delta);
            out.flush();
        }

        private synchronized void finish() {
            if (streamedOutput) {
                if (reasoningRenderer != null) {
                    reasoningRenderer.finish();
                }
                if (contentRenderer != null) {
                    contentRenderer.finish();
                }
                flushLateReasoning();
                out.println("\n");
            }
        }

        /**
         * 两次 iteration 之间（通常是一次 tool-call 分支完成后）调用：收尾当前渲染器并重置状态，
         * 让下一轮迭代能重新打印 🧠 / 🤖 标题，避免标题和内容被 HITL / 工具执行中断而错位
         */
        private synchronized void resetBetweenIterations() {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            flushLateReasoning();
            pendingReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            if (streamedOutput) {
                out.println();
            }
        }

        private synchronized boolean hasStreamedOutput() {
            return streamedOutput;
        }

        private void flushLateReasoning() {
            String late = lateReasoning.toString().trim();
            if (late.isEmpty()) {
                lateReasoning.setLength(0);
                return;
            }
            out.println();
            out.println(AnsiStyle.heading("🧠 补充思考 [" + taskId + "]"));
            TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(out);
            renderer.append(late);
            renderer.finish();
            lateReasoning.setLength(0);
        }
    }

}
