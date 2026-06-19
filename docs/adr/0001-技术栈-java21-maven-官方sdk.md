# ADR-0001：技术栈选用 Java 21 + Maven + 官方 anthropic-java SDK

- 状态：已接受
- 日期：2026-08-09

## 背景

本项目通过手写一个 Java 版简易 Claude Code 来学习 coding agent 的构造，参考课程 <https://learn.shareai.run/zh>（Python
实现，20 课递进）。

课程的核心数据结构是一个 **sum type 流**：

- API 返回的 content block 只可能是 `text` / `tool_use` / `thinking` 之一
- `stop_reason` 只可能是 `end_turn` / `tool_use` / `max_tokens` 之一

Python 用 dict 承载，靠 `block["type"] == "tool_use"` 做字符串判别——类型信息只活在字符串里，漏一个分支要到运行时才暴露。整个
agent loop 就是在对这些变体反复分派，因此 **如何建模这个 sum type 决定了整个项目的代码质量**。

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
- 项目通过 Anthropic 兼容端点运行，必须显式提供 `anthropic.baseUrl` 或 `ANTHROPIC_BASE_URL`。缺失时启动失败，禁止静默回退到
  SDK 默认的 Anthropic 官方端点，以免 shell、IDE 等启动环境差异被远端鉴权错误掩盖。
- content block 的具体建模方案（sealed 层级如何划分、与 SDK 自带类型的边界在哪）尚未决定，留待专门审议后另立 ADR。本 ADR
  只锁定语言版本，不锁定建模方案。
