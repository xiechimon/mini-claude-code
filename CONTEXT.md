# Nano Agent

一个通过逐课实现最小 coding-agent harness 来学习 Agent 构造的单一上下文。这里统一描述课程讨论和代码评审使用的领域语言。

## Language

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

**Tool Definition**:
随每次模型请求发出的工具声明，由工具名、描述和 input schema 组成；与 Tool Handler 是两份互不校验的独立数据。
_Avoid_: Tool Schema、Tool Spec

**Tool Call**:
模型消息中要求 harness 调用某个工具的结构化内容块，包含调用 ID、工具名和输入。
_Avoid_: Command、Function Call

**Tool Result**:
harness 执行一次 Tool Call 后产生并以调用 ID 关联回去的结构化内容块。
_Avoid_: Tool Output、Command Result

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
