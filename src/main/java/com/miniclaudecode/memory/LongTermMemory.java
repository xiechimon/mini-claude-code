package com.miniclaudecode.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 长期记忆 - 跨对话持久化的关键信息
 * <p>
 * 职责：
 * 1. 持久化用户偏好、项目事实、关键决策等
 * 2. 支持关键词检索
 * 3. 自动去重（基于内容相似度）
 * 4. 定期持久化到磁盘
 */
public class LongTermMemory implements Memory {
    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);
    private static final String STORAGE_DIR_PROPERTY = "mini-claude-code.memory.dir";
    private static final String STORAGE_DIR_ENV = "MINI_CLAUDE_CODE_MEMORY_DIR";
    private static final String STORAGE_FILE = "long_term_memory.json";
    private final Map<String, MemoryEntry> entries;
    private final AtomicInteger tokenCounter;
    private final ObjectMapper mapper;
    private final File storageFile;

    public LongTermMemory() {
        this(resolveStorageDir());
    }

    public LongTermMemory(File storageDir) {
        this.entries = new ConcurrentHashMap<>();
        this.tokenCounter = new AtomicInteger(0);
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);

        File dir = storageDir;
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.storageFile = new File(dir, STORAGE_FILE);

        loadFromDisk();
    }

    @Override
    public void store(MemoryEntry entry) {
        // 去重检查：如果已存在内容完全相同的条目，跳过
        boolean duplicate = entries.values().stream()
                .anyMatch(e -> e.content().equals(entry.content()));
        if (duplicate) {
            return;
        }

        entries.put(entry.id(), entry);
        tokenCounter.addAndGet(entry.tokenCount());
        saveToDisk();
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        return search(query, limit, null);
    }

    public List<MemoryEntry> search(String query, int limit, String projectKey) {
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);

        return entries.values().stream()
                .filter(entry -> isVisibleInProject(entry, projectKey))
                .filter(entry -> {
                    if (MemoryQueryTokenizer.matches(entry.content(), queryTokens)) {
                        return true;
                    }
                    return entry.metadata().values().stream()
                            .anyMatch(value -> MemoryQueryTokenizer.matches(value, queryTokens));
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    public List<MemoryEntry> getAll(String projectKey) {
        return entries.values().stream()
                .filter(entry -> isVisibleInProject(entry, projectKey))
                .collect(Collectors.toList());
    }

    @Override
    public boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);
        if (removed != null) {
            tokenCounter.addAndGet(-removed.tokenCount());
            saveToDisk();
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        entries.clear();
        tokenCounter.set(0);
        saveToDisk();
    }

    @Override
    public int getTokenCount() {
        return tokenCounter.get();
    }

    @Override
    public int size() {
        return entries.size();
    }

    /**
     * 按类型筛选记忆
     */
    public List<MemoryEntry> getByType(MemoryEntry.MemoryType type) {
        return entries.values().stream()
                .filter(entry -> entry.type() == type)
                .collect(Collectors.toList());
    }

    public static boolean isVisibleInProject(MemoryEntry entry, String projectKey) {
        String scope = scopeOf(entry);
        if ("global".equals(scope)) {
            return true;
        }
        String entryProject = entry.metadata().get("project");
        return projectKey != null && !projectKey.isBlank() && Objects.equals(entryProject, projectKey);
    }

    public static String scopeOf(MemoryEntry entry) {
        String scope = entry.metadata().get("scope");
        if ("project".equalsIgnoreCase(scope)) {
            return "project";
        }
        return "global";
    }

    private void saveToDisk() {
        try {
            List<Map<String, Object>> dataList = entries.values().stream()
                    .map(this::entryToMap)
                    .collect(Collectors.toList());
            mapper.writeValue(storageFile, dataList);
        } catch (IOException e) {
            log.warn("长期记忆持久化失败: {}", e.getMessage(), e);
        }
    }

    private static File resolveStorageDir() {
        String configuredDir = System.getProperty(STORAGE_DIR_PROPERTY);
        if (configuredDir == null || configuredDir.isBlank()) {
            configuredDir = System.getenv(STORAGE_DIR_ENV);
        }
        if (configuredDir != null && !configuredDir.isBlank()) {
            return new File(configuredDir);
        }
        return new File(new File(System.getProperty("user.home"), ".mini-claude-code"), "memory");
    }

    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (!storageFile.exists()) return;

        try {
            List<Map<String, Object>> dataList = mapper.readValue(storageFile, List.class);
            for (Map<String, Object> data : dataList) {
                MemoryEntry entry = mapToEntry(data);
                if (entry != null) {
                    entries.put(entry.id(), entry);
                    tokenCounter.addAndGet(entry.tokenCount());
                }
            }
            log.info("加载了 {} 条长期记忆", entries.size());
        } catch (IOException e) {
            log.warn("加载长期记忆失败: {}", e.getMessage(), e);
        }
    }

    private Map<String, Object> entryToMap(MemoryEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.id());
        map.put("content", entry.content());
        map.put("type", entry.type().name());
        map.put("timestamp", entry.timestamp().toString());
        map.put("metadata", entry.metadata());
        map.put("tokenCount", entry.tokenCount());
        return map;
    }

    @SuppressWarnings("unchecked")
    private MemoryEntry mapToEntry(Map<String, Object> map) {
        try {
            String id = (String) map.get("id");
            String content = (String) map.get("content");
            MemoryEntry.MemoryType type = MemoryEntry.MemoryType.valueOf((String) map.get("type"));
            Instant timestamp = null;
            Object timestampObj = map.get("timestamp");
            if (timestampObj instanceof String timestampValue && !timestampValue.isBlank()) {
                timestamp = Instant.parse(timestampValue);
            }
            Map<String, String> metadata = new HashMap<>();
            Object metaObj = map.get("metadata");
            if (metaObj instanceof Map) {
                ((Map<String, Object>) metaObj).forEach((k, v) -> metadata.put(k, String.valueOf(v)));
            }
            int tokenCount = map.get("tokenCount") instanceof Number n ? n.intValue() : MemoryEntry.estimateTokens(content);
            return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount);
        } catch (Exception e) {
            return null;
        }
    }

    public String getStatusSummary() {
        Map<MemoryEntry.MemoryType, Long> typeCounts = entries.values().stream()
                .collect(Collectors.groupingBy(MemoryEntry::type, Collectors.counting()));

        return String.format("长期记忆: %d条 / %d tokens (事实: %d, 摘要: %d, 工具结果: %d)",
                entries.size(), tokenCounter.get(),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.FACT, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.SUMMARY, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.TOOL_RESULT, 0L));
    }
}
