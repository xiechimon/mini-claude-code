# Nano Agent

一个通过逐课实现最小 coding-agent harness 来学习 Agent 构造的单一上下文。这里统一描述课程讨论和代码评审使用的领域语言。

术语分两簇。**契约术语**来自 Claude Code 与 Anthropic API 的公开契约，名称和取值照抄，改了行为就对不上；**内部术语**是本项目
自己的实现概念，契约里没有对应物，用词由项目自己定。判断一个词能不能改，先看它在哪一簇。

## 契约术语

照抄公开契约，不可改写。括号内为契约中的原始类型名与来源。

**Tool Definition**（Messages API 的 `tools` 元素）:
随每次模型请求发出的工具声明，由工具名、描述和 input schema 组成；与 Tool Handler 是两份互不校验的独立数据。
_Avoid_: Tool Schema、Tool Spec

**Tool Call**（Messages API 的 `tool_use` 内容块）:
模型消息中要求 harness 调用某个工具的结构化内容块，包含调用 ID、工具名和输入。
_Avoid_: Command、Function Call

**Tool Result**（Messages API 的 `tool_result` 内容块）:
harness 执行一次 Tool Call 后产生并以调用 ID 关联回去的结构化内容块。
_Avoid_: Tool Output、Command Result

**Permission Mode**（`PermissionMode`，`sdk.d.ts`）:
决定整个会话默认如何处理权限请求的模式。取值恰为 `default`、`acceptEdits`、`bypassPermissions`、`plan`、`dontAsk`、`auto`。
_Avoid_: 权限级别、安全模式

**Permission Behavior**（`PermissionBehavior`，`sdk.d.ts`）:
单次权限判定的三种结果之一：`allow`、`deny`、`ask`。`ask` 表示交由用户裁决，本身不是终态。
_Avoid_: 权限决策、Permission Decision

**Permission Result**（`PermissionResult`，`sdk.d.ts`）:
一次权限判定的完整结果，按 `behavior` 判别的联合类型。`allow` 分支可携带改写后的工具输入与权限更新，`deny` 分支**必须**
携带 `message`。
_Avoid_: 权限返回值、Permission Outcome

## 内部术语

本项目的实现概念，公开契约中没有对应物。

**Agent Loop**:
反复请求模型、执行模型发起的工具调用、把工具结果加入会话，直到模型不再请求工具的控制循环。
_Avoid_: 主循环、LLM 循环

**Conversation History**:
当前进程内按顺序保存的用户消息、模型消息和工具结果；每次模型请求都携带完整历史。
_Avoid_: Context、Memory、Session

**Turn**:
一次原始用户输入以及由它触发的全部模型请求、Tool Call 和 Tool Result，终止于模型返回非工具调用响应。
_Avoid_: Query、Request、Round

**Model Client**:
Agent Loop 用来提交 Conversation History 并取得下一条模型消息的协作者。
_Avoid_: LLM Service、Anthropic Client

**Tool Handler**:
按 Tool Call 中的工具名查表分发到的工具实现，接收模型给出的原始输入并产生文本 Tool Result。工具名查不到时不构成错误。
_Avoid_: Tool Executor、Tool Function

**Bash Tool**:
在固定工作目录中执行模型给出的 POSIX shell 命令并产生文本 Tool Result 的 Tool Handler；不受 Workspace 约束。
_Avoid_: Shell Runner、Command Executor

**Workspace**:
以启动时捕获的工作目录为根的路径边界；负责把模型给出的原始路径解析成区内绝对路径，并判定原始路径解析后是否落在区内。解析包含符号链接。
_Avoid_: Sandbox、Root Directory

**Effective Environment**:
由继承的进程变量与 `.env` 声明合并而成、供 agent 集成和所启动工具共同使用的配置视图；同名项以 `.env` 为准。
_Avoid_: Process Environment、System Environment
