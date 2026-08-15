package com.miniclaudecode.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryInitializerTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesConciseMiniClaudeCodeProjectMemory() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# Mini Claude Code\n\nJava Agent CLI");
        Files.writeString(tempDir.resolve("AGENTS.md"), "项目名：Mini Claude Code\n改命令入口要联动");
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");
        Files.writeString(tempDir.resolve("mvnw"), "");

        ProjectMemoryInitializer.InitResult result = ProjectMemoryInitializer.initialize(tempDir, false);

        String content = Files.readString(tempDir.resolve("MCC.md"));
        assertTrue(result.written());
        assertTrue(content.contains("# MCC.md"));
        assertTrue(content.contains("Mini Claude Code 是一个用于学习 Agent 架构的 Java CLI"));
        assertTrue(content.contains("./mvnw test -Pquick"));
        assertTrue(content.contains("不要为某个模式创建孤立能力"));
        assertTrue(content.lines().count() < 45, content);
    }

    @Test
    void doesNotOverwriteExistingFileWithoutForce() throws Exception {
        Files.writeString(tempDir.resolve("MCC.md"), "existing");

        ProjectMemoryInitializer.InitResult result = ProjectMemoryInitializer.initialize(tempDir, false);

        assertFalse(result.written());
        assertTrue(Files.readString(tempDir.resolve("MCC.md")).equals("existing"));
    }

    @Test
    void forceOverwritesExistingFile() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# Mini Claude Code\n");
        Files.writeString(tempDir.resolve("MCC.md"), "existing");

        ProjectMemoryInitializer.InitResult result = ProjectMemoryInitializer.initialize(tempDir, true);

        assertTrue(result.written());
        assertTrue(Files.readString(tempDir.resolve("MCC.md")).contains("# MCC.md"));
    }

    @Test
    void migratesLegacyFileWithoutDeletingIt() throws Exception {
        Files.writeString(tempDir.resolve("PAI.md"), "legacy project memory");

        ProjectMemoryInitializer.InitResult result = ProjectMemoryInitializer.initialize(tempDir, false);

        assertTrue(result.written());
        assertTrue(result.message().contains("迁移"));
        assertTrue(Files.readString(tempDir.resolve("MCC.md")).equals("legacy project memory"));
        assertTrue(Files.readString(tempDir.resolve("PAI.md")).equals("legacy project memory"));
    }
}
