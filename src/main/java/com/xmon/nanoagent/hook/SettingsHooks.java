package com.xmon.nanoagent.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmon.nanoagent.host.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 从工作区的 {@code .claude/settings.json} 读出 hook 配置
 *
 * <p>照抄契约的三层嵌套：<b>事件名 → matcher 组 → handler 数组</b>。
 *
 * <pre>{@code
 * {
 *   "hooks": {
 *     "PreToolUse": [
 *       {
 *         "matcher": "bash",
 *         "timeout": 10,
 *         "hooks": [{"type": "command", "command": ".claude/hooks/audit.sh"}]
 *       }
 *     ]
 *   }
 * }
 * }</pre>
 *
 * <p>只读<b>一个</b>文件。契约的另外几个来源各自需要额外机制，都不在本课范围：{@code ~/.claude/settings.json}
 * 与 {@code .claude/settings.local.json} 需要跨层合并规则（契约规定合并而非覆盖，且托管层不可被外层禁用）；
 * plugin 的 {@code hooks/hooks.json} 需要插件系统（s14）；skill 与 subagent frontmatter 需要 skill 加载（s07）
 * 与子 Agent（s06）。
 *
 * <p><b>配置错误一律抛出，不静默跳过。</b>未知事件名、未实现事件、未知 handler 类型、错误的正则——
 * 全部在启动读取时炸掉。一条被静默跳过的 hook 与一条根本不存在的 hook 在运行时无从分辨，而 hook 的
 * 典型用途正是当闸门：闸门没装上必须立刻知道。
 */
public final class SettingsHooks {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 工作区内的配置文件相对路径。 */
    private static final Path SETTINGS_PATH = Path.of(".claude", "settings.json");

    /**
     * 禁止实例化
     */
    private SettingsHooks() {
    }

    /**
     * 读取工作区的 hook 配置
     *
     * @param workspace 工作区，配置文件位置以它的根目录为基准
     * @return 事件到 matcher 组的映射，配置文件不存在或没有 {@code hooks} 段时为空映射
     * @throws IOException 配置文件存在但读取失败
     * @throws IllegalArgumentException 配置结构或取值非法
     * @throws UnsupportedOperationException 配置里的事件尚未接上触发点
     */
    public static Map<HookEvent, List<HookMatcher>> read(Workspace workspace) throws IOException {
        Path settings = Objects.requireNonNull(workspace, "workspace").root().resolve(SETTINGS_PATH);
        if (!Files.isRegularFile(settings)) {
            return Map.of();
        }
        JsonNode root = MAPPER.readTree(Files.readString(settings));
        JsonNode hooks = root.get("hooks");
        if (hooks == null || hooks.isNull()) {
            return Map.of();
        }
        if (!hooks.isObject()) {
            throw new IllegalArgumentException(
                    "hooks must be an object in " + settings + ", found " + hooks.getNodeType());
        }

        Map<HookEvent, List<HookMatcher>> parsed = new EnumMap<>(HookEvent.class);
        hooks.properties().forEach(entry -> {
            HookEvent event = HookEvent.fromContractValue(entry.getKey())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown hook event in " + settings + ": " + entry.getKey()));
            event.requireImplemented();
            parsed.put(event, readMatchers(event, entry.getValue(), settings));
        });
        return Map.copyOf(parsed);
    }

    /**
     * 读取一个事件下的 matcher 组数组
     *
     * @param event 所属事件
     * @param node matcher 组数组节点
     * @param settings 配置文件路径，用于错误信息
     * @return matcher 组列表
     * @throws IllegalArgumentException 节点不是数组或内容非法
     */
    private static List<HookMatcher> readMatchers(HookEvent event, JsonNode node, Path settings) {
        if (!node.isArray()) {
            throw new IllegalArgumentException("hooks." + event.contractValue()
                    + " must be an array in " + settings + ", found " + node.getNodeType());
        }
        List<HookMatcher> matchers = new ArrayList<>();
        for (JsonNode group : node) {
            matchers.add(readMatcher(event, group, settings));
        }
        return List.copyOf(matchers);
    }

