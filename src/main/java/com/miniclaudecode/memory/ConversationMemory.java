package com.miniclaudecode.memory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 短期记忆 - 管理当前对话的上下文
 * <p>
 * 职责：
 * 1. 维护对话历史（用户消息、助手回复、工具调用与结果）
 * 2. 当 token 超出预算时，自动压缩旧消息（滑动窗口 + 摘要）
 * 3. 提供关键词检索能力
 */
public class ConversationMemory implements Memory {
    private final LinkedHashMap<String, MemoryEntry> entries;
    private final List<MemoryEntry> compressedSummaries;
    private int maxTokens;
    private int currentTokens;

    /**
     * @param maxTokens 最大 token 预算，超出时触发压缩
     */
    public ConversationMemory(int maxTokens) {
        this.entries = new LinkedHashMap<>();
        this.maxTokens = maxTokens;
        this.currentTokens = 0;
        this.compressedSummaries = new ArrayList<>();
    }

    @Override
    public void store(MemoryEntry entry) {
        entries.put(entry.id(), entry);
        currentTokens += entry.tokenCount();

        while (currentTokens > maxTokens && entries.size() > 1) {
            evictOldest();
        }
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);
        return entries.values().stream()
                .filter(entry -> MemoryQueryTokenizer.matches(entry.content(), queryTokens))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    @Override
    public boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);
        if (removed != null) {
            currentTokens -= removed.tokenCount();
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        entries.clear();
        currentTokens = 0;
        compressedSummaries.clear();
    }

    @Override
    public int getTokenCount() {
        return currentTokens;
    }

    @Override
    public int size() {
        return entries.size();
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        this.maxTokens = maxTokens;
        while (currentTokens > maxTokens && entries.size() > 1) {
            evictOldest();
        }
    }

    private void evictOldest() {
        Iterator<Map.Entry<String, MemoryEntry>> it = entries.entrySet().iterator();
        if (it.hasNext()) {
            Map.Entry<String, MemoryEntry> oldest = it.next();
            it.remove();
            currentTokens -= oldest.getValue().tokenCount();
            compressedSummaries.add(oldest.getValue());
        }
    }

    public List<MemoryEntry> getCompressedSummaries() {
        return Collections.unmodifiableList(compressedSummaries);
    }

    /**
     * 新摘要会替换旧的压缩候选，避免重复回灌历史内容
     */
    public void injectSummary(MemoryEntry summary) {
        compressedSummaries.clear();
        entries.put(summary.id(), summary);
        currentTokens += summary.tokenCount();
    }

    public double getUsageRatio() {
        return maxTokens > 0 ? (double) currentTokens / maxTokens : 0;
    }

    public String getStatusSummary() {
        return String.format("短期记忆: %d条 / %d tokens (预算: %d, 使用率: %.0f%%, 已压缩: %d条)",
                entries.size(), currentTokens, maxTokens, getUsageRatio() * 100, compressedSummaries.size());
    }
}
