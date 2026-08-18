package com.miniclaudecode.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 加载随项目版本管理并在会话启动时注入 system prompt 的项目记忆
 */
public class ProjectMemoryLoader {
    private static final Logger log = LoggerFactory.getLogger(ProjectMemoryLoader.class);
    private static final int MAX_TOTAL_CHARS = 24_000;
    private static final int MAX_IMPORT_DEPTH = 3;
    private static final String MEMORY_FILE_NAME = "MCC.md";
    private static final String LOCAL_MEMORY_FILE_NAME = "MCC.local.md";
    private static final String LEGACY_MEMORY_FILE_NAME = "PAI.md";
    private static final String LEGACY_LOCAL_MEMORY_FILE_NAME = "PAI.local.md";

    private final Path userConfigDir;
    private final Path projectRoot;

    public ProjectMemoryLoader(Path userConfigDir, Path projectRoot) {
        this.userConfigDir = userConfigDir == null ? null : userConfigDir.toAbsolutePath().normalize();
        this.projectRoot = projectRoot == null
                ? Path.of(".").toAbsolutePath().normalize()
                : projectRoot.toAbsolutePath().normalize();
    }

    public static ProjectMemoryLoader createDefault(Path projectRoot) {
        return new ProjectMemoryLoader(Path.of(System.getProperty("user.home"), ".mini-claude-code"), projectRoot);
    }

    private static void addPreferredSource(List<MemorySource> sources, Path directory,
                                           String preferredName, String legacyName, Path importRoot) {
        Path preferred = directory.resolve(preferredName);
        if (Files.isRegularFile(preferred)) {
            sources.add(new MemorySource(preferred, importRoot));
            return;
        }
        Path legacy = directory.resolve(legacyName);
        if (Files.isRegularFile(legacy)) {
            log.warn("Using legacy project memory file {}; rename it to {}", legacy, preferred);
            sources.add(new MemorySource(legacy, importRoot));
        }
    }

    private static String parseImport(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (!trimmed.startsWith("@") || trimmed.length() < 2 || trimmed.contains(" ")) {
            return null;
        }
        String path = trimmed.substring(1).trim();
        if (path.startsWith("/") || path.contains("..")) {
            return null;
        }
        return path;
    }

    private static String truncateSection(StringBuilder body) {
        int keep = Math.max(0, MAX_TOTAL_CHARS - 80);
        String truncated = body.substring(0, Math.min(body.length(), keep)).stripTrailing();
        return "## Mini Claude Code Project Memory\n\n" + truncated
                + "\n\n[MCC.md 内容已按 " + MAX_TOTAL_CHARS + " 字符预算截断]";
    }

    private static String label(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    public String loadForPrompt() {
        List<MemorySource> sources = sources();
        StringBuilder body = new StringBuilder();
        Set<Path> importStack = new HashSet<>();

        for (MemorySource source : sources) {
            if (!Files.isRegularFile(source.path())) {
                continue;
            }
            String content = readWithImports(source.path(), source.importRoot(), importStack, 0).trim();
            if (content.isBlank()) {
                continue;
            }
            if (!body.isEmpty()) {
                body.append("\n\n");
            }
            body.append("### ").append(label(source.path())).append("\n\n").append(content);
            if (body.length() >= MAX_TOTAL_CHARS) {
                return truncateSection(body);
            }
        }

        if (body.isEmpty()) {
            return "";
        }
        return "## Mini Claude Code Project Memory\n\n" + body;
    }

    private List<MemorySource> sources() {
        List<MemorySource> sources = new ArrayList<>();
        if (userConfigDir != null) {
            addPreferredSource(sources, userConfigDir, MEMORY_FILE_NAME, LEGACY_MEMORY_FILE_NAME, userConfigDir);
        }
        Path projectConfigDir = projectRoot.resolve(".mini-claude-code");
        addPreferredSource(sources, projectRoot, MEMORY_FILE_NAME, LEGACY_MEMORY_FILE_NAME, projectRoot);
        addPreferredSource(sources, projectConfigDir, MEMORY_FILE_NAME, LEGACY_MEMORY_FILE_NAME, projectRoot);
        addPreferredSource(sources, projectRoot, LOCAL_MEMORY_FILE_NAME, LEGACY_LOCAL_MEMORY_FILE_NAME, projectRoot);
        addPreferredSource(sources, projectConfigDir, LOCAL_MEMORY_FILE_NAME, LEGACY_LOCAL_MEMORY_FILE_NAME, projectRoot);
        return sources;
    }

    private String readWithImports(Path file, Path importRoot, Set<Path> importStack, int depth) {
        Path normalized = file.toAbsolutePath().normalize();
        if (depth > MAX_IMPORT_DEPTH) {
            log.warn("Skipping project memory import beyond depth {}: {}", MAX_IMPORT_DEPTH, normalized);
            return "";
        }
        if (!normalized.startsWith(importRoot) || !Files.isRegularFile(normalized)) {
            log.warn("Skipping project memory import outside allowed root or missing file: {}", normalized);
            return "";
        }
        if (!importStack.add(normalized)) {
            log.warn("Skipping cyclic project memory import: {}", normalized);
            return "";
        }

        try {
            StringBuilder out = new StringBuilder();
            for (String line : Files.readAllLines(normalized, StandardCharsets.UTF_8)) {
                String importPath = parseImport(line);
                if (importPath == null) {
                    out.append(line).append("\n");
                    continue;
                }
                Path imported = normalized.getParent().resolve(importPath).normalize();
                String importedContent = readWithImports(imported, importRoot, importStack, depth + 1).trim();
                if (!importedContent.isBlank()) {
                    out.append(importedContent).append("\n");
                }
            }
            return out.toString();
        } catch (IOException e) {
            log.warn("Failed to read project memory file: {}", normalized, e);
            return "";
        } finally {
            importStack.remove(normalized);
        }
    }

    private record MemorySource(Path path, Path importRoot) {
        private MemorySource {
            path = path.toAbsolutePath().normalize();
            importRoot = importRoot.toAbsolutePath().normalize();
        }
    }
}
