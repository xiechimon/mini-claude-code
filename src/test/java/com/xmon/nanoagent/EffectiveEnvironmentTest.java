package com.xmon.nanoagent;

import io.github.cdimascio.dotenv.DotenvException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 测试环境变量加载
 */
final class EffectiveEnvironmentTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void nearestDotenvOverridesInheritedEnvironmentWithoutExpandingValues() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path workingDirectory = Files.createDirectories(project.resolve("nested/work"));
        Files.writeString(project.resolve(".env"), """
                MODEL_ID=from-dotenv
                EMPTY=
                LITERAL=${INHERITED}
                """);

        EffectiveEnvironment environment = EffectiveEnvironment.load(
                workingDirectory,
                Map.of("MODEL_ID", "inherited", "INHERITED", "host-value"));

        assertEquals("from-dotenv", environment.require("MODEL_ID"));
        assertEquals("", environment.get("EMPTY"));
        assertEquals("${INHERITED}", environment.get("LITERAL"));
        assertEquals("host-value", environment.get("INHERITED"));
    }

    @Test
    void missingDotenvLeavesInheritedEnvironmentUnchanged() {
        EffectiveEnvironment environment = EffectiveEnvironment.load(
                temporaryDirectory,
                Map.of("MODEL_ID", "inherited"));

        assertEquals(Map.of("MODEL_ID", "inherited"), environment.values());
    }

    @Test
    void malformedDotenvFailsInsteadOfFallingBack() throws IOException {
        Files.writeString(temporaryDirectory.resolve(".env"), "BROKEN LINE\n");

        assertThrows(
                DotenvException.class,
                () -> EffectiveEnvironment.load(temporaryDirectory, Map.of()));
    }

    @Test
    void requiredValueDistinguishesBlankFromMissing() {
        EffectiveEnvironment environment = EffectiveEnvironment.load(
                temporaryDirectory,
                Map.of("MODEL_ID", ""));

        assertEquals("", environment.require("MODEL_ID"));
        assertThrows(IllegalStateException.class, () -> environment.require("ANTHROPIC_API_KEY"));
    }

    @Test
    void nonEmptyCustomBaseUrlRemovesAuthTokenFromTheEffectiveEnvironment() {
        EffectiveEnvironment environment = EffectiveEnvironment.load(
                temporaryDirectory,
                Map.of(
                        "ANTHROPIC_BASE_URL", "https://provider.example/anthropic",
                        "ANTHROPIC_AUTH_TOKEN", "must-not-leak",
                        "ANTHROPIC_API_KEY", "api-key"));

        assertEquals(null, environment.get("ANTHROPIC_AUTH_TOKEN"));
        assertEquals("api-key", environment.get("ANTHROPIC_API_KEY"));
    }

    @Test
    void blankBaseUrlIsPassedThroughAndDoesNotRemoveAuthToken() {
        EffectiveEnvironment environment = EffectiveEnvironment.load(
                temporaryDirectory,
                Map.of("ANTHROPIC_BASE_URL", "", "ANTHROPIC_AUTH_TOKEN", "auth-token"));

        assertEquals("", environment.get("ANTHROPIC_BASE_URL"));
        assertEquals("auth-token", environment.get("ANTHROPIC_AUTH_TOKEN"));
    }
}
