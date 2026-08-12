package com.miniclaudecode.render;

import com.miniclaudecode.render.inline.InlineRenderer;
import com.miniclaudecode.render.inline.TerminalCapabilities;
import org.jline.terminal.Terminal;

/**
 * 渲染器形态由系统属性、环境变量和终端能力共同决定
 *
 * <p>选型规则：
 * <ul>
 *   <li>{@code -Dmini-claude-code.renderer} > {@code MINI_CLAUDE_CODE_RENDERER} 环境变量 > 默认 inline</li>
 *   <li>{@code lanterna} → Lanterna 全屏 TUI（由 {@code TuiBootstrap} 在 CLI 循环前接管）</li>
 *   <li>{@code plain} → {@link PlainRenderer}</li>
 *   <li>{@code inline}（默认）→ {@link InlineRenderer}</li>
 *   <li>兼容：{@code MINI_CLAUDE_CODE_TUI=true} → 等价 {@code lanterna}，打 deprecation 提示</li>
 * </ul>
 *
 * <p>当 inline 目标渲染器初始化失败（如终端不支持 ANSI），自动 fallback 到
 * {@link PlainRenderer}，并在 stderr 打日志；Lanterna 模式不经由本工厂创建，
 * 而是在 {@code Main} 里进入 {@code TuiBootstrap}
 */
public final class RendererFactory {

    public enum Mode {
        INLINE, LANTERNA, PLAIN
    }

    private RendererFactory() {
    }

    public static Mode resolveMode() {
        String prop = System.getProperty("mini-claude-code.renderer");
        if (prop != null && !prop.isBlank()) {
            return parse(prop);
        }
        String env = System.getenv("MINI_CLAUDE_CODE_RENDERER");
        if (env != null && !env.isBlank()) {
            return parse(env);
        }
        // 兼容旧 MINI_CLAUDE_CODE_TUI=true → lanterna
        String legacyTui = System.getenv("MINI_CLAUDE_CODE_TUI");
        if (legacyTui != null && Boolean.parseBoolean(legacyTui.trim())) {
            System.err.println("⚠️ MINI_CLAUDE_CODE_TUI=true 已废弃，请改用 MINI_CLAUDE_CODE_RENDERER=lanterna");
            return Mode.LANTERNA;
        }
        return Mode.INLINE;
    }

    private static Mode parse(String raw) {
        return switch (raw.trim().toLowerCase()) {
            case "lanterna", "tui" -> Mode.LANTERNA;
            case "plain" -> Mode.PLAIN;
            case "inline" -> Mode.INLINE;
            default -> {
                System.err.println("⚠️ 未识别的 MINI_CLAUDE_CODE_RENDERER='" + raw + "'，回退到 inline");
                yield Mode.INLINE;
            }
        };
    }

    /**
     * inline 不支持 ANSI 时回退 plain，lanterna 到达此处也视为已降级
     *
     * @param terminal JLine terminal，可为 null
     */
    public static Renderer create(Mode mode, Terminal terminal) {
        return switch (mode) {
            case PLAIN -> new PlainRenderer();
            case INLINE -> {
                if (TerminalCapabilities.supportsAnsi(terminal)) {
                    yield new InlineRenderer(terminal);
                }
                System.err.println("⚠️ 终端不支持 ANSI，inline 模式回退到 plain");
                yield new PlainRenderer();
            }
            case LANTERNA -> new PlainRenderer();
        };
    }
}
