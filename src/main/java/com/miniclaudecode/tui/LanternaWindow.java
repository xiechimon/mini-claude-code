package com.miniclaudecode.tui;

import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.miniclaudecode.config.MiniClaudeCodeConfig;
import com.miniclaudecode.llm.LlmClient;

import java.io.IOException;
import java.util.Objects;

/**
 * 拥有 Lanterna Screen、GUI 主窗口和事件线程调度
 */
public final class LanternaWindow {

    private static volatile boolean tuiMode = false;
    private final Screen screen;
    private final WindowBasedTextGUI gui;
    private final LlmClient llmClient;
    private final MiniClaudeCodeConfig config;
    private final BasicWindow mainWindow;
    private final RootPane rootPane;
    private Runnable closeHook = () -> {
    };

    /**
     * 创建并启动 Screen，调用方随后通过 {@link #start()} 进入阻塞主循环
     *
     * @param config    配置
     * @param llmClient LLM 客户端
     * @throws IOException 如果终端初始化失败
     */
    public LanternaWindow(MiniClaudeCodeConfig config, LlmClient llmClient) throws IOException {
        this.config = Objects.requireNonNull(config);
        this.llmClient = Objects.requireNonNull(llmClient);

        DefaultTerminalFactory terminalFactory = new DefaultTerminalFactory();
        this.screen = new TerminalScreen(terminalFactory.createTerminal());
        this.screen.startScreen();

        this.gui = new MultiWindowTextGUI(screen);

        this.rootPane = new RootPane(config, llmClient);
        this.mainWindow = new BasicWindow("Mini Claude Code v16.0.0");
        mainWindow.setComponent(rootPane);
        gui.addWindow(mainWindow);

        tuiMode = true;
    }

    public static boolean isTuiMode() {
        return tuiMode;
    }

    /**
     * 阻塞运行 TUI 主循环直到窗口关闭
     */
    public void start() {
        try {
            gui.waitForWindowToClose(mainWindow);
        } catch (Exception e) {
            System.err.println("❌ TUI 主循环异常: " + e.getMessage());
        } finally {
            closeHook.run();
            tuiMode = false;
            closeScreen();
        }
    }

    public void close() {
        mainWindow.close();
        closeScreen();
        tuiMode = false;
    }

    private void closeScreen() {
        try {
            if (screen != null) {
                screen.stopScreen();
            }
        } catch (IOException e) {
            System.err.println("⚠️ 关闭屏幕失败: " + e.getMessage());
        }
    }

    public void setCloseHook(Runnable closeHook) {
        this.closeHook = closeHook == null ? () -> {
        } : closeHook;
    }

    public void runOnGuiThread(Runnable task) {
        Objects.requireNonNull(task);
        if (gui != null) {
            gui.getGUIThread().invokeLater(task);
        }
    }

    public Screen getScreen() {
        return screen;
    }

    public WindowBasedTextGUI getGui() {
        return gui;
    }

    public RootPane getRootPane() {
        return rootPane;
    }

    public LlmClient getLlmClient() {
        return llmClient;
    }

    public MiniClaudeCodeConfig getConfig() {
        return config;
    }
}
