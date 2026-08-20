package com.miniclaudecode.cli;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 覆盖 Ctrl+C 双击退出的状态机：空行内 1 秒内二次按下才退出，
 * 非空行按下只清空（不计入武装），提交命令后武装状态清零
 */
class MainCtrlCExitGuardTest {

    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1);

    @Test
    void firstPressOnEmptyLineArms() {
        Main.CtrlCExitGuard guard = new Main.CtrlCExitGuard();
        assertEquals(Main.CtrlCExitGuard.Result.ARMED, guard.onInterrupt("", 0L));
    }

    @Test
    void firstPressWithNullPartialLineArms() {
        Main.CtrlCExitGuard guard = new Main.CtrlCExitGuard();
        assertEquals(Main.CtrlCExitGuard.Result.ARMED, guard.onInterrupt(null, 0L));
    }

    @Test
    void secondPressWithinWindowExits() {
        Main.CtrlCExitGuard guard = new Main.CtrlCExitGuard();
        guard.onInterrupt("", 0L);
        assertEquals(Main.CtrlCExitGuard.Result.EXIT, guard.onInterrupt("", WINDOW_NANOS / 2));
    }

    @Test
    void secondPressExactlyAtWindowBoundaryExits() {
        Main.CtrlCExitGuard guard = new Main.CtrlCExitGuard();
        guard.onInterrupt("", 0L);
        assertEquals(Main.CtrlCExitGuard.Result.EXIT, guard.onInterrupt("", WINDOW_NANOS));
    }

    @Test
    void secondPressAfterWindowRearms() {
        Main.CtrlCExitGuard guard = new Main.CtrlCExitGuard();
        guard.onInterrupt("", 0L);
        assertEquals(Main.CtrlCExitGuard.Result.ARMED, guard.onInterrupt("", WINDOW_NANOS + 1));
    }

    @Test
    void nonEmptyLineIsSilentAndDoesNotArm() {
        Main.CtrlCExitGuard guard = new Main.CtrlCExitGuard();
        assertEquals(Main.CtrlCExitGuard.Result.SILENT, guard.onInterrupt("half typed", 0L));
    }

    @Test
    void nonEmptyLineDisarmsPreviousArm() {
        Main.CtrlCExitGuard guard = new Main.CtrlCExitGuard();
        guard.onInterrupt("", 0L);
        assertEquals(Main.CtrlCExitGuard.Result.SILENT, guard.onInterrupt("typing now", WINDOW_NANOS / 4));
        assertEquals(Main.CtrlCExitGuard.Result.ARMED, guard.onInterrupt("", WINDOW_NANOS / 2));
    }

    @Test
    void promptRoundCompletedClearsArmedState() {
        Main.CtrlCExitGuard guard = new Main.CtrlCExitGuard();
        guard.onInterrupt("", 0L);
        guard.onPromptRoundCompleted();
        assertEquals(Main.CtrlCExitGuard.Result.ARMED, guard.onInterrupt("", WINDOW_NANOS / 2));
    }

    @Test
    void exitConsumesArmedStateForNextRound() {
        Main.CtrlCExitGuard guard = new Main.CtrlCExitGuard();
        guard.onInterrupt("", 0L);
        guard.onInterrupt("", WINDOW_NANOS / 2);
        assertEquals(Main.CtrlCExitGuard.Result.ARMED, guard.onInterrupt("", WINDOW_NANOS));
    }
}
