package com.xmon.nanoagent.hook;

import com.anthropic.core.JsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试 hook 注册表的触发与逐事件归并
 *
 * <p>用进程内回调而非外部进程：本类验证的是「跑哪些、怎么合」，与传输面无关。
 * 传输面（stdin / exit code / stdout）由 {@link CommandHookRunnerTest} 覆盖。
 */
final class HookDispatcherTest {

    @TempDir
    Path workingDirectory;

    @Test
    void everyMatchingHookRunsEvenAfterOneOfThemDenies() throws Exception {
        // 与课程参考解法的核心差异。code.py 的 trigger_hooks 首个非 None 返回即短路，
        // 于是注册在权限 hook 之后的审计 hook 在权限拒绝时不执行——恰恰是最需要记录的那一次。
        List<String> executed = new ArrayList<>();
        HookDispatcher hooks = dispatcher();
        hooks.register(HookEvent.PRE_TOOL_USE, callback("*", input -> {
            executed.add("gate");
            return deny("nope");
        }));
        hooks.register(HookEvent.PRE_TOOL_USE, callback("*", input -> {
            executed.add("audit");
            return HookOutput.Sync.none();
        }));

        HookDispatcher.PreToolUseVerdict verdict = hooks.preToolUse("bash", input(), "call-1");

        assertEquals(List.of("gate", "audit"), executed);
        assertEquals(Optional.of("nope"), verdict.denyReason());
    }

    @Test
    void denyWinsOverAllowRegardlessOfRegistrationOrder() throws Exception {
        // 契约的归并优先级 deny > defer > ask > allow。
        for (boolean allowFirst : List.of(true, false)) {
            HookDispatcher hooks = dispatcher();
            HookMatcher allow = callback("*", input -> decision(HookPermissionDecision.ALLOW, "fine"));
            HookMatcher deny = callback("*", input -> deny("blocked"));
            hooks.register(HookEvent.PRE_TOOL_USE, allowFirst ? allow : deny);
            hooks.register(HookEvent.PRE_TOOL_USE, allowFirst ? deny : allow);

            assertEquals(
                    Optional.of("blocked"),
                    hooks.preToolUse("bash", input(), "call-1").denyReason(),
                    "allowFirst=" + allowFirst);
        }
    }

    @Test
    void unimplementedDecisionsProceedButLeaveAVisibleWarning() throws Exception {
        for (HookPermissionDecision unimplemented : List.of(
                HookPermissionDecision.ALLOW, HookPermissionDecision.ASK, HookPermissionDecision.DEFER)) {
            HookDispatcher hooks = dispatcher();
            hooks.register(HookEvent.PRE_TOOL_USE, callback("*", input -> decision(unimplemented, "r")));

            HookDispatcher.PreToolUseVerdict verdict = hooks.preToolUse("bash", input(), "call-1");

            assertTrue(verdict.denyReason().isEmpty(), unimplemented.contractValue());
            // 静默忽略会让「未实现」看起来像「放行」。
            assertTrue(
                    verdict.notices().warnings().stream()
                            .anyMatch(warning -> warning.contains(unimplemented.contractValue())),
                    unimplemented.contractValue() + " warnings: " + verdict.notices().warnings());
        }
    }

    @Test
    void aTopLevelBlockIsEquivalentToDenyForPreToolUse() throws Exception {
        HookDispatcher hooks = dispatcher();
        hooks.register(HookEvent.PRE_TOOL_USE, callback("*", input -> HookOutput.Sync.block("exit-2 style")));

        assertEquals(
                Optional.of("exit-2 style"),
                hooks.preToolUse("bash", input(), "call-1").denyReason());
    }

    @Test
    void matcherNarrowsWhichToolsFireTheHook() throws Exception {
        List<String> fired = new ArrayList<>();
        HookDispatcher hooks = dispatcher();
        hooks.register(HookEvent.PRE_TOOL_USE, callback("bash", input -> {
            fired.add(((HookInput.PreToolUse) input).toolName());
            return HookOutput.Sync.none();
        }));

        hooks.preToolUse("bash", input(), "call-1");
        hooks.preToolUse("read_file", input(), "call-2");

        assertEquals(List.of("bash"), fired);
    }

    @Test
    void everyAdditionalContextValueIsDelivered() throws Exception {
        HookDispatcher hooks = dispatcher();
        hooks.register(HookEvent.PRE_TOOL_USE, callback("*", input -> context("first")));
        hooks.register(HookEvent.PRE_TOOL_USE, callback("*", input -> context("second")));

        assertEquals(
                List.of("first", "second"),
                hooks.preToolUse("bash", input(), "call-1").additionalContext());
    }

