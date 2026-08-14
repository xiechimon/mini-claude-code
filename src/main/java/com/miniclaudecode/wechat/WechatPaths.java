package com.miniclaudecode.wechat;

import java.nio.file.Path;

/**
 * 统一计算微信通道的本地状态、凭据和日志路径
 */
public final class WechatPaths {
    private WechatPaths() {
    }

    public static Path root() {
        String configured = System.getProperty("mini-claude-code.wechat.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("MINI_CLAUDE_CODE_WECHAT_DIR");
        }
        if (configured == null || configured.isBlank()) {
            configured = Path.of(System.getProperty("user.home"), ".mini-claude-code", "wechat").toString();
        }
        return Path.of(configured);
    }

    public static Path accountsDir() {
        return root().resolve("accounts");
    }

    public static Path sessionsDir() {
        return root().resolve("sessions");
    }

    public static Path mediaDir() {
        return root().resolve("media");
    }

    public static Path logsDir() {
        return root().resolve("logs");
    }

    public static Path pidFile() {
        return root().resolve("mini-claude-code-wechat.pid");
    }
}
