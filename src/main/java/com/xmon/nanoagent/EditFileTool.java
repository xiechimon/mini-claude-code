package com.xmon.nanoagent;

import com.anthropic.core.JsonValue;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 替换工作区内文本文件中首次出现的精确文本
 */
final class EditFileTool implements ToolHandler {

    private final Workspace workspace;

    /**
     * 创建编辑文件工具
     *
     * @param workspace 路径边界
     */
    EditFileTool(Workspace workspace) {
        this.workspace = Objects.requireNonNull(workspace);
    }

    /**
     * 替换首次出现的文本
     *
     * @param input 模型给出的工具输入
     * @return 编辑结果，未命中或失败时以 {@code Error:} 开头
     */
    @Override
    public String execute(JsonValue input) {
        // 解码在错误边界之外：输入形状错误必须直接暴露，而不是变成一条 Tool Result。
        EditFileInput decoded = input.convert(EditFileInput.class);
        try {
            Path file = workspace.resolve(decoded.path());
            String text = Files.readString(file, StandardCharsets.UTF_8);
            int start = text.indexOf(decoded.oldText());
            if (start < 0) {
                // 未命中是普通返回值而非异常，因此不经过下面的错误边界。
                return "Error: text not found in " + decoded.path();
            }
            String edited = text.substring(0, start)
                    + decoded.newText()
                    + text.substring(start + decoded.oldText().length());
            Files.writeString(file, edited, StandardCharsets.UTF_8);
            return "Edited " + decoded.path();
        } catch (IOException | RuntimeException failure) {
            return "Error: " + failure;
        }
    }

    /**
     * 接收模型生成的编辑文件工具输入
     *
     * @param path 文件路径
     * @param oldText 待替换的精确文本，空串表示在文件开头插入
     * @param newText 替换后的文本
     */
    private record EditFileInput(
            @JsonProperty("path") String path,
            @JsonProperty("old_text") String oldText,
            @JsonProperty("new_text") String newText) {

        /**
         * 校验工具输入
         *
         * @param path 文件路径
         * @param oldText 待替换的精确文本
         * @param newText 替换后的文本
         */
        private EditFileInput {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(oldText, "old_text");
            Objects.requireNonNull(newText, "new_text");
        }
    }
}
