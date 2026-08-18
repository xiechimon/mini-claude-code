package com.miniclaudecode.cli;

/**
 * 将 Plan 审阅阶段的按键序列解析为明确动作
 * ESC 控制序列不会被误判为取消
 */
final class PlanReviewInputParser {

    private PlanReviewInputParser() {
    }

    static Decision parse(String input) {
        if (input != null && input.equals("\u001B")) {
            return new Decision(DecisionType.CANCEL, null);
        }

        String trimmed = input == null ? "" : input.trim();

        if (trimmed.isEmpty()
                || trimmed.equalsIgnoreCase("y")
                || trimmed.equalsIgnoreCase("yes")
                || trimmed.equalsIgnoreCase("run")
                || trimmed.equalsIgnoreCase("/run")) {
            return new Decision(DecisionType.EXECUTE, null);
        }

        if (trimmed.equalsIgnoreCase("cancel")
                || trimmed.equalsIgnoreCase("esc")
                || trimmed.equalsIgnoreCase("/cancel")) {
            return new Decision(DecisionType.CANCEL, null);
        }

        return new Decision(DecisionType.SUPPLEMENT, trimmed);
    }

    enum DecisionType {
        EXECUTE,
        SUPPLEMENT,
        CANCEL
    }

    record Decision(DecisionType type, String feedback) {
    }
}
