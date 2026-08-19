# Mini Claude Code · Agent Guide

这是编码 Agent 进入仓库后的首读文件。实现细节按需查 [docs/agents-reference.md](docs/agents-reference.md)
，文档索引见 [docs/README.md](docs/README.md)。

信息优先级：代码实际行为 > 本文件 > `MCC.md` > `README.md` > `ROADMAP.md`。Roadmap 只表示方向，不证明功能已经交付。

## 项目定位

- Mini Claude Code 是我的 Java Agent CLI 学习项目，非商业产品。
- 目标是保留 Agent 核心机制的可读性：ReAct、Plan、Multi-Agent、工具、上下文、MCP、终端和安全边界都能在仓库里追到。
- Java 17，包根 `com.miniclaudecode`，本机 Maven，仓库不带 Maven Wrapper。
- Banner 版本是 `v16.1.0`；Maven artifact 是 `mini-claude-code-1.0-SNAPSHOT.jar`，目前允许不同步。
- `MCC.md` 是程序启动时注入的项目记忆；个人或会变化的事实通过 `/save` 管理。

## 开发入口

```bash
cp .env.example .env
mvn clean compile exec:java -Dexec.mainClass="com.miniclaudecode.cli.Main"
mvn clean package
java -jar target/mini-claude-code-1.0-SNAPSHOT.jar
```

前提：JDK 17、Maven、至少一个受支持 provider 的 API Key；`ripgrep` 可选。编译、测试和调试统一走 `mvn`，`java -jar` 只用于打包产物验收。

## 代码导航

仓库存在 `.codegraph/` 时，定位代码和理解调用链先用：

```bash
codegraph explore "<符号或问题>"
```

CodeGraph 无结果或只需精确文本匹配时再用 `rg`。避免无目标地通读大文件。

| 任务           | 首选入口                                                  |
|----------------|-----------------------------------------------------------|
| CLI 命令       | `cli/Main.java`、`cli/CliCommandParser.java`              |
| ReAct          | `agent/Agent.java`                                        |
| Plan / DAG     | `agent/PlanExecuteAgent.java`、`plan/`                    |
| Multi-Agent    | `agent/AgentOrchestrator.java`、`agent/SubAgent.java`     |
| 工具           | `tool/ToolRegistry.java`                                  |
| 模型           | `llm/*Client.java`、`llm/LlmClientFactory.java`           |
| Memory         | `memory/MemoryManager.java`、`memory/LongTermMemory.java` |
| MCP            | `mcp/McpServerManager.java`、`mcp/McpClient.java`         |
| 策略与审批     | `policy/`、`hitl/`                                        |
| 终端           | `render/`、`cli/Main.java`                                |
| RAG / LSP      | `rag/`、`lsp/`                                            |
| Runtime / 微信 | `runtime/`、`wechat/`                                     |

## 架构不变量

三条执行路径共享 `ToolRegistry`、`MemoryManager` 和 `SnapshotService`：

| 路径             | 入口                | 触发     |
|------------------|---------------------|----------|
| ReAct            | `Agent`             | 普通输入 |
| Plan-and-Execute | `PlanExecuteAgent`  | `/plan`  |
| Multi-Agent      | `AgentOrchestrator` | `/team`  |

内置工具：`read_file`、`write_file`、`list_dir`、`glob_files`、`grep_code`、`execute_command`、`create_project`、`search_code`、
`web_search`、`web_fetch`、`revert_turn`。MCP 工具统一命名为 `mcp__{server}__{tool}`。

新增共享能力时，优先放在三条路径共同依赖的层；不要只给某一种模式写旁路。

## 关键行为

### 代码检索

- 精确定位：`glob_files` → `grep_code` → `read_file`。
- `grep_code` 优先 ripgrep，缺失时回退 Java 扫描；返回 `partial` 或 `suggested_reads` 时继续缩小范围。
- `search_code` 只处理模糊语义、关键词不清或普通搜索无果的场景。

### Memory 与上下文

- 长期记忆只在 `/save` 或用户明确要求保存时写入，只保存跨会话稳定事实。
- 默认使用 project scope；跨项目偏好才使用 global。
- `MCC.md` 放可提交、长期稳定的项目规则；一次性任务不写入。
- short-term memory 压缩与 conversation history 压缩是两条链路；真正防止请求超过窗口的是后者。
- `/clear` 清当前会话、短期记忆和待注入 Skill buffer，保留长期记忆；`/compact` 只压缩当前 ReAct history。

### 工具、安全与并发

- 拦截顺序：`HitlToolRegistry` → `ToolRegistry` → `PathGuard` / `CommandGuard`。
- 策略拒绝不能被用户审批绕过；文件路径必须留在项目根内。
- 三条执行路径都调用 `executeTools()`；默认最多 4 个并发，结果保持原 tool call 顺序。
- 微信通道没有审批面板：只读工具默认允许，命令和 MCP 必须命中白名单，`revert_turn` 与浏览器会话切换默认拒绝。

### Web、Browser 与 MCP

- 本地“当前项目 / 文件 / README / 代码”问题使用本地代码工具，不发起联网搜索。
- 已知 URL 先 `web_fetch`；SPA、反爬或登录页再使用 Chrome DevTools MCP。
- `web_search` / `web_fetch` 返回后三条执行路径都会打一行结果摘要；ReAct 走 renderer 流，
  Plan 走任务输出流，SubAgent 走 step 级缓冲流。
