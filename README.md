# Mini Claude Code

> 我用 Java 从零实现的本地 Agent CLI，也是我理解 Agent 架构的一块长期实验田。

这个项目关注的不是复刻某个现成产品，而是把 ReAct、工具调用、上下文管理、MCP、终端交互和安全边界真正写进一个可以运行的程序里。代码优先保持可读、可调试，适合边使用边拆解实现。

项目仅用于个人学习和技术交流，不做商业化。名称和交互方式受到 Claude Code 启发，但项目独立实现，与 Anthropic 无关。

## 我想研究什么

- 一个 Agent 如何在“思考—行动—观察”之间稳定循环
- 复杂任务如何拆成 DAG，并在执行前让人审阅
- 多轮对话、长期记忆和长上下文如何各司其职
- 本地工具、MCP、Skill 和浏览器能力如何共享同一套执行边界
- 终端 UI 如何兼顾流式输出、可编辑输入和稳定 transcript
- 在没有容器沙箱的前提下，如何把审批、路径限制、审计和回滚做清楚

## 现在可以做什么

| 方向     | 当前能力                                                             |
|----------|----------------------------------------------------------------------|
| 执行模式 | 默认 ReAct、`/plan` 规划执行、`/team` 多 Agent 协作                  |
| 代码理解 | 文件读写、glob、grep、命令执行、可选 RAG、Java 语法诊断              |
| 上下文   | 对话压缩、项目记忆 `MCC.md`、显式长期记忆、128k–1M 窗口预算          |
| 扩展     | MCP stdio/HTTP、resources、prompts、Skill 按需加载                   |
| 模型     | GLM、DeepSeek、Kimi、FreeLLMAPI                                      |
| 交互     | JLine inline UI、Lanterna、纯文本模式、图片输入、微信 iLink 文本通道 |
| 安全     | HITL、项目路径围栏、命令快速拒绝、JSONL 审计、Side-Git 快照          |
| 运行时   | 后台任务和仅监听本机的 Runtime API                                   |

路线图中的内容不等于已经交付。当前状态以代码、[AGENTS.md](AGENTS.md) 和 [ROADMAP.md](ROADMAP.md) 为准。

## 快速开始

### 环境

- JDK 17+
- Maven
- 至少一个受支持模型的 API Key
- 可选：`ripgrep`，用于更快的代码搜索
- 可选：Ollama + `nomic-embed-text`，用于本地 RAG

仓库不带 Maven Wrapper，开发和测试统一使用本机 `mvn`。

### 配置

```bash
cp .env.example .env
```

编辑 `.env`，至少填写一个 Key，例如：

```dotenv
GLM_API_KEY=your_key
# 或 DEEPSEEK_API_KEY / KIMI_API_KEY / FREELLMAPI_API_KEY
```

