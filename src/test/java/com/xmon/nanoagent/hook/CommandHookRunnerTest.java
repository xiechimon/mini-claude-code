package com.xmon.nanoagent.hook;

import com.anthropic.core.JsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试外部进程 hook 的 exit code 与 stdout 契约
 *
 * <p>这是 s04 契约里最独特的一块，也是信任边界的第一次出现：hook 是用户配置的外部进程，
 * 它的退出码和 stdout 都要按固定规则解读，解读错了闸门就形同虚设。
 *
 * <p>用真实 {@code sh -c} 子进程而不是造假：stdin 投递、两路输出流、退出码、超时是一整套，
 * 拆开来单测任何一半都测不到它们的接缝。
 */
final class CommandHookRunnerTest {

    @TempDir
    Path workingDirectory;

    @Test
    void eventDataArrivesOnStdinWithContractFieldNames() throws Exception {
        // 把 stdin 原样吐回 stdout，再断言字段名是契约的 snake_case。
        HookOutput.Sync output = run("cat", preToolUse());

        assertTrue(output.systemMessage().isEmpty(), "unexpected warning: " + output.systemMessage());
        String json = CommandHookRunner.toJson(preToolUse());
        assertTrue(json.contains("\"hook_event_name\":\"PreToolUse\""), json);
        assertTrue(json.contains("\"session_id\":\"session-1\""), json);
        assertTrue(json.contains("\"permission_mode\":\"default\""), json);
        assertTrue(json.contains("\"tool_name\":\"bash\""), json);
        assertTrue(json.contains("\"tool_use_id\":\"call-1\""), json);
        assertTrue(json.contains("\"command\":\"ls\""), json);
        // transcript_path 刻意不发：本项目不落盘 transcript，发假路径会让 hook 拿到 FileNotFound。
        assertFalse(json.contains("transcript_path"), json);
    }

    @Test
    void exitZeroWithJsonOnStdoutCarriesTheVerdict() throws Exception {
        HookOutput.Sync output = run(
                "printf '%s' '{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\","
                        + "\"permissionDecision\":\"deny\",\"permissionDecisionReason\":\"nope\"}}'",
                preToolUse());

        HookSpecificOutput.PreToolUse specific =
                output.specificAs(HookSpecificOutput.PreToolUse.class).orElseThrow();
        assertEquals(Optional.of(HookPermissionDecision.DENY), specific.decision());
        assertEquals(Optional.of("nope"), specific.reason());
    }

    @Test
    void exitZeroWithPlainTextReachesTheModelOnlyForUserPromptSubmit() throws Exception {
        HookOutput.Sync submit = run("printf 'branch is main'", userPromptSubmit());
        assertEquals(
                Optional.of("branch is main"),
                submit.specificAs(HookSpecificOutput.UserPromptSubmit.class)
                        .orElseThrow()
                        .additionalContext());

        // 其余事件的纯文本 stdout 丢弃：契约只让三个事件把它当上下文。
        HookOutput.Sync pre = run("printf 'branch is main'", preToolUse());
        assertTrue(pre.specific().isEmpty(), "plain stdout must not become context: " + pre);
    }

    @Test
    void exitTwoBlocksAndTakesStderrAsTheReason() throws Exception {
        HookOutput.Sync output = run("printf 'rm is banned here' >&2; exit 2", preToolUse());

        assertTrue(output.blocked());
        // 格式照抄真实 Claude Code 实测输出：<事件>:<工具> hook error: [<命令>]: <stderr>
        assertEquals(
                Optional.of("PreToolUse:bash hook error: ["
                        + "printf 'rm is banned here' >&2; exit 2]: rm is banned here"),
                output.reason());
    }

    @Test
    void exitTwoBlocksEvenWhenTheJsonSaysAllow() throws Exception {
        // 契约明文：exit 2 是 JSON 唯一盖不住的结果。
        HookOutput.Sync output = run(
                "printf '%s' '{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\","
                        + "\"permissionDecision\":\"allow\"}}'; exit 2",
                preToolUse());

        assertTrue(output.blocked());
    }

    @Test
    void exitTwoPrefersTheJsonReasonOverStderr() throws Exception {
        HookOutput.Sync output = run(
                "printf 'stderr text' >&2; printf '%s' '{\"reason\":\"json reason\"}'; exit 2",
                preToolUse());

        assertEquals(Optional.of("json reason"), output.reason());
    }

