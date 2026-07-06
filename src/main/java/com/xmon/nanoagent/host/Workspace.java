package com.xmon.nanoagent.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 以工作目录为根的路径边界
 *
 * <p>包含性判定发生在符号链接解析之后，因此指向工作区外的链接同样会被识别为越界；判定同时允许路径
 * 尚不存在，以支持写入新文件。JDK 没有同时满足这两点的现成实现，解析步骤的取舍见 ADR-0004。
 *
 * <p>本类只解析和判定，不裁决。越界路径是否放行由 {@code PermissionGate} 决定——Claude Code 契约把
 * 越界访问建模成携带原因的权限请求，而非硬错误。
 */
public final class Workspace {

    /** 解析符号链接的最大跳数，用于在链接成环时终止解析。 */
    private static final int MAX_SYMBOLIC_LINK_HOPS = 40;

    private final Path root;

    /**
     * 创建工作区
     *
     * @param workingDirectory 工作目录
     * @throws IOException 工作目录不存在或无法解析
     */
    public Workspace(Path workingDirectory) throws IOException {
        // 根与被判定路径必须是同一种表示，否则 macOS 上 /var、/tmp 这类符号链接会让所有路径被判越界。
        this.root = Objects.requireNonNull(workingDirectory).toRealPath();
    }

    /**
     * 返回工作区根目录
     *
     * @return 已解析符号链接的绝对路径
     */
    public Path root() {
        return root;
    }

    /**
     * 判断路径是否落在工作区内
     *
     * @param rawPath 模型给出的原始路径
     * @return 解析后落在工作区内时为 {@code true}
     * @throws IOException 解析路径失败
     */
    public boolean contains(String rawPath) throws IOException {
        return resolve(rawPath).startsWith(root);
    }

    /**
     * 解析模型给出的路径，不判定包含性
     *
     * @param rawPath 模型给出的原始路径，绝对路径不与根目录拼接
     * @return 解析后的绝对路径，可能落在工作区外
     * @throws IOException 解析路径失败
     */
    public Path resolve(String rawPath) throws IOException {
        return resolveAllowingMissing(root.resolve(rawPath), MAX_SYMBOLIC_LINK_HOPS);
    }

    /**
     * 按 realpath 语义解析路径，允许路径尚不存在
     *
     * @param target 待解析的绝对路径
     * @param remainingHops 剩余可解析的符号链接跳数
     * @return 解析后的绝对路径
     * @throws IOException 解析路径失败
     */
    private static Path resolveAllowingMissing(Path target, int remainingHops) throws IOException {
        Path existing = target;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return target.normalize();
        }
        Path resolvedExisting = resolveExisting(existing, remainingHops);
        if (existing.equals(target)) {
            return resolvedExisting;
        }
        return resolvedExisting.resolve(existing.relativize(target)).normalize();
    }

    /**
     * 解析已存在的路径项
     *
     * @param existing 已存在的路径项，可能是断链或成环的符号链接
     * @param remainingHops 剩余可解析的符号链接跳数
     * @return 解析后的绝对路径
     * @throws IOException 解析路径失败或符号链接跳数耗尽
     */
    private static Path resolveExisting(Path existing, int remainingHops) throws IOException {
        try {
            return existing.toRealPath();
        } catch (IOException unresolved) {
            // toRealPath 拒绝断链与成环的符号链接，但断链符号链接仍可被写入并穿透工作区，
            // 因此手工读取链接目标后按同样语义继续解析。
            if (remainingHops <= 0 || !Files.isSymbolicLink(existing)) {
                throw unresolved;
            }
            Path parent = existing.getParent().toRealPath();
            return resolveAllowingMissing(
                    parent.resolve(Files.readSymbolicLink(existing)), remainingHops - 1);
        }
    }
}
