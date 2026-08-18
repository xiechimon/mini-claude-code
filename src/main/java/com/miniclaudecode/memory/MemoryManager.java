package com.miniclaudecode.memory;

import com.miniclaudecode.context.ContextProfile;
import com.miniclaudecode.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Memory 管理器 - Memory 系统的门面类
 * <p>
 * 统一管理短期记忆、长期记忆、上下文压缩和检索，
 * 为 Agent 提供简洁的记忆存取接口
 */
public class MemoryManager {
    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);
    // 完整工具结果已保存在消息历史，短期记忆只保留摘要
    private static final int MAX_TOOL_RESULT_CHARS = 500;
    private final ConversationMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final ContextCompressor compressor;
    private final MemoryRetriever retriever;
    private TokenBudget tokenBudget;
    private ContextProfile contextProfile;
    private String currentProject;

    public MemoryManager(LlmClient llmClient) {
        this(llmClient, ContextProfile.from(llmClient), null);
    }

    /**
     * @param llmClient       LLM 客户端（用于压缩时的摘要生成）
     * @param shortTermBudget 短期记忆 token 预算
     * @param contextWindow   模型上下文窗口大小
     */
    public MemoryManager(LlmClient llmClient, int shortTermBudget, int contextWindow) {
        this(llmClient, shortTermBudget, contextWindow, null);
    }

    public MemoryManager(LlmClient llmClient, int shortTermBudget, int contextWindow, LongTermMemory longTermMemory) {
        this(llmClient, ContextProfile.custom(contextWindow, shortTermBudget), longTermMemory);
    }

    private MemoryManager(LlmClient llmClient, ContextProfile contextProfile, LongTermMemory longTermMemory) {
        this.contextProfile = contextProfile;
        this.shortTermMemory = new ConversationMemory(contextProfile.shortTermMemoryBudget());
        this.longTermMemory = longTermMemory != null ? longTermMemory : new LongTermMemory();
        this.compressor = new ContextCompressor(llmClient);
        this.retriever = new MemoryRetriever(shortTermMemory, this.longTermMemory);
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
        this.currentProject = defaultProjectKey();
    }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "project";
        }
        String normalized = scope.trim().toLowerCase();
        return "global".equals(normalized) ? "global" : "project";
    }

    private static String defaultProjectKey() {
        return normalizeProjectKey(System.getProperty("user.dir"));
    }

    private static String normalizeProjectKey(String path) {
        try {
            Path candidate = Path.of(path).toAbsolutePath().normalize();
            if (java.nio.file.Files.exists(candidate)) {
                return candidate.toRealPath().toString();
            }
            return candidate.toString();
        } catch (Exception e) {
            return Path.of(path).toAbsolutePath().normalize().toString();
        }
    }

    public void setLlmClient(LlmClient llmClient) {
        this.compressor.setLlmClient(llmClient);
        applyContextProfile(ContextProfile.from(llmClient));
    }

    public void applyContextProfile(ContextProfile contextProfile) {
        this.contextProfile = contextProfile;
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
        this.shortTermMemory.setMaxTokens(contextProfile.shortTermMemoryBudget());
    }

    public void setProjectPath(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return;
        }
        this.currentProject = normalizeProjectKey(projectPath);
    }

    public void addUserMessage(String content) {
        MemoryEntry entry = new MemoryEntry(
                "user-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryEntry.MemoryType.CONVERSATION,
                Map.of("source", "user"),
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);
        compressIfNeeded();
    }

    public void addAssistantMessage(String content) {
        MemoryEntry entry = new MemoryEntry(
                "assistant-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryEntry.MemoryType.CONVERSATION,
                Map.of("source", "assistant"),
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);
        compressIfNeeded();
    }

    /**
     * 过长工具结果会截断，避免单次调用耗尽短期记忆预算
     */
    public void addToolResult(String toolName, String result) {
        String truncated = result.length() > MAX_TOOL_RESULT_CHARS
                ? result.substring(0, MAX_TOOL_RESULT_CHARS) + "...(已截断)"
                : result;
        String content = "[" + toolName + "] " + truncated;
        MemoryEntry entry = new MemoryEntry(
                "tool-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryEntry.MemoryType.TOOL_RESULT,
                Map.of("source", "tool", "toolName", toolName),
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);
        compressIfNeeded();
    }

    public void storeFact(String fact) {
        storeFact(fact, "project");
    }

    public void storeFact(String fact, String scope) {
        String normalizedScope = normalizeScope(scope);
        Map<String, String> metadata = "global".equals(normalizedScope)
                ? Map.of("source", "fact", "scope", "global")
                : Map.of("source", "fact", "scope", "project", "project", currentProject);
        MemoryEntry entry = new MemoryEntry(
                "fact-" + UUID.randomUUID().toString().substring(0, 8),
                fact,
                MemoryEntry.MemoryType.FACT,
                metadata,
                MemoryEntry.estimateTokens(fact)
        );
        longTermMemory.store(entry);
    }

    public List<MemoryEntry> retrieveRelevant(String query, int limit) {
        return retriever.retrieve(query, limit);
    }

    public List<MemoryEntry> listLongTerm() {
        return longTermMemory.getAll();
    }

    public List<MemoryEntry> searchLongTerm(String query, int limit) {
        return longTermMemory.search(query, limit, currentProject);
    }

    public boolean deleteLongTerm(String id) {
        return longTermMemory.delete(id);
    }

    public String buildContextForQuery(String query, int maxTokens) {
        return retriever.buildContextForQuery(query, maxTokens, currentProject);
    }

    public void recordTokenUsage(int inputTokens, int outputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens);
    }

    public void recordTokenUsage(int inputTokens, int outputTokens, int cachedInputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens, cachedInputTokens);
    }

    /**
     * 仅在当前配置的占用阈值达到后压缩短期记忆
     *
     * @return 本次是否产生了摘要
     */
    public boolean compressIfNeeded() {
        if (!tokenBudget.needsCompression(shortTermMemory, contextProfile.compressionTriggerRatio())) {
            return false;
        }
        int beforeTokens = shortTermMemory.getTokenCount();
        log.info("上下文占用达到压缩阈值（{}%），触发短期记忆压缩",
                (int) (contextProfile.compressionTriggerRatio() * 100));
        String summary = compressor.compress(shortTermMemory);
        if (summary != null) {
            int afterTokens = shortTermMemory.getTokenCount();
            String preview = summary.substring(0, Math.min(100, summary.length()));
            log.info("短期记忆压缩完成: {} -> {} tokens, summaryPreview={}", beforeTokens, afterTokens, preview);
        }
        return summary != null;
    }

    /**
     * 只重置当前会话的短期记忆，长期记忆保持不变
     */
    public void clearShortTerm() {
        shortTermMemory.clear();
    }

    public void clearLongTerm() {
        longTermMemory.clear();
    }

    public String getSystemStatus() {
        return "上下文策略: " + contextProfile.summary() + "\n" +
                shortTermMemory.getStatusSummary() + "\n" +
                longTermMemory.getStatusSummary() + "\n" +
                tokenBudget.getUsageReport();
    }

    public ConversationMemory getShortTermMemory() {
        return shortTermMemory;
    }

    public LongTermMemory getLongTermMemory() {
        return longTermMemory;
    }

    public TokenBudget getTokenBudget() {
        return tokenBudget;
    }

    public ContextProfile getContextProfile() {
        return contextProfile;
    }

    public String getCurrentProject() {
        return currentProject;
    }
}
