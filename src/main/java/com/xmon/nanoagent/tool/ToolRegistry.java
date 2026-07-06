package com.xmon.nanoagent.tool;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.xmon.nanoagent.host.Workspace;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 持有工具声明表与实现表的组装点
 *
 * <p>两张表互不校验：声明表决定模型看到什么，实现表决定运行时执行什么，新增一个工具就是各加一条。
 * 保持两份数据而不聚合成单一工具对象的理由见 ADR-0003。
 */
public final class ToolRegistry {

    private static final JsonValue STRING_TYPE = JsonValue.from(Map.of("type", "string"));
    private static final JsonValue INTEGER_TYPE = JsonValue.from(Map.of("type", "integer"));

    private final List<Tool> definitions;
    private final Map<String, ToolHandler> handlers;

    /**
     * 创建工具注册表
     *
     * @param bashTool 执行 shell 命令的工具
     * @param workspace 文件类工具共用的路径边界
     */
    public ToolRegistry(BashTool bashTool, Workspace workspace) {
        Objects.requireNonNull(bashTool);
        Objects.requireNonNull(workspace);
        this.definitions = List.of(
                tool("bash", "Run a shell command.",
                        Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("command", STRING_TYPE)
                                .build(),
                        List.of("command")),
                tool("read_file", "Read file contents.",
                        Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("path", STRING_TYPE)
                                .putAdditionalProperty("limit", INTEGER_TYPE)
                                .build(),
                        List.of("path")),
                tool("write_file", "Write content to a file.",
                        Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("path", STRING_TYPE)
                                .putAdditionalProperty("content", STRING_TYPE)
                                .build(),
                        List.of("path", "content")),
                tool("edit_file", "Replace exact text in a file once.",
                        Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("path", STRING_TYPE)
                                .putAdditionalProperty("old_text", STRING_TYPE)
                                .putAdditionalProperty("new_text", STRING_TYPE)
                                .build(),
                        List.of("path", "old_text", "new_text")),
                tool("glob", "Find files matching a glob pattern.",
                        Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("pattern", STRING_TYPE)
                                .build(),
                        List.of("pattern")));
        this.handlers = Map.of(
                "bash", bashTool,
                "read_file", new ReadFileTool(workspace),
                "write_file", new WriteFileTool(workspace),
                "edit_file", new EditFileTool(workspace),
                "glob", new GlobTool(workspace));
    }

    /**
     * 返回随每次模型请求发出的工具声明
     *
     * @return 按声明顺序排列的工具声明
     */
    public List<Tool> definitions() {
        return definitions;
    }

    /**
     * 按工具名查找工具实现
     *
     * @param name 模型给出的工具名
     * @return 对应的工具实现，未注册时为空
     */
    public Optional<ToolHandler> handler(String name) {
        return Optional.ofNullable(handlers.get(name));
    }

    /**
     * 创建一条工具声明
     *
     * @param name 工具名
     * @param description 工具描述
     * @param properties input schema 的属性
     * @param required input schema 的必填属性名
     * @return 工具声明
     */
    private static Tool tool(
            String name,
            String description,
            Tool.InputSchema.Properties properties,
            List<String> required) {
        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(properties)
                        .required(required)
                        .build())
                .build();
    }
}
