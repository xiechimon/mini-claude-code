package com.xmon.nanoagent.hook;

import com.xmon.nanoagent.host.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试从 {@code .claude/settings.json} 读出的 hook 配置
 *
 * <p>重点在「配置错了会不会炸」：一条被静默跳过的 hook 与一条不存在的 hook 在运行时无从分辨，
 * 而 hook 的典型用途是当闸门——闸门没装上必须启动即知。
 */
final class SettingsHooksTest {

    @TempDir
    Path workingDirectory;

    @Test
    void aWorkspaceWithoutSettingsHasNoHooks() throws Exception {
        assertEquals(Map.of(), SettingsHooks.read(new Workspace(workingDirectory)));
    }

    @Test
    void settingsWithoutAHooksSectionHasNoHooks() throws Exception {
        writeSettings("{\"permissions\": {\"allow\": []}}");
        assertEquals(Map.of(), SettingsHooks.read(new Workspace(workingDirectory)));
    }

    @Test
    void theThreeLevelNestingIsParsedIntoMatchersAndHandlers() throws Exception {
        writeSettings("""
                {
                  "hooks": {
                    "PreToolUse": [
                      {
                        "matcher": "bash",
                        "timeout": 10,
                        "hooks": [{"type": "command", "command": ".claude/hooks/audit.sh"}]
                      }
                    ]
                  }
                }
                """);

        Map<HookEvent, List<HookMatcher>> hooks = SettingsHooks.read(new Workspace(workingDirectory));

        List<HookMatcher> matchers = hooks.get(HookEvent.PRE_TOOL_USE);
        assertEquals(1, matchers.size());
        HookMatcher matcher = matchers.getFirst();
        assertEquals(Optional.of(Duration.ofSeconds(10)), matcher.timeout());
        assertTrue(matcher.matches(HookEvent.PRE_TOOL_USE, "bash"));
        assertEquals(
                new HookHandler.Command(".claude/hooks/audit.sh", Duration.ofSeconds(10)),
                matcher.handlers().getFirst());
    }

    @Test
    void anOmittedTimeoutFallsBackToTheContractDefaultForThatEvent() throws Exception {
        writeSettings("""
                {
                  "hooks": {
                    "PreToolUse": [{"hooks": [{"type": "command", "command": "true"}]}],
                    "UserPromptSubmit": [{"hooks": [{"type": "command", "command": "true"}]}]
                  }
                }
                """);

        Map<HookEvent, List<HookMatcher>> hooks = SettingsHooks.read(new Workspace(workingDirectory));

        assertEquals(
                HookHandler.DEFAULT_TIMEOUT,
                command(hooks, HookEvent.PRE_TOOL_USE).timeout());
        // 契约给 UserPromptSubmit 单独下调到 30 秒。
        assertEquals(
                HookHandler.USER_PROMPT_SUBMIT_TIMEOUT,
                command(hooks, HookEvent.USER_PROMPT_SUBMIT).timeout());
    }

    @Test
    void anOmittedMatcherMatchesEveryTool() throws Exception {
        writeSettings("""
                {"hooks": {"PostToolUse": [{"hooks": [{"type": "command", "command": "true"}]}]}}
                """);

        HookMatcher matcher = SettingsHooks.read(new Workspace(workingDirectory))
                .get(HookEvent.POST_TOOL_USE)
                .getFirst();

        assertTrue(matcher.matches(HookEvent.POST_TOOL_USE, "bash"));
        assertTrue(matcher.matches(HookEvent.POST_TOOL_USE, "write_file"));
    }

    @Test
    void anUnknownEventNameIsRejected() throws Exception {
        writeSettings("""
                {"hooks": {"PreToolUsage": [{"hooks": [{"type": "command", "command": "true"}]}]}}
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> SettingsHooks.read(new Workspace(workingDirectory)));
        assertTrue(failure.getMessage().contains("PreToolUsage"), failure.getMessage());
    }

    @Test
    void anEventThatIsRecordedButNotImplementedIsRejected() throws Exception {
        // 名全录、行为按需：SessionStart 的事件名在 HookEvent 里，但触发点还没接上。
        writeSettings("""
                {"hooks": {"SessionStart": [{"hooks": [{"type": "command", "command": "true"}]}]}}
                """);

        UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class,
                () -> SettingsHooks.read(new Workspace(workingDirectory)));
        assertTrue(failure.getMessage().contains("SessionStart"), failure.getMessage());
    }

    @Test
    void handlerTypesThatAreRecordedButNotImplementedAreRejectedByName() throws Exception {
        for (String type : List.of("http", "mcp_tool", "prompt", "agent")) {
            writeSettings("{\"hooks\": {\"PreToolUse\": [{\"hooks\": [{\"type\": \"" + type + "\"}]}]}}");

            UnsupportedOperationException failure = assertThrows(
                    UnsupportedOperationException.class,
                    () -> SettingsHooks.read(new Workspace(workingDirectory)),
                    type);
            assertTrue(failure.getMessage().contains(type), failure.getMessage());
        }
    }

    @Test
    void anUnknownHandlerTypeIsRejected() throws Exception {
        writeSettings("{\"hooks\": {\"PreToolUse\": [{\"hooks\": [{\"type\": \"webhook\"}]}]}}");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> SettingsHooks.read(new Workspace(workingDirectory)));
        assertTrue(failure.getMessage().contains("webhook"), failure.getMessage());
    }

    @Test
    void aCommandHandlerWithoutACommandIsRejected() throws Exception {
        writeSettings("{\"hooks\": {\"PreToolUse\": [{\"hooks\": [{\"type\": \"command\"}]}]}}");

        assertThrows(
                IllegalArgumentException.class, () -> SettingsHooks.read(new Workspace(workingDirectory)));
    }

    @Test
    void anEmptyHandlerArrayIsRejected() throws Exception {
        writeSettings("{\"hooks\": {\"PreToolUse\": [{\"matcher\": \"bash\", \"hooks\": []}]}}");

        assertThrows(
                IllegalArgumentException.class, () -> SettingsHooks.read(new Workspace(workingDirectory)));
    }

    @Test
    void aNonPositiveTimeoutIsRejected() throws Exception {
        writeSettings("""
                {"hooks": {"PreToolUse": [
                  {"timeout": 0, "hooks": [{"type": "command", "command": "true"}]}]}}
                """);

        assertThrows(
                IllegalArgumentException.class, () -> SettingsHooks.read(new Workspace(workingDirectory)));
    }

    @Test
    void anInvalidMatcherRegexIsRejectedAtReadTime() throws Exception {
        writeSettings("""
                {"hooks": {"PreToolUse": [
                  {"matcher": "(unclosed", "hooks": [{"type": "command", "command": "true"}]}]}}
                """);

        assertThrows(
                IllegalArgumentException.class, () -> SettingsHooks.read(new Workspace(workingDirectory)));
    }

    private static HookHandler.Command command(
            Map<HookEvent, List<HookMatcher>> hooks, HookEvent event) {
        return (HookHandler.Command) hooks.get(event).getFirst().handlers().getFirst();
    }

    private void writeSettings(String json) throws IOException {
        Path settings = workingDirectory.resolve(".claude").resolve("settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, json);
    }
}
