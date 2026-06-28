package com.xmon.nanoagent;

import com.anthropic.core.JsonValue;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.PatternSyntaxException;

/**
 * 按 glob 模式查找工作区内的文件
 *
 * <p>不用 JDK 的 {@code glob:} PathMatcher 直接匹配整条路径：它的 {@code **} 跨目录、{@code *} 匹配隐藏文件，
 * 与课程基准的非递归 glob 语义相反。这里按 {@code /} 分段逐层匹配，每段只匹配单个名字，
 * {@code **} 因此退化为单层。模式本身不受工作区约束，包含性对逐条结果判定。
 */
final class GlobTool implements ToolHandler {

    private static final String NO_MATCHES = "(no matches)";

    private final Workspace workspace;

    /**
     * 创建查找文件工具
     *
     * @param workspace 路径边界
     */
    GlobTool(Workspace workspace) {
        this.workspace = Objects.requireNonNull(workspace);
    }

    /**
     * 查找匹配模式的文件
     *
     * @param input 模型给出的工具输入
     * @return 以换行连接的相对路径，无匹配时为 {@code (no matches)}，失败时以 {@code Error:} 开头
     */
    @Override
    public String execute(JsonValue input) {
        // 解码在错误边界之外：输入形状错误必须直接暴露，而不是变成一条 Tool Result。
        GlobInput decoded = input.convert(GlobInput.class);
        try {
            List<String> inside = new ArrayList<>();
            for (String match : match(decoded.pattern())) {
                if (workspace.contains(match)) {
                    inside.add(match);
                }
            }
            return inside.isEmpty() ? NO_MATCHES : String.join("\n", inside);
        } catch (IOException | RuntimeException failure) {
            return "Error: " + failure;
        }
    }

    /**
     * 按模式枚举匹配项
     *
     * @param pattern glob 模式
     * @return 相对工作区根目录的路径，模式为绝对路径时结果也是绝对路径
     * @throws IOException 读取目录失败
     */
    private List<String> match(String pattern) throws IOException {
        boolean absolute = pattern.startsWith("/");
        Path base = absolute ? workspace.root().getRoot() : workspace.root();
        List<String> segments = List.of((absolute ? pattern.substring(1) : pattern).split("/", -1));
        List<String> matches = new ArrayList<>();
        try {
            expand(base, absolute ? "/" : "", segments, 0, matches);
        } catch (PatternSyntaxException invalidPattern) {
            // 课程基准的 glob 对非法模式返回空结果而不是报错。
            return List.of();
        }
        return matches;
    }

    /**
     * 逐段展开模式
     *
     * @param directory 当前所在目录
     * @param accumulated 已匹配的路径前缀
     * @param segments 按 {@code /} 拆分的模式段
     * @param index 当前模式段下标
     * @param matches 收集匹配项的列表
     * @throws IOException 读取目录失败
     */
    private void expand(
            Path directory,
            String accumulated,
            List<String> segments,
            int index,
            List<String> matches) throws IOException {
        String segment = segments.get(index);
        boolean last = index == segments.size() - 1;

        if (segment.isEmpty()) {
            if (!last) {
                expand(directory, accumulated, segments, index + 1, matches);
            } else if (!accumulated.isEmpty() && Files.isDirectory(directory)) {
                matches.add(child(accumulated, ""));
            }
            return;
        }

        if (!hasMagic(segment)) {
            Path candidate = directory.resolve(segment);
            String path = child(accumulated, segment);
            if (last) {
                if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    matches.add(path);
                }
            } else if (Files.isDirectory(candidate)) {
                expand(candidate, path, segments, index + 1, matches);
            }
            return;
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + segment);
        boolean includesHidden = segment.startsWith(".");
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!includesHidden && name.startsWith(".")) {
                    continue;
                }
                if (!matcher.matches(entry.getFileName())) {
                    continue;
                }
                String path = child(accumulated, name);
                if (last) {
                    matches.add(path);
                } else if (Files.isDirectory(entry)) {
                    expand(entry, path, segments, index + 1, matches);
                }
            }
        } catch (NoSuchFileException | NotDirectoryException unreadable) {
            // 模式中间段指向不存在的项或非目录时，课程基准的 glob 同样得到空结果。
        }
    }

    /**
     * 拼接路径前缀与名字
     *
     * @param accumulated 已匹配的路径前缀
     * @param name 当前名字，空串表示模式以斜杠结尾
     * @return 拼接后的路径
     */
    private static String child(String accumulated, String name) {
        if (accumulated.isEmpty()) {
            return name;
        }
        if (accumulated.endsWith("/")) {
            return accumulated + name;
        }
        return accumulated + "/" + name;
    }

    /**
     * 判断模式段是否含通配符
     *
     * @param segment 模式段
     * @return 含 {@code *}、{@code ?} 或 {@code [} 时为 {@code true}
     */
    private static boolean hasMagic(String segment) {
        return segment.indexOf('*') >= 0 || segment.indexOf('?') >= 0 || segment.indexOf('[') >= 0;
    }

    /**
     * 接收模型生成的查找文件工具输入
     *
     * @param pattern glob 模式
     */
    private record GlobInput(@JsonProperty("pattern") String pattern) {

        /**
         * 校验工具输入
         *
         * @param pattern glob 模式
         */
        private GlobInput {
            Objects.requireNonNull(pattern, "pattern");
        }
    }
}