    @Test
    void continueFalseHaltsTheSessionAndCarriesItsReason() throws Exception {
        HookDispatcher hooks = dispatcher();
        hooks.register(HookEvent.POST_TOOL_USE, callback("*", input -> new HookOutput.Sync(
                Optional.of(false),
                Optional.of("budget exhausted"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty())));

        HookDispatcher.PostToolUseVerdict verdict =
                hooks.postToolUse("bash", input(), "output", "call-1");

        assertTrue(verdict.notices().halt());
        assertEquals(Optional.of("budget exhausted"), verdict.notices().haltReason());
    }

    @Test
    void theLastHookToRewriteTheToolOutputWins() throws Exception {
        // 契约没规定多个 hook 都设 updatedToolOutput 时怎么办；本实现串行跑，后注册者胜出。
        HookDispatcher hooks = dispatcher();
        hooks.register(HookEvent.POST_TOOL_USE, callback("*", input -> HookOutput.Sync.of(
                new HookSpecificOutput.PostToolUse(Optional.empty(), Optional.of("first")))));
        hooks.register(HookEvent.POST_TOOL_USE, callback("*", input -> HookOutput.Sync.of(
                new HookSpecificOutput.PostToolUse(Optional.empty(), Optional.of("second")))));

        assertEquals(
                Optional.of("second"),
                hooks.postToolUse("bash", input(), "original", "call-1").updatedToolOutput());
    }

    @Test
    void stopContinuesOnBothBlockAndAdditionalContext() throws Exception {
        HookDispatcher blocking = dispatcher();
        blocking.register(HookEvent.STOP, callback("*", input -> HookOutput.Sync.block("run the tests")));
        HookDispatcher.StopVerdict blocked = blocking.stop(false, "done");
        assertTrue(blocked.continues());
        assertEquals("run the tests", blocked.continuationMessage());

        HookDispatcher feedback = dispatcher();
        feedback.register(HookEvent.STOP, callback("*", input -> HookOutput.Sync.of(
                new HookSpecificOutput.Stop(Optional.of("also lint")))));
        HookDispatcher.StopVerdict advised = feedback.stop(false, "done");
        assertTrue(advised.continues());
        assertEquals("also lint", advised.continuationMessage());

        HookDispatcher silent = dispatcher();
        silent.register(HookEvent.STOP, callback("*", input -> HookOutput.Sync.none()));
        assertFalse(silent.stop(false, "done").continues());
    }

    @Test
    void stopHookActiveIsHandedToTheHook() throws Exception {
        List<Boolean> seen = new ArrayList<>();
        HookDispatcher hooks = dispatcher();
        hooks.register(HookEvent.STOP, callback("*", input -> {
            seen.add(((HookInput.Stop) input).stopHookActive());
            return HookOutput.Sync.none();
        }));

        hooks.stop(false, "first");
        hooks.stop(true, "second");

        assertEquals(List.of(false, true), seen);
    }

    @Test
    void registeringAnUnimplementedEventFailsLoudly() {
        HookDispatcher hooks = dispatcher();
        // 一条挂在未实现事件上的 hook 永远不会跑，静默接受会让它看起来像装好了。
        UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class,
                () -> hooks.register(
                        HookEvent.SESSION_START, callback("*", input -> HookOutput.Sync.none())));
        assertTrue(failure.getMessage().contains("SessionStart"), failure.getMessage());
    }

    @Test
    void anAsyncHookOutputIsRejectedRatherThanTreatedAsNoVerdict() throws Exception {
        HookDispatcher hooks = dispatcher();
        hooks.register(HookEvent.PRE_TOOL_USE, callback("*", input -> new HookOutput.Async(30)));

        assertThrows(
                UnsupportedOperationException.class, () -> hooks.preToolUse("bash", input(), "call-1"));
    }

    @Test
    void anUnimplementedHandlerTypeIsRejectedWithItsContractName() throws Exception {
        HookDispatcher hooks = dispatcher();
        hooks.register(
                HookEvent.PRE_TOOL_USE,
                new HookMatcher(
                        "*",
                        List.of(new HookHandler.Http("http://localhost", Map.of(), List.of())),
                        Optional.empty()));

        UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class, () -> hooks.preToolUse("bash", input(), "call-1"));
        assertTrue(failure.getMessage().contains("http"), failure.getMessage());
    }

    private HookDispatcher dispatcher() {
        return new HookDispatcher("session-1", workingDirectory, "default", Map.of());
    }

    private static HookMatcher callback(String matcher, Function<HookInput, HookOutput> body) {
        return new HookMatcher(matcher, List.of(new HookHandler.Callback(body)), Optional.empty());
    }

    private static HookOutput deny(String reason) {
        return decision(HookPermissionDecision.DENY, reason);
    }

    private static HookOutput decision(HookPermissionDecision decision, String reason) {
        return HookOutput.Sync.of(new HookSpecificOutput.PreToolUse(
                Optional.of(decision), Optional.of(reason), Optional.empty()));
    }

    private static HookOutput context(String additionalContext) {
        return HookOutput.Sync.of(new HookSpecificOutput.PreToolUse(
                Optional.empty(), Optional.empty(), Optional.of(additionalContext)));
    }

    private static JsonValue input() {
        return JsonValue.from(Map.of("command", "ls"));
    }
}