    /**
     * 读取一个 matcher 组
     *
     * @param event 所属事件
     * @param node matcher 组节点
     * @param settings 配置文件路径，用于错误信息
     * @return matcher 组
     * @throws IllegalArgumentException 节点结构非法
     */
    private static HookMatcher readMatcher(HookEvent event, JsonNode node, Path settings) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("each entry of hooks." + event.contractValue()
                    + " must be an object in " + settings + ", found " + node.getNodeType());
        }
        JsonNode handlers = node.get("hooks");
        if (handlers == null || !handlers.isArray() || handlers.isEmpty()) {
            throw new IllegalArgumentException("each entry of hooks." + event.contractValue()
                    + " needs a non-empty \"hooks\" array in " + settings);
        }
        Optional<Duration> groupTimeout = seconds(node.get("timeout"));
        List<HookHandler> parsed = new ArrayList<>();
        for (JsonNode handler : handlers) {
            parsed.add(readHandler(event, handler, groupTimeout, settings));
        }
        return new HookMatcher(text(node.get("matcher")).orElse(null), parsed, groupTimeout);
    }

    /**
     * 读取一个 handler
     *
     * @param event 所属事件，决定 command 型的默认超时
     * @param node handler 节点
     * @param groupTimeout 组级超时
     * @param settings 配置文件路径，用于错误信息
     * @return handler
     * @throws IllegalArgumentException 节点结构非法或类型未知
     */
    private static HookHandler readHandler(
            HookEvent event, JsonNode node, Optional<Duration> groupTimeout, Path settings) {
        if (!node.isObject()) {
            throw new IllegalArgumentException(
                    "each hook handler must be an object in " + settings);
        }
        String type = text(node.get("type")).orElseThrow(() -> new IllegalArgumentException(
                "each hook handler needs a \"type\" in " + settings));
        return switch (type) {
            case "command" -> new HookHandler.Command(
                    text(node.get("command")).orElseThrow(() -> new IllegalArgumentException(
                            "a command hook needs a \"command\" in " + settings)),
                    seconds(node.get("timeout"))
                            .or(() -> groupTimeout)
                            .orElseGet(() -> defaultTimeout(event)));
            case "http", "mcp_tool", "prompt", "agent" -> throw new UnsupportedOperationException(
                    "hook handler type not implemented: " + type + " (in " + settings + ")");
            default -> throw new IllegalArgumentException(
                    "unknown hook handler type in " + settings + ": " + type);
        };
    }

    /**
     * 返回 command 型 handler 的契约默认超时
     *
     * @param event 所属事件
     * @return 默认超时
     */
    private static Duration defaultTimeout(HookEvent event) {
        return event == HookEvent.USER_PROMPT_SUBMIT
                ? HookHandler.USER_PROMPT_SUBMIT_TIMEOUT
                : HookHandler.DEFAULT_TIMEOUT;
    }

    /**
     * 读取可选文本字段
     *
     * @param node 字段节点，可为 {@code null}
     * @return 字段值，缺失或非文本时为空
     */
    private static Optional<String> text(JsonNode node) {
        return node != null && node.isTextual() ? Optional.of(node.asText()) : Optional.empty();
    }

    /**
     * 读取以秒为单位的可选超时字段
     *
     * @param node 字段节点，可为 {@code null}
     * @return 超时，缺失或非数字时为空
     * @throws IllegalArgumentException 超时不是正数
     */
    private static Optional<Duration> seconds(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return Optional.empty();
        }
        long value = node.asLong();
        if (value <= 0) {
            throw new IllegalArgumentException("hook timeout must be positive, found " + value);
        }
        return Optional.of(Duration.ofSeconds(value));
    }
}
