# Turn 协议学习资源

## Knowledge

- [OpenAI Docs: Codex App Server](https://learn.chatgpt.com/docs/app-server)
  官方协议文档。用于核对 Thread / Turn / Item 定义、`turn/start` 生命周期、流式通知、steer 和 interrupt 语义。
- [`RuntimeApiServer.java`](../../src/main/java/com/miniclaudecode/runtime/api/RuntimeApiServer.java)
  Mini Claude Code Runtime API 的实际协议入口。用于确认 HTTP 端点、异步执行和 SSE 事件行为。
- [`RuntimeThreadStore.java`](../../src/main/java/com/miniclaudecode/runtime/api/RuntimeThreadStore.java)
  Thread 与事件的 SQLite 持久化实现。用于判断项目究竟保存了会话上下文，还是只保存事件记录。
- [`SnapshotService.java`](../../src/main/java/com/miniclaudecode/snapshot/SnapshotService.java)
  交互路径的 turn 执行边界。用于理解 pre-turn / post-turn 快照以及失败时仍执行收尾的保证。
- [`Agent.java`](../../src/main/java/com/miniclaudecode/agent/Agent.java)
  ReAct turn 内部的循环。用于证明一个 turn 可以包含多次 LLM 调用和多批工具调用。
- [第 20 期：异步后台任务 + Runtime API](../phase-20-runtime-api.md)
  项目设计记录。用于了解 Runtime API 被刻意限定为事件回放式 SSE MVP，而非完整上游协议。
- [第 18 期：Git Side-History 快照与回滚](../phase-18-side-history-snapshot.md)
  项目设计记录。用于理解为什么 ReAct、Plan、Team 都以整个用户任务作为快照 turn。

## Wisdom (Communities)

当前课程先以官方协议和本仓库源码为准，尚未筛选适合讨论 App Server 客户端设计的高信噪社区。

## Gaps

- Mini Claude Code 尚无统一的 `Turn` 领域类型或协议规范文档
- Runtime API 与交互式快照 turn 之间的标识、上下文和生命周期尚未打通
