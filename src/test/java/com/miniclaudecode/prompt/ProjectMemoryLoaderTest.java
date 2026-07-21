package com.miniclaudecode.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsUserProjectAndLocalMemoryInOrder() throws Exception {
        Path userDir = tempDir.resolve("user");
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(userDir);
        Files.createDirectories(projectRoot.resolve(".mini-claude-code"));
        Files.writeString(userDir.resolve("MCC.md"), "- user rule");
        Files.writeString(projectRoot.resolve("MCC.md"), "- project rule");
        Files.writeString(projectRoot.resolve(".mini-claude-code").resolve("MCC.md"), "- dot project rule");
        Files.writeString(projectRoot.resolve("MCC.local.md"), "- local rule");

        String context = new ProjectMemoryLoader(userDir, projectRoot).loadForPrompt();

        assertTrue(context.contains("## Mini Claude Code Project Memory"));
        assertTrue(context.indexOf("user rule") < context.indexOf("project rule"));
        assertTrue(context.indexOf("project rule") < context.indexOf("dot project rule"));
        assertTrue(context.indexOf("dot project rule") < context.indexOf("local rule"));
    }

    @Test
    void expandsRelativeImportsInsideAllowedRootOnly() throws Exception {
        Path userDir = tempDir.resolve("user");
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(userDir);
        Files.createDirectories(projectRoot.resolve("docs"));
        Files.writeString(projectRoot.resolve("docs").resolve("rules.md"), "- imported rule");
        Files.writeString(projectRoot.resolve("MCC.md"), """
                @docs/rules.md
                @../outside.md
                - root rule
                """);
        Files.writeString(tempDir.resolve("outside.md"), "- outside rule");

        String context = new ProjectMemoryLoader(userDir, projectRoot).loadForPrompt();

        assertTrue(context.contains("- imported rule"));
        assertTrue(context.contains("- root rule"));
        assertFalse(context.contains("- outside rule"));
    }

    @Test
    void returnsEmptyContextWhenNoMemoryFilesExist() {
        String context = new ProjectMemoryLoader(tempDir.resolve("missing-user"), tempDir.resolve("missing-project"))
                .loadForPrompt();

        assertTrue(context.isEmpty());
    }

    @Test
    void fallsBackToLegacyFileButPrefersCanonicalFile() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("PAI.md"), "- legacy rule");

        ProjectMemoryLoader loader = new ProjectMemoryLoader(tempDir.resolve("missing-user"), projectRoot);
        assertTrue(loader.loadForPrompt().contains("legacy rule"));

        Files.writeString(projectRoot.resolve("MCC.md"), "- canonical rule");
        String context = loader.loadForPrompt();
        assertTrue(context.contains("canonical rule"));
        assertFalse(context.contains("legacy rule"));
    }
}