可由 `.env` 读取的配置见 [.env.example](.env.example)；Renderer、Embedding、LSP、Snapshot
等启动参数见 [Agent Reference](docs/agents-reference.md#配置与持久化)。`.env` 只保留在本地，不要提交真实密钥。

### 开发运行

```bash
mvn clean compile exec:java \
  -Dexec.mainClass="com.miniclaudecode.cli.Main"
```

### 打包运行

```bash
mvn clean package
java -jar target/mini-claude-code-1.0-SNAPSHOT.jar
```

`mvn clean package` 默认跳过测试，目的是快速产出可手工验收的 jar。

进入 CLI 后，可以先试：

```text
解释一下这个项目的执行入口
@README.md 帮我检查快速开始有没有过时
/plan 给 ToolRegistry 增加一个只读工具
/team 分析当前终端渲染链路
```

输入 `/` 后按 Tab，可以查看当前版本真正支持的命令。

## 三条执行路径

```text
普通输入 ───────────────► Agent (ReAct)
/plan <任务> ───────────► PlanExecuteAgent ─► DAG
/team <任务> ───────────► AgentOrchestrator ─► Planner / Worker / Reviewer
                                  │
                                  ▼
          ToolRegistry / MemoryManager / SnapshotService
                                  │
                                  ▼
             HITL / PathGuard / CommandGuard / AuditLog
```

三条路径共享工具、记忆和快照能力。新能力应优先接入共享层，而不是只在某一种模式里单独实现。

## 常用命令

| 场景         | 命令                                                  |
|--------------|-------------------------------------------------------|
| 模式         | `/plan [任务]`、`/team [任务]`、`/cancel`             |
| 模型与上下文 | `/model`、`/config`、`/context`、`/compact`           |
| 会话         | `/clear`、`/history clear`、`/export`、`/exit`        |
| 记忆         | `/save`、`/memory list/search/delete/clear`、`/init`  |
| 代码索引     | `/index`、`/search`、`/graph`                         |
| 扩展         | `/mcp`、`/skill`、`/browser`                          |
| 安全与回滚   | `/hitl`、`/policy`、`/audit`、`/snapshot`、`/restore` |
| 后台能力     | `/task`、`/wechat`                                    |

斜杠命令由 CLI 层解析；未识别的 `/xxx` 会直接报错，不会偷偷交给 Agent。

## 计划审阅

`/plan` 生成 DAG 之后会先停下等确认，不会直接执行：

| 按键     | 行为                                        |
|----------|---------------------------------------------|
| `Enter`  | 按当前计划执行                              |
| `Ctrl+O` | 展开完整计划                                |
| `ESC`    | 已展开时折叠回摘要，否则取消本次计划        |
| `I`      | 进入 `补充>` 提示符，输入补充要求后重新规划 |

终端不支持单键读取时降级为 `操作/补充>` 行输入：直接回车或 `y` / `run` 执行，`cancel` / `/cancel` 取消，`/view` 展开完整计划，其余文本一律当作补充要求重新规划。

## 记忆怎么分

Mini Claude Code 有三类容易混淆的状态：

- **对话历史**：当前会话真正发送给模型的消息，接近窗口上限时会压缩。
- **长期记忆**：只在 `/save` 或用户明确要求“记住”时保存，可查询和删除。
- **项目记忆**：仓库里的 `MCC.md`，启动时注入 system prompt，记录长期稳定的项目规则。

一次性任务、临时路径和当前轮指令不应该进入长期记忆或 `MCC.md`。

## MCP、Skill 与浏览器

- MCP 配置合并 `~/.mini-claude-code/mcp.json` 和项目级 `.mini-claude-code/mcp.json`。
- MCP 工具以 `mcp__{server}__{tool}` 注册，并继续经过审批与审计。
- MCP server 全程后台启动，`npx` 拉包或 Chrome 冷启动不会阻塞 Logo 和首个输入提示；用 `/mcp` 查看实时状态。
- Skill 只在需要时通过 `load_skill` 展开，避免把全部说明塞进 system prompt。
- 静态网页优先走 `web_fetch`；SPA、登录页或强交互页面再使用 Chrome DevTools MCP。
- `web_search` / `web_fetch` 返回后在流式输出里打一行结果摘要（如 `→ 搜索 "xxx" 返回 N 条结果`），
  ReAct、Plan、Team 三种模式一致。
- shared 浏览器模式会复用登录态，因此敏感页面上的改写操作必须单步审批。

## 安全边界

这是本地学习项目，不提供容器或 VM 隔离。“能审批”也不等于“已经沙箱化”。

当前边界包括：

- 文件类工具只能访问项目根目录内的路径
- 明显危险命令在进入审批前直接拒绝
- 写文件、执行命令、MCP 等操作可进入 HITL
- 危险调用写入本地 JSONL 审计日志
- 每轮修改前后可建立独立 Side-Git 快照
- 微信通道没有审批面板，采用更保守的非交互式策略

## 项目结构

| 目录                            | 职责                                                 |
|---------------------------------|------------------------------------------------------|
| `agent/`、`plan/`               | ReAct、Plan-and-Execute、Multi-Agent 和 DAG 计划模型 |
| `cli/`                          | 启动入口、命令解析、JLine 交互                       |
| `tool/`                         | 内置工具与统一批量执行                               |
| `llm/`、`config/`               | Provider 客户端、模型工厂和运行时配置                |
| `memory/`、`context/`           | 记忆、压缩和窗口预算                                 |
| `mcp/`、`skill/`、`browser/`    | 外部能力、按需说明和浏览器会话                       |
| `web/`                          | 联网搜索 provider 与网页抓取                         |
| `policy/`、`hitl/`、`snapshot/` | 策略、审批、审计和回滚                               |
| `render/`、`tui/`、`util/`      | inline、plain 和 Lanterna 渲染，终端样式与 Markdown  |
| `prompt/`                       | 分层 system prompt                                   |
| `rag/`、`lsp/`                  | 语义检索和代码诊断                                   |
| `image/`                        | 剪贴板与本地图片预处理                               |
| `runtime/`、`wechat/`           | 后台任务、HTTP API 和微信通道                        |

## 测试

按改动范围选最小测试：

```bash
# 常规快速回归
mvn test -Pquick

# 终端与渲染
mvn test -Pphase16-smoke

# 单个或一组测试
mvn test -Dtest=XxxTest -DskipTests=false

# 全量
mvn test -DskipTests=false
```

更细的测试映射见 [AGENTS.md](AGENTS.md#验证)。

## 文档地图

- [AGENTS.md](AGENTS.md)：给编码 Agent 的仓库规则
- [MCC.md](MCC.md)：Mini Claude Code 启动时加载的项目记忆
- [ROADMAP.md](ROADMAP.md)：当前状态、下一步和明确边界
- [docs/README.md](docs/README.md)：设计记录、测试手册和专题文档索引
- [docs/agents-reference.md](docs/agents-reference.md)：按模块查阅的实现约束

## 项目原则

1. **先跑通，再抽象。** 新设计先经过真实 CLI 路径。
2. **代码行为优先。** 文档与实现冲突时，以可验证的代码为准，并及时修正文档。
3. **能力共享。** ReAct、Plan 和 Multi-Agent 复用同一套基础设施。
4. **边界透明。** 明确说明哪些是策略保护，哪些还不是沙箱。
5. **为学习保留细节。** 不用框架把核心 Agent 循环完全藏起来。

## 致谢

项目名称和部分终端交互受到 Claude Code 启发；MCP、JLine、OkHttp、Jackson、JGit、SQLite、JavaParser
等开源项目提供了重要基础。这里的实现和取舍服务于我自己的学习目标，不代表这些项目的官方方案。