    @Test
    void otherExitCodesDoNotBlockButTheirJsonStillApplies() throws Exception {
        HookOutput.Sync withJson = run(
                "printf '%s' '{\"systemMessage\":\"heads up\"}'; exit 7", preToolUse());
        assertFalse(withJson.blocked());
        assertEquals(Optional.of("heads up"), withJson.systemMessage());

        HookOutput.Sync withoutJson = run("printf 'oops' >&2; exit 7", preToolUse());
        assertFalse(withoutJson.blocked());
        assertTrue(withoutJson.systemMessage().orElseThrow().contains("exited 7"),
                "a non-blocking failure must still be visible: " + withoutJson.systemMessage());
    }

    @Test
    void aMissingScriptIsANonBlockingErrorThatStillWarnsTheUser() throws Exception {
        // 契约把「脚本路径打错」归入非阻塞错误：动作继续。但必须留下痕迹——
        // 一个静默失败的闸门和一个放行的闸门在运行时无从分辨，而 settings.json 里打错一个
        // 路径就会走到这里（sh 以 127 退出）。
        HookOutput.Sync output = run("/nonexistent/hook.sh", preToolUse());

        assertFalse(output.blocked());
        String warning = output.systemMessage().orElseThrow();
        assertTrue(warning.contains("exited 127"), "warning should carry the exit code: " + warning);
        assertTrue(warning.contains("No such file"), "warning should carry the shell message: " + warning);
    }

    @Test
    void aHookThatIgnoresStdinIsNotTreatedAsAFailure() throws Exception {
        // 不读 stdin 的 hook 完全合法（只看环境变量就干活的那种）。它常在我们写完 JSON 之前就退出，
        // 管道断开、写入抛 Stream closed。把它当失败会让这类 hook 随调度顺序随机失败。
        for (int attempt = 0; attempt < 20; attempt++) {
            HookOutput.Sync output = run("exit 0", preToolUse());
            assertTrue(output.systemMessage().isEmpty(),
                    "attempt " + attempt + " reported a failure: " + output.systemMessage());
        }
    }

    @Test
    void aTimedOutHookProducesNoVerdictAndDoesNotBlock() throws Exception {
        HookOutput.Sync output = new CommandHookRunner(workingDirectory, Map.of())
                .run(new HookHandler.Command("sleep 30", Duration.ofMillis(200)), preToolUse());

        // 契约明确警告：不要指望一个卡住的 hook 充当闸门。
        assertFalse(output.blocked());
        assertTrue(output.specific().isEmpty());
        assertTrue(output.systemMessage().orElseThrow().contains("timed out"),
                "user must be told the gate did not run: " + output.systemMessage());
    }

    @Test
    void unparsableJsonDegradesToNoVerdictWithAWarning() throws Exception {
        HookOutput.Sync output = run("printf '%s' '{not json'", preToolUse());

        assertFalse(output.blocked());
        assertTrue(output.specific().isEmpty());
        assertTrue(output.systemMessage().orElseThrow().contains("unparsable"),
                "degradation must not be silent: " + output.systemMessage());
    }

    @Test
    void hookSpecificOutputWithAMismatchedEventNameIsIgnored() throws Exception {
        // 契约要求 hookEventName 存在且匹配，不匹配属于 schema 校验失败，按契约降级为无判定。
        HookOutput.Sync output = run(
                "printf '%s' '{\"hookSpecificOutput\":{\"hookEventName\":\"PostToolUse\","
                        + "\"additionalContext\":\"wrong event\"}}'",
                preToolUse());

        assertTrue(output.specific().isEmpty(), "mismatched hookEventName must be ignored: " + output);
    }

    @Test
    void continueFalseIsCarriedThroughWithItsStopReason() throws Exception {
        HookOutput.Sync output = run(
                "printf '%s' '{\"continue\":false,\"stopReason\":\"budget exhausted\"}'",
                preToolUse());

        assertEquals(Optional.of(false), output.continueLoop());
        assertEquals(Optional.of("budget exhausted"), output.stopReason());
    }

    private HookOutput.Sync run(String command, HookInput input) throws InterruptedException {
        return new CommandHookRunner(workingDirectory, Map.of())
                .run(new HookHandler.Command(command, Duration.ofSeconds(10)), input);
    }

    private static HookInput preToolUse() {
        return new HookInput.PreToolUse(
                base(), "bash", JsonValue.from(Map.of("command", "ls")), "call-1");
    }

    private static HookInput userPromptSubmit() {
        return new HookInput.UserPromptSubmit(base(), "what branch am I on");
    }

    private static HookInput.Base base() {
        return new HookInput.Base("session-1", "/tmp/workspace", "default");
    }
}
