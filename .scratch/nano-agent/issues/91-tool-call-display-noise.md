# 91 消除工具调用显示噪音

Type: task

Status: resolved

> 非课次票。编号从 90 起，避开 01–17 的课次号段。

## 问题

当前 bash 工具调用时，终端显示两处噪音：

1. **`ToolUseDelta` 逐字打印原始 JSON 片段**：`{"command":"cd /Users/...` 逐段输出，用户看到的是模型内部的 JSON 串，不是给人看的
2. **输出截断到 200 codepoint**：`permit()` 里 `prefixByCodePoint(output, 200)` 把多行命令输出截断

## 根因

`AgentLoop.receive()` 第 166 行：

```java
case ModelEvent.ToolUseDelta delta -> writeText(delta.partialJson());
```

`ToolUseDelta` 携带的是 SDK 的 `input_json_delta` 原始 JSON 碎片（`{"command":"cd ...`），直接逐字输出到终端，用户看到的是不可读的 JSON 串。

## 方案

### 改动范围

仅 `AgentLoop.java` 一个文件，三个改动点：

### 1. `receive()` — 静默累积 ToolUseDelta，不打印

- 删除 `case ModelEvent.ToolUseDelta delta -> writeText(delta.partialJson());`
- 添加默认 case 吃掉未处理事件（`ToolUseDelta` 落在这里，静默丢弃）

不需要累积 JSON——`respond()` 里拿到的 `ToolUseBlock._input()` 已经是完整解析好的 `JsonValue`，不需要从碎片重建。

### 2. `respond()` — 在 `permit()` 之前格式化显示参数

```java
// 工具名已在 ToolUseStart 中显示，这里追加格式化参数
displayToolInput(toolUse);
String output = permit(toolUse);
```

新增私有方法 `displayToolInput(ToolUseBlock)`：
- 将 `toolUse._input()` 转为 `Map<String, Object>`
- 每个 key-value 显示为 `  key: value`，暗色样式
- 不截断，让终端自行换行

### 3. `permit()` — 去掉输出截断

```java
// 改前
writeLine(prefixByCodePoint(output, MAX_PREVIEW_CODE_POINTS));

// 改后
writeLine(output);
```

`BashTool` 已截断到 50,000 codepoint，`permit()` 再截到 200 是多余的。非 bash 工具（read/write/edit/glob）输出很短，200 截断也没意义。

### 预期效果

改前：
```
> bash
{"command":"cd /Users/xmon/Code/IdeaProjects/mini-claude-code && mvn -q test 2>&1 | tail -15"}
8月 17, 2026 3:49:13 下午 org.junit.jupiter.engine.extension.TempDirectory$CloseablePath$1 warnAboutLinkWithTargetOutsideTempDir
警告: Deleting symbolic link from location inside of temp dir (/var/folders/
```

改后：
```
> bash
  command: cd /Users/xmon/Code/IdeaProjects/mini-claude-code && mvn -q test 2>&1 | tail -15
8月 17, 2026 3:49:13 下午 org.junit.jupiter.engine.extension.TempDirectory$CloseablePath$1 warnAboutLinkWithTargetOutsideTempDir
警告: Deleting symbolic link from location inside of temp dir (/var/folders/...
[完整输出，不截断]
```

## 不变的部分

- `ToolUseStart` 打印 `> bash`（黄色）不动
- `ThinkingDelta` 显示不动
- `TextDelta` markdown 流式渲染不动
- `permit()` 的权限拒绝显示不动