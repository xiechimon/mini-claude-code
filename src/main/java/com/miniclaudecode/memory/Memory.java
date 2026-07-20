package com.miniclaudecode.memory;

import java.util.List;
import java.util.Optional;

/**
 * Memory 接口 - 记忆系统的统一抽象
 * <p>
 * 分为短期记忆（ShortTermMemory）和长期记忆（LongTermMemory）：
 * - 短期记忆：当前对话的上下文，包括消息历史和工具结果
 * - 长期记忆：跨对话持久化的关键信息，如用户偏好、项目事实
 */
public interface Memory {
    void store(MemoryEntry entry);

    Optional<MemoryEntry> retrieve(String id);

    List<MemoryEntry> search(String query, int limit);

    List<MemoryEntry> getAll();

    boolean delete(String id);

    void clear();

    int getTokenCount();

    int size();
}
