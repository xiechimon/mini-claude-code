# Mission: 理解 Mini Claude Code 的 Turn 协议

## Why
通过追踪一次用户输入在 Mini Claude Code 中的完整生命周期，建立阅读和判断项目架构的能力；同时用 Codex App Server 的正式协议作为参照，理解当前实现为什么这样分层、还缺少哪些协议能力。

## Success looks like
- 能准确区分 Thread、Turn、Item、Message 和单次 LLM 调用
- 能从 CLI/TUI 输入追到 `SnapshotService.runTurn()`，再追到 ReAct、Plan 或 Team 执行
- 能说清 Runtime API 的 thread / turn / event 流程及其当前 MVP 边界
- 面对流式事件、中断或上下文持久化需求时，能判断应该修改哪一层

## Constraints
- 以当前代码行为为准，设计文档只作为意图和历史背景
- 采用短课、源码路径和检索练习，避免一次灌输完整协议表
- 同时覆盖本项目实现和 Codex App Server 官方协议

## Out of scope
- 穷举 Codex App Server 的所有 JSON-RPC 方法和 Item 类型
- 立即把 Mini Claude Code 改造成完整的 Codex App Server 实现