- 浏览器读取优先 `take_snapshot`；公开页面不提前切 shared 模式。
- shared 模式下，敏感页面改写操作必须单步审批，`close_page` 只能关闭本会话创建的 tab。
- MCP 合并用户级和项目级配置；server 全程后台启动，慢 server 保持 `STARTING`，不能阻塞 Logo 和首个输入提示。

### Prompt 与 Skill

- Prompt 由 `PromptAssembler` 分层组装；内置模板位于 `src/main/resources/prompts/`。
- Skill 索引最多 20 个 / 4KB；`load_skill` 将正文写入 `SkillContextBuffer`，下一轮 user message 前置注入。
- 修改工具集时同步 ReAct、Plan、SubAgent 和必要的 Planner prompt。

### 终端

- 交互主路径输出走 `Renderer.stream()`；只有 fatal bootstrap、Runtime API 和 legacy 降级路径可以直接写 stdout。
- 启动时尽早建立 `Terminal → LineReader → Renderer`。
- `BottomStatusBar` 由 JLine `Status` 管理；不手写绝对光标定位或用 `CLEAR_TO_EOS` 覆盖 transcript。
- live thinking 区只清理自己打印的行。
- 提交输入以 `>` 独立行回写，不添加整行背景；不能依赖 JLine accept 后残留的编辑行。
- `ctx` 是下一轮仍会携带的上下文估算；`in/out/cache` 是最近调用统计。

Provider、配置优先级、JLine 和各模块的详细边界见 [docs/agents-reference.md](docs/agents-reference.md)。

## 改动联动

行为变化必须同步本文件和 `README.md`；只有交付状态变化才改 `ROADMAP.md`。

| 改动面          | 必须一起检查                                                  |
|-----------------|---------------------------------------------------------------|
| CLI 命令        | `Main.java`、`CliCommandParser.java`、测试、README、本文件    |
| Plan 审阅       | `Main.java`、`PlanReviewInputParser.java`、测试、手工终端路径 |
| 工具            | `ToolRegistry.java`、三条执行路径的 prompt、文档              |
| Provider        | 对应 Client、`LlmClientFactory.java`、`.env.example`、文档    |
| Embedding / RAG | `EmbeddingClient`、`VectorStore`、配置、文档                  |
| Web / Browser   | `web/`、`browser/`、ToolRegistry、策略、测试、文档            |
| Memory          | `MemoryManager`、`LongTermMemory`、`TokenBudget`、测试、文档  |
| HITL / 策略     | `policy/`、ToolRegistry、HitlToolRegistry、prompt、配置、测试 |
| MCP             | `mcp/`、ToolRegistry、HITL、AuditLog、prompt、测试、文档      |

未知 `/xxx` 必须在 CLI 层报告“未知命令”，不能回退给 Agent。

## 验证

```bash
# 常规
mvn test -Pquick

# 终端
mvn test -Pphase16-smoke

# 单组测试
mvn test -Dtest=XxxTest -DskipTests=false

# 全量
mvn test -DskipTests=false
```

| 场景        | 聚焦测试                                                                    |
|-------------|-----------------------------------------------------------------------------|
| 搜索工具    | `ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest`               |
| 命令解析    | `CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest` |
| DAG / Plan  | `ExecutionPlanTest`                                                         |
| Multi-Agent | `AgentRoleTest,AgentMessageTest,AgentOrchestratorTest`                      |
| RAG         | `CodeChunkerTest,CodeAnalyzerTest,VectorStoreTest,CodeIndexTest`            |

用最小可重复路径证明修改；终端交互无法自动化时，把手工证据补进对应文档。

## Agent skills

- **Issue tracker** —— issues 与 spec 以 markdown 存放在 `.scratch/<feature-slug>/`。见 `docs/agents/issue-tracker.md`。
- **Triage labels** —— 沿用五个规范角色的默认标签（needs-triage / needs-info / ready-for-agent / ready-for-human /
  wontfix）。见 `docs/agents/triage-labels.md`。
- **Domain docs** —— single-context：根目录 `CONTEXT.md` + `docs/adr/`。见 `docs/agents/domain.md`。

## 工作约定

- 先复现和定位责任层，再改代码；保持补丁聚焦，不顺手重构。
- 代码可读性优先于抽象数量；关键技术决策说明原因。
- Java 注释使用简洁中文且句尾不写 `。`，只解释设计原因、不变量、协议限制、安全边界和反直觉兼容行为
- 跨模块接口、复杂顶层类型、协议边界和有非显然副作用的方法写 Javadoc；简单 record、enum、getter、构造器和自解释测试免写
- `@param` / `@return` / `@throws` 只补充名称无法表达的约束；阶段历史写进 `docs/`，代码 TODO 必须写明触发条件和完成标准
- 错误应可观察。fallback 只用于明确的业务容错或外部系统边界，不能掩盖主流程异常。
- 保留用户已有改动；不提交 `.env`、真实 API Key 或 `target/`。
- Commit 使用 Conventional Commits，中文祈使句；不添加 AI co-author trailer。

稳定规则写入本文件，模块细节写入 `docs/agents-reference.md`，历史方案写入对应 `docs/` 设计记录。
