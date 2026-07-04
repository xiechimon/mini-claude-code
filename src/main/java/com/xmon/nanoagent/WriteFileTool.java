package com.xmon.nanoagent;

import com.anthropic.core.JsonValue;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 覆盖写入工作区内的文本文件
 */
final class WriteFileTool implements ToolHandler {

    private final Workspace workspace;

    /**
     * 创建写入文件工具
     *
     * @param workspace 路径边界
     */
    WriteFileTool(Workspace workspace) {
        this.workspace = Objects.requireNonNull(workspace);
    }

    /**
     * 递归创建父目录后整文件覆盖写入
     *
     * @param input 模型给出的工具输入
     * @return 写入结果，失败时以 {@code Error:} 开头
     */
    @Override
    public String execute(JsonValue input) {
        // 解码在错误边界之外：输入形状错误必须直接暴露，而不是变成一条 Tool Result。
        WriteFileInput decoded = input.convert(WriteFileInput.class);
        try {
            Path file = workspace.resolve(decoded.path());
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, decoded.content(), StandardCharsets.UTF_8);
            // 课程源码的计数口径是字符数而非字节数，文案与实际字节数在多字节字符下并不一致。
            String content = decoded.content();
            return "Wrote " + content.codePointCount(0, content.length()) + " bytes to " + decoded.path();
        } catch (IOException | RuntimeException failure) {
            return "Error: " + failure;
        }
    }

    /**
     * 接收模型生成的写入文件工具输入
     *
     * @param path 文件路径
     * @param content 写入内容
     */
    private record WriteFileInput(
            @JsonProperty("path") String path,
            @JsonProperty("content") String content) {

        /**
         * 校验工具输入
         *
         * @param path 文件路径
         * @param content 写入内容
         */
        private WriteFileInput {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(content, "content");
        }
    }
}
