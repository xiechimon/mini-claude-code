# ADR-0001：技术栈选用 Java 21 + Maven + 官方 anthropic-java SDK

- 状态：已接受
- 日期：2026-08-09

## 背景

本项目通过手写一个 Java 版简易 Claude Code 来学习 coding agent 的构造，参考课程 <https://learn.shareai.run/zh>（Python
实现，20 课递进）。

课程的核心数据结构是一个 **sum type 流**。原决策时把它简化为：

- API 返回的 content block 只可能是 `text` / `tool_use` / `thinking` 之一
- `stop_reason` 只可能是 `end_turn` / `tool_use` / `max_tokens` 之一

Python 用 dict 承载，靠 `block["type"] == "tool_use"` 做字符串判别——类型信息只活在字符串里，漏一个分支要到运行时才暴露。

后续核查 `anthropic-java 2.53.0` 发现，SDK 已提供带未知变体处理的 content-block 联合类型和 stop-reason 包装，实际变体也多于上述三种。
因此 ADR-0002 已决定复用 SDK 协议类型，而不是为外部协议重复建立 sealed 层级。Java 21 仍作为项目下限：后续 harness 自有状态机可使用
record、sealed type 和穷尽 switch，虚拟线程也服务于后半课程的并发机制。

## 决策

### 1. Java 21 作为语言版本下限

治理上述 sum type 的四件工具，GA 版本不同：

| 特性                                   | GA 版本 |
|----------------------------------------|---------|
| `record`                               | 16      |
| `sealed interface`                     | 17      |
| pattern matching for switch（JEP 441） | **21**  |
| record patterns（JEP 440）             | **21**  |

**21 是四者齐备且全部转正的最低版本。** 缺任何一件，建模都会退化为 `instanceof` 链——编译器不再强制穷尽，等于把 Python
的病原样搬进 Java，丢掉本项目最值得学的一层。

次要理由：虚拟线程（JEP 444）同在 21 GA，课程后半段 s13 Background Tasks / s14 Cron / s15 Agent Teams 全是并发题，届时直接可用。

这不是 SDK 的要求——`anthropic-java` 支持低得多的版本。这是建模质量的要求。

### 2. Maven 作为构建工具

用户既有习惯。构建工具对本项目无技术影响，遵循用户偏好。

### 3. 官方 `com.anthropic:anthropic-java` SDK

不自行实现 HTTP 与 SSE 解析。协议细节（流式、重试、错误语义）不是本项目的学习目标，学习目标是 agent 的 **控制流**。自撸 HTTP
会用大量精力换取零学习收益。

## 影响

- 语言版本下限 21，上限不限（25 等更新 LTS 亦可）。
- 本机 `java` 命令为 Homebrew JDK 26，而 `JAVA_HOME` 指向 SDKMAN Zulu 21，Maven 运行在 21 上。 **统一用 `mvn` 执行，不使用裸
  `java` 命令**，避免编译期与运行期版本不一致。
- `ANTHROPIC_BASE_URL` 是可选配置：提供时使用 Anthropic 兼容端点，缺失时沿用 SDK 默认的 Anthropic 官方端点。这样与课程基线保持
  功能对等；具体端点仍应由启动环境明确管理，鉴权或端点配置错误直接暴露，不做 fallback。
- content block 与 SDK 类型的边界见 ADR-0002：外部协议复用 SDK 类型，模型调用处建立可替换接缝。
