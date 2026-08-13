package com.miniclaudecode.tui.pane;

import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.miniclaudecode.llm.LlmClient;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 右侧状态栏面板
 *
 * <p>显示：
 * - 模型名称 / 提供商
 * - Token 使用量（已用 / 预算）
 * - 任务耗时
 * - 当前模式（ReAct / Plan / Team）
 * - 快捷键提示（精简版）
 *
 * <p>由 TUI 会话控制器在任务开始、结束和 token 变化时更新
 */
public class StatusPane extends Panel {

    private final LlmClient llmClient;
    private final Label modelLabel;
    private final Label tokenLabel;
    private final Label modeLabel;
    private final Label timeLabel;
    private final AtomicLong taskStartTime = new AtomicLong(0);

    public StatusPane(com.miniclaudecode.config.MiniClaudeCodeConfig config, LlmClient llmClient) {
        super();
        this.llmClient = llmClient;

        setLayoutManager(new LinearLayout(Direction.VERTICAL));

        this.modelLabel = new Label("🤖 " + (llmClient != null ? llmClient.getModelName() : "?"));
        this.tokenLabel = new Label("💡 --");
        this.modeLabel = new Label("🔄 ReAct");
        this.timeLabel = new Label("⏱ --");

        addComponent(modelLabel);
        addComponent(tokenLabel);
        addComponent(modeLabel);
        addComponent(timeLabel);
    }

    public void updateTokenUsage(long used, long budget, long cached) {
        tokenLabel.setText(String.format("💡 %d/%d", used, budget));
        if (cached > 0) {
            tokenLabel.setText(tokenLabel.getText() + String.format(" (cached: %d)", cached));
        }
    }

    public void updateMode(String mode) {
        modeLabel.setText("🔄 " + (mode != null ? mode : "ReAct"));
    }

    public void startTimer() {
        taskStartTime.set(System.currentTimeMillis());
    }

    public void stopTimer() {
        if (taskStartTime.get() > 0) {
            long elapsedMs = System.currentTimeMillis() - taskStartTime.get();
            timeLabel.setText(String.format("⏱ %.1fs", elapsedMs / 1000.0));
            taskStartTime.set(0);
        }
    }
}
