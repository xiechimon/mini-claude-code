package com.miniclaudecode.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * 在用户目录保存最近一次微信账号和工作区绑定
 * 支持 POSIX 时尽量收紧目录及凭据文件权限
 */
public record WechatAccountStore(Path root) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public WechatAccountStore(Path root) {
        this.root = root == null ? WechatPaths.root() : root;
    }

    public static WechatAccountStore createDefault() {
        return new WechatAccountStore(WechatPaths.root());
    }

    private static void secureDirectory(Path dir) {
        try {
            if (Files.exists(dir)) {
                Files.setPosixFilePermissions(dir, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            }
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private static void secureFile(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    public Optional<WechatAccount> loadLatest() {
        Path file = accountFile();
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(file.toFile(), WechatAccount.class));
        } catch (IOException e) {
            throw new IllegalStateException("读取微信账号失败: " + e.getMessage(), e);
        }
    }

    public void save(WechatAccount account) {
        if (account == null) {
            return;
        }
        try {
            Files.createDirectories(root.resolve("accounts"));
            secureDirectory(root);
            secureDirectory(root.resolve("accounts"));
            Path file = accountFile();
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), account);
            secureFile(file);
        } catch (IOException e) {
            throw new IllegalStateException("保存微信账号失败: " + e.getMessage(), e);
        }
    }

    public WechatAccount createAccount(String token, String accountId, String baseUrl, String boundUserId,
                                       String workspace) {
        return new WechatAccount(
                token,
                accountId,
                baseUrl == null || baseUrl.isBlank() ? IlinkClient.DEFAULT_BASE_URL : baseUrl,
                boundUserId,
                workspace,
                "",
                Instant.now().toString());
    }

    public Path mediaDir() {
        Path dir = root.resolve("media");
        try {
            Files.createDirectories(dir);
            secureDirectory(root);
            secureDirectory(dir);
        } catch (IOException e) {
            throw new IllegalStateException("创建微信媒体目录失败: " + e.getMessage(), e);
        }
        return dir;
    }

    private Path accountFile() {
        return root.resolve("accounts").resolve("latest.json");
    }
}
