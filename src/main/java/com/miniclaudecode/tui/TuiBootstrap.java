package com.miniclaudecode.tui;

import com.miniclaudecode.agent.Agent;
import com.miniclaudecode.config.MiniClaudeCodeConfig;
import com.miniclaudecode.hitl.RendererHitlHandler;
import com.miniclaudecode.hitl.SwitchableHitlHandler;
import com.miniclaudecode.llm.LlmClient;
import com.miniclaudecode.tui.config.TuiConfigPanel;
import com.miniclaudecode.util.AnsiStyle;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.util.Objects;

/**
 * TUI 入口与降级检测
 *
 * <p>职责：
 * 1. 判断当前环境是否显式启用 TUI（{@link #shouldUseTui()}）
 * 2. 默认保留现有 CLI 路径
 * 3. TUI 模式时创建 {@link LanternaWindow} 并启动主循环
 *
 * <p>TUI 触发条件：
 * - {@code MINI_CLAUDE_CODE_RENDERER=lanterna|tui} 环境变量或 {@code -Dmini-claude-code.renderer=lanterna|tui}
 * - 兼容旧入口：{@code MINI_CLAUDE_CODE_TUI=true} 环境变量或 {@code -Dmini-claude-code.tui=true} 系统属性
 *
 * <p>即使显式启用，以下情况仍降级：
 * - {@code NO_TUI=true} 环境变量
 * - 终端尺寸 &lt; 80×24
 */
public final class TuiBootstrap {

    private static final String TUI_ENV = "MINI_CLAUDE_CODE_TUI";
    private static final String TUI_PROPERTY = "mini-claude-code.tui";
    private static final String RENDERER_ENV = "MINI_CLAUDE_CODE_RENDERER";
    private static final String RENDERER_PROPERTY = "mini-claude-code.renderer";
    private static final int MIN_COLS = 80;
    private static final int MIN_ROWS = 24;

    private TuiBootstrap() {
    }

    public static boolean shouldUseTui() {
        if (!isTuiRequested()) {
            return false;
        }
        try (Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build()) {
            return shouldUseTui(terminal);
        } catch (IOException e) {
            System.err.println("⚠️ 已显式启用 TUI，但终端检测失败，降级到 CLI 模式: " + e.getMessage());
            return false;
        }
    }

    public static boolean shouldUseTui(Terminal terminal) {
        // 默认保持传统 CLI 交互；全屏 TUI 必须显式开启
        boolean requested = isTuiRequested();
        if (!requested) {
            return false;
        }

        if (Boolean.parseBoolean(Objects.requireNonNullElse(
                System.getenv("NO_TUI"), "false"))) {
            System.out.println(AnsiStyle.heading("💡 提示: NO_TUI=true，已切换为 CLI 模式。"
                    + "要启用 TUI 请清除 NO_TUI 环境变量，并使用 MINI_CLAUDE_CODE_RENDERER=lanterna。"));
            return false;
        }

        if (terminal == null) {
            System.out.println(AnsiStyle.heading("💡 提示: 当前运行环境没有可用系统终端，已切换为 CLI 模式。"));
            return false;
        }
        Size size = terminal.getSize();
        if (size == null || size.getColumns() <= 0 || size.getRows() <= 0) {
            System.out.println(AnsiStyle.heading("💡 提示: 当前运行环境无法读取真实终端尺寸，已切换为 CLI 模式。"
                    + "如需启用 TUI，请在 macOS Terminal / iTerm2 等真实终端中运行。"));
            return false;
        }
        if (size.getColumns() < MIN_COLS || size.getRows() < MIN_ROWS) {
            System.out.println(AnsiStyle.heading("💡 提示: 终端尺寸过小（当前 " +
                    size.getColumns() + "×" + size.getRows() +
                    "，最小需要 " + MIN_COLS + "×" + MIN_ROWS + "），已切换为 CLI 模式。" +
                    "如需启用 TUI，请调整窗口大小后重新运行。"));
            return false;
        }

        return true;
    }

    private static boolean isTuiRequested() {
        String rendererProperty = System.getProperty(RENDERER_PROPERTY);
        if (rendererProperty != null && !rendererProperty.isBlank()) {
            return isLanternaRenderer(rendererProperty);
        }
        String rendererEnv = System.getenv(RENDERER_ENV);
        if (rendererEnv != null && !rendererEnv.isBlank()) {
            return isLanternaRenderer(rendererEnv);
        }
        String property = System.getProperty(TUI_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property);
        }
        return Boolean.parseBoolean(Objects.requireNonNullElse(System.getenv(TUI_ENV), "false"));
    }

    private static boolean isLanternaRenderer(String value) {
        String normalized = value.trim().toLowerCase();
        return "lanterna".equals(normalized) || "tui".equals(normalized);
    }

    /**
     * Lanterna 接管当前 CLI 会话
     *
     * @param config      已加载的配置
     * @param llmClient   LLM 客户端
     * @param reactAgent  复用 CLI 初始化出的 Agent 会话
     * @param hitlHandler 可切换 HITL 代理
     * @throws IOException 如果 Lanterna 初始化失败
     */
    public static void launch(MiniClaudeCodeConfig config,
                              LlmClient llmClient,
                              Agent reactAgent,
                              SwitchableHitlHandler hitlHandler) throws IOException {
        Objects.requireNonNull(config);
        Objects.requireNonNull(llmClient);
        Objects.requireNonNull(reactAgent);
        Objects.requireNonNull(hitlHandler);

        System.out.println(AnsiStyle.section("🖥️  启动 TUI 界面..."));

        try {
            LanternaWindow window = new LanternaWindow(config, llmClient);
            LanternaRenderer renderer = new LanternaRenderer(window);
            reactAgent.setRenderer(renderer);
            reactAgent.setHitlEnabledSupplier(hitlHandler::isEnabled);
            reactAgent.getToolRegistry().setWriteFileObserver(
                    (path, ba) -> renderer.appendDiff(path, ba[0], ba[1]));
            RendererHitlHandler rendererHitl = new RendererHitlHandler(renderer, hitlHandler.isEnabled());
            hitlHandler.setDelegate(rendererHitl);
            TuiSessionController controller = new TuiSessionController(
                    config,
                    llmClient,
                    reactAgent,
                    hitlHandler,
                    window.getRootPane().getCenterPane(),
                    window.getRootPane().getStatusPane(),
                    window::close,
                    () -> new TuiConfigPanel(config, window.getGui()).showConfigDialog(),
                    window::runOnGuiThread
            );
            window.getRootPane().setMessageHandler(controller::submit);
            window.setCloseHook(controller::close);
            window.start();  // 阻塞直到用户退出
            System.out.println("\n👋 再见!");
        } catch (Exception e) {
            System.err.println("❌ TUI 启动失败，降级到 CLI 模式: " + e.getMessage());
            e.printStackTrace();
            throw new IOException("TUI 启动失败", e);
        }
    }
}
