package com.xmon.nanoagent;

import com.anthropic.core.JsonValue;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 读取工作区内的文本文件
 */
final class ReadFileTool implements ToolHandler {

    private final Workspace workspace;

    /**
     * 创建读取文件工具
     *
     * @param workspace 路径边界
     */
    ReadFileTool(Workspace workspace) {
        this.workspace = Objects.requireNonNull(workspace);
    }

    /**
     * 读取文件并按行重组
     *
     * @param input 模型给出的工具输入
     * @return 文件内容，失败时以 {@code Error:} 开头
     */
    @Override
    public String execute(JsonValue input) {
        // 解码在错误边界之外：输入形状错误必须直接暴露，而不是变成一条 Tool Result。
        ReadFileInput decoded = input.convert(ReadFileInput.class);
        try {
            List<String> lines = Files
                    .readString(workspace.resolve(decoded.path()), StandardCharsets.UTF_8)
                    .lines()
                    .toList();
            return truncateAndJoin(lines, decoded.limit());
        } catch (IOException | RuntimeException failure) {
            return "Error: " + failure;
        }
    }

    /**
     * 按保留行数截断后以换行连接
     *
     * <p>{@code limit} 为 {@code null}、0 或不小于总行数时既不截断也不追加标记；为负数时等价于去掉末尾
     * {@code -limit} 行，而标记里的剩余行数仍按 {@code 总行数 - limit} 计算，与课程源码的算式一致。
     *
     * @param lines 文件的全部行
     * @param limit 保留行数，可为 {@code null}
     * @return 重组后的文本，发生截断时末行为 {@code ... (N more lines)}
     */
    private static String truncateAndJoin(List<String> lines, Integer limit) {
        if (limit == null || limit == 0 || limit >= lines.size()) {
            return String.join("\n", lines);
        }
        // 负数保留行数在课程源码里等价于去掉末尾若干行，而标记里的剩余行数仍按 size - limit 计算。
        int kept = limit > 0 ? limit : Math.max(0, lines.size() + limit);
        List<String> truncated = new ArrayList<>(lines.subList(0, kept));
        truncated.add("... (" + (lines.size() - limit) + " more lines)");
        return String.join("\n", truncated);
    }

    /**
     * 接收模型生成的读取文件工具输入
     *
     * @param path 文件路径
     * @param limit 保留行数，缺省表示不截断
     */
    private record ReadFileInput(
            @JsonProperty("path") String path,
            @JsonProperty("limit") Integer limit) {

        /**
         * 校验工具输入
         *
         * @param path 文件路径
         * @param limit 保留行数
         */
        private ReadFileInput {
            Objects.requireNonNull(path, "path");
        }
    }
}
