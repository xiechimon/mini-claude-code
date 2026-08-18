package com.miniclaudecode.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.miniclaudecode.config.MiniClaudeCodeConfig;
import com.miniclaudecode.llm.LlmClient;
import com.miniclaudecode.tui.pane.CenterPane;
import com.miniclaudecode.tui.pane.FileTreePane;
import com.miniclaudecode.tui.pane.InputBar;
import com.miniclaudecode.tui.pane.StatusPane;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 根面板容器，实现三栏布局
 *
 * <p>布局结构：
 * <pre>
 * ┌─────────────────┬─────────────────────────────────────┬──────────┐
 * │  文件树面板      │  对话流面板                          │  状态栏   │
 * │  (FileTreePane) │  (CenterPane)                       │ (StatusPane)
 * │                 │                                     │          │
 * ├─────────────────┴─────────────────────────────────────┴──────────┤
 * │  输入栏 (InputBar)                                              │
 * └─────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class RootPane extends Panel {

    private static final double FILE_TREE_RATIO = 0.25;
    private static final double STATUS_RATIO = 0.10;
    private final FileTreePane fileTreePane;
    private final CenterPane centerPane;
    private final StatusPane statusPane;
    private final InputBar inputBar;
    private final LlmClient llmClient;
    private final MiniClaudeCodeConfig config;
    private Consumer<String> messageHandler;
    private boolean fileTreeVisible = true;

    public RootPane(MiniClaudeCodeConfig config, LlmClient llmClient) {
        super();
        this.config = Objects.requireNonNull(config);
        this.llmClient = Objects.requireNonNull(llmClient);

        this.fileTreePane = new FileTreePane(config);
        this.centerPane = new CenterPane(config, llmClient);
        this.statusPane = new StatusPane(config, llmClient);
        this.messageHandler = centerPane::onUserMessage;
        this.inputBar = new InputBar(config, llmClient, this::onUserMessage);

        setLayoutManager(new LinearLayout(Direction.VERTICAL));

        Panel topPanel = new Panel();
        topPanel.setLayoutManager(new LinearLayout(Direction.HORIZONTAL));

        topPanel.addComponent(fileTreePane.withBorder(Borders.singleLine("项目结构")));

        topPanel.addComponent(centerPane.withBorder(Borders.singleLine("对话")).setLayoutData(
                LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)));

        topPanel.addComponent(statusPane.withBorder(Borders.singleLine("状态")).setLayoutData(
                LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)));

        addComponent(topPanel.setLayoutData(
                LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)));

        addComponent(inputBar.withBorder(Borders.singleLine("输入")).setLayoutData(
                LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)));
    }

    /**
     * 按终端宽度调整三栏布局
     *
     * @param newSize 新的终端尺寸
     */
    public void onResize(TerminalSize newSize) {
        if (newSize == null) {
            return;
        }

        int cols = newSize.getColumns();
        int rows = newSize.getRows();

        // 窄于 80 列时隐藏文件树，窄于 120 列时固定为 15 列
        int fileTreeWidth;
        if (cols < 80) {
            fileTreeWidth = 0;
        } else if (cols < 120) {
            fileTreeWidth = 15;
        } else {
            fileTreeWidth = (int) (cols * FILE_TREE_RATIO);
        }

        fileTreePane.setPreferredSize(new TerminalSize(fileTreeWidth, rows - 5));

        int statusWidth = Math.min(20, (int) (cols * STATUS_RATIO));
        statusPane.setPreferredSize(new TerminalSize(statusWidth, rows - 5));

        int centerWidth = cols - fileTreeWidth - statusWidth - 2;
        centerPane.setPreferredSize(new TerminalSize(Math.max(20, centerWidth), rows - 5));

        invalidate();
    }

    public void onUserMessage(String message) {
        if (message != null && !message.trim().isEmpty()) {
            messageHandler.accept(message);
        }
    }

    public void setMessageHandler(Consumer<String> messageHandler) {
        this.messageHandler = messageHandler == null ? centerPane::onUserMessage : messageHandler;
    }

    public FileTreePane getFileTreePane() {
        return fileTreePane;
    }

    public CenterPane getCenterPane() {
        return centerPane;
    }

    public StatusPane getStatusPane() {
        return statusPane;
    }

    public InputBar getInputBar() {
        return inputBar;
    }

    public void toggleFileTree() {
        fileTreeVisible = !fileTreeVisible;
        fileTreePane.setVisible(fileTreeVisible);
        invalidate();
        centerPane.appendSystemMessage("文件树已" + (fileTreeVisible ? "显示" : "隐藏"));
    }

    @Override
    public boolean handleInput(KeyStroke keyStroke) {
        if (keyStroke.getKeyType() == KeyType.Character && keyStroke.isCtrlDown() && keyStroke.getCharacter() == 'O') {
            centerPane.appendSystemMessage("代码块折叠快捷键已收到；当前版本保留完整代码输出。");
            return true;
        }

        if (keyStroke.getKeyType() == KeyType.Character && keyStroke.isCtrlDown() && keyStroke.getCharacter() == 'P') {
            centerPane.appendSystemMessage("对话历史已持续保存到 ~/.mini-claude-code/history/。");
            return true;
        }

        if (keyStroke.getKeyType() == KeyType.Character && keyStroke.isCtrlDown() && keyStroke.getCharacter() == '\\') {
            toggleFileTree();
            return true;
        }

        return super.handleInput(keyStroke);
    }
}
