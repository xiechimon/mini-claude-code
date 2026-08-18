# Mini Claude Code · Project Memory

## 项目定位

- 这是我的 Java Agent CLI 学习项目，用来拆解 ReAct、工具调用、上下文、MCP 和终端交互。
- 项目非商业化；名称受 Claude Code 启发，但代码和架构独立实现。
- 目标是“能运行、能解释、能验证”，不为了功能数量牺牲可读性。

## 常用命令

- 调试：`mvn clean compile exec:java -Dexec.mainClass="com.miniclaudecode.cli.Main"`
- 打包：`mvn clean package`
- 常规回归：`mvn test -Pquick`
- 终端回归：`mvn test -Pphase16-smoke`
- 聚焦测试：`mvn test -Dtest=XxxTest -DskipTests=false`

## 架构

- ReAct、Plan-and-Execute、Multi-Agent 共享 `ToolRegistry`、`MemoryManager` 和 `SnapshotService`。
- 精确代码定位优先 glob / grep / read；`search_code` 只做 RAG 语义辅助。
- Prompt 由 `PromptAssembler` 分层组装，模板位于 `src/main/resources/prompts/`。
- MCP、Skill、Browser 继续经过现有 HITL、策略和审计边界。

## 稳定约定

- 先复现，再做最小修改，并用与改动最接近的测试证明。
- 行为变化同步 `AGENTS.md` 和 `README.md`；状态变化才更新 `ROADMAP.md`。
- CLI 命令、工具、Provider、Memory、MCP 和终端改动都要检查对应的联动文件。
- 长期记忆只保存用户明确要求保留的稳定事实；一次性任务不写入记忆。
- 交互输出走 `Renderer.stream()`；提交输入以 `>` 独立行回写，不添加整行背景。

## 边界

- 不提交 `.env`、真实 API Key 和 `target/`。
- 不把策略层描述成容器或 VM 沙箱。
- 不把 Roadmap 的候选能力写成已经交付。
- 不为单一执行模式创建无法复用的旁路能力。
