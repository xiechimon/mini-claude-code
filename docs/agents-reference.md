# Mini Claude Code · Agent Reference

这里保存按模块查阅的配置优先级、行为边界和实现说明。Agent 的首读规则见 [AGENTS.md](../AGENTS.md)
，用户文档见 [README.md](../README.md)。

---

## 配置与持久化

### API Key 读取顺序

1. `~/.mini-claude-code/config.json` 中对应 provider 的 `apiKey`
2. 环境变量：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `KIMI_API_KEY` / `FREELLMAPI_API_KEY`
   （Kimi 兼容 `MOONSHOT_API_KEY`）
3. 仓库当前目录下的 `.env`
4. 用户主目录下的 `.env`

### 持久化位置

| 数据          | 默认路径                                                            | 覆盖方式                                                            |
|---------------|---------------------------------------------------------------------|---------------------------------------------------------------------|
| 长期记忆      | `~/.mini-claude-code/memory/long_term_memory.json`                  | `-Dmini-claude-code.memory.dir`                                     |
| 项目级记忆    | `MCC.md` / `.mini-claude-code/MCC.md` / `MCC.local.md`              | 用户级稳定偏好：`~/.mini-claude-code/MCC.md`                        |
| RAG 索引      | `~/.mini-claude-code/rag/codebase.db`                               | `-Dmini-claude-code.rag.dir`                                        |
| 审计日志      | `~/.mini-claude-code/audit/audit-YYYY-MM-DD.jsonl`                  | `MINI_CLAUDE_CODE_AUDIT_DIR` / `-Dmini-claude-code.audit.dir`       |
| Side-Git 快照 | `~/.mini-claude-code/snapshots/<project_hash>/<worktree_hash>/.git` | `MINI_CLAUDE_CODE_SNAPSHOT_DIR` / `-Dmini-claude-code.snapshot.dir` |
| 后台任务      | `~/.mini-claude-code/tasks/tasks.db`                                | —                                                                   |

### Snapshot 配置

系统属性 > 环境变量 > 默认值：`mini-claude-code.snapshot.enabled`(true) / `mini-claude-code.snapshot.max`(50) /
`mini-claude-code.snapshot.excludes`(.git,.mini-claude-code/snapshots,target,node_modules,dist,.idea, *.class,*.jar) /
`mini-claude-code.snapshot.dir`(~/.mini-claude-code/snapshots)

### Embedding 配置

环境变量 > 系统属性 > 默认值：`EMBEDDING_PROVIDER`(ollama) / `EMBEDDING_MODEL`(nomic-embed-text:latest) /
`EMBEDDING_BASE_URL`(http://localhost:11434)

### 日志配置

系统属性 > 环境变量/.env > 默认值：`MINI_CLAUDE_CODE_LOG_DIR`(~/.mini-claude-code/logs) / `MINI_CLAUDE_CODE_LOG_LEVEL`
(INFO) / `MINI_CLAUDE_CODE_LOG_MAX_HISTORY`(7) / `MINI_CLAUDE_CODE_LOG_MAX_FILE_SIZE`(10MB) /
`MINI_CLAUDE_CODE_LOG_TOTAL_SIZE_CAP`(100MB)

### ReAct / SubAgent 预算

系统属性 > 默认值：`mini-claude-code.react.token.budget`(Integer.MAX_VALUE) / `mini-claude-code.react.stagnation.window`
(3) / `mini-claude-code.react.hard.max.iterations`(50)

设计取舍：长上下文模型默认不再以 80% x window 为硬限。死循环防护由 stagnation 检测（连续 3 轮相同工具调用）和
hardMaxIterations（50 轮）兜底。Token 显示行 `📊 Token: 已用 X / Y` 的 Y 是软提示，不代表强制限制。

### LLM HTTP 超时

系统属性 > 默认值：`mini-claude-code.llm.connect.timeout.seconds`(60) / `mini-claude-code.llm.read.timeout.seconds`
(300) / `mini-claude-code.llm.write.timeout.seconds`(60) / `mini-claude-code.llm.call.timeout.seconds`(600)

SSE 流式下 readTimeout 是两次 read 间最大间隔，GLM-5.1 生成大段 reasoning 时可能长时间静默，所以放宽到 300 秒。 DeepSeek
流式调用默认使用 HTTP/1.1，避免部分 HTTP/2 网关在长 SSE 响应中重置 stream，表现为 `stream was reset: INTERNAL_ERROR`。
DeepSeek 当前不发送图片输入：`supportsImageInput()` 返回 false，含图片的 `ContentPart` 会在 OpenAI-compatible
请求序列化时替换成文本提示，避免不支持多模态的 DeepSeek API 收到 `image_url` block。

### Web Search Provider

1. `SEARCH_PROVIDER` 显式指定 `zhipu` / `serpapi` / `searxng`
2. 未指定时按 Key 自动判断：`GLM_API_KEY` → zhipu / `SERPAPI_KEY` → serpapi / `SEARXNG_URL` → searxng
3. 都没有 → zhipu 占位

各 provider：zhipu (`GLM_API_KEY` + 可选 `ZHIPU_SEARCH_ENGINE`) / serpapi (`SERPAPI_KEY`) / searxng (`SEARXNG_URL`)

### Web Fetch 网络策略

scheme 白名单 (http/https) / 主机黑名单 (localhost/loopback/link-local/site-local) / 响应体上限 5MB / 超时 30s / 限流
30次/60s

### MCP 配置

1. 用户级：`~/.mini-claude-code/mcp.json`
2. 项目级：`.mini-claude-code/mcp.json`
3. 按 server 名 merge，项目级覆盖用户级

格式兼容 Claude Code：`command` + `args` = stdio，`url` + `headers` = Streamable HTTP。内置变量：`${PROJECT_DIR}`、`${HOME}`
；其他 `${VAR}` 从系统环境变量、系统属性、项目 `.env`、用户 `~/.env` 读取。 检测到 `STEP_API_KEY` 时自动内置 `step_search` 远程
MCP（显式同名配置优先）。`STEP_API_KEY` 只用于这个 MCP server，与 LLM provider 无关。

`ToolRegistry` 里 `web_search` / `web_fetch` 代理到 `mcp__step_search__*` 的分支要求 `currentProvider == "step"`；
Step provider 已移除，该分支目前不可达，`step_search` 的两个工具只能被 LLM 直接按名调用。

---

## 模块行为

### ReAct

- 主入口：`Agent.java`
- 退出条件由 LLM 自决（不返回 tool_calls 即结束）
- `AgentBudget` 三种兜底：token 超预算 / 连续 3 轮相同调用 / 50 轮硬上限
- 流式输出 reasoning_content + content；inline ReAct 用固定高度 live thinking 区动态预览 reasoning，同一次输入只把完整
  reasoning 引用块落到 transcript 一次；live 区只允许清理自己占用的行，避免覆盖旧输出
- inline 流式回答用低调 `▪` 标记起始，不再输出强标题；plain / 非流式兜底仍可使用传统 reasoning + answer 文本
- `TerminalMarkdownRenderer` 渲染 Markdown 表格时按终端列宽分配列宽，长内容在单元格内部换行；CJK 字符按显示宽度计算，避免表格行被终端自动折断后错位

### 长上下文

- `ContextProfile` 计算 short/balanced/long 模式
- GLM-5.1: 200k / DeepSeek V4: 1M / Kimi K2.6: 256k / FreeLLMAPI: 128k
- long 模式 (>=100k)：跳过 Memory 自动摘要，search_code 语义辅助 topK=20，MCP resources 自动索引；精确代码定位仍优先实时
  glob/grep/read
- prompt caching：能力声明 + cached usage 解析
- 自动压缩阈值为摘要输出和安全缓冲预留空间：`maxContextWindow - min(20k, window/4) - min(13k, window/8)`；200k 窗口约 167k
  触发，1M 窗口约 967k 触发，小窗口会按比例缩小预留。

### Memory

- 两道压缩：
    1. `ContextCompressor` 压缩 shortTermMemory
    2. `ConversationHistoryCompactor` 压缩 conversationHistory（真正发给 LLM 的消息）
- 第二道压缩切割在 user message 边界，保留最近 3 个 user 起算的尾部
- 三条路径 (ReAct/Plan/SubAgent)都接入第二道压缩
- `/compact` 可手动压缩当前 ReAct conversationHistory，不等待 token 阈值触发，保留最近 1 个 user 轮次；Plan/SubAgent 仍只走调
  LLM 前的自动压缩
- 长期记忆只通过 `/save` 或用户明确要求保存
- 长期记忆只保存跨会话稳定事实，不保存临时指令；默认项目级作用域，跨项目通用偏好才用 global
- 长期记忆管理命令：`/memory list`、`/memory search <关键词>`、`/memory delete <id>`、`/memory clear`
- `MCC.md` 不是 `/save` 长期记忆：它是启动时注入 system prompt 的项目指令文件，适合团队共享、长期稳定、可进 git 的规则
- 加载顺序：`~/.mini-claude-code/MCC.md` → `MCC.md` → `.mini-claude-code/MCC.md` → `MCC.local.md` →
  `.mini-claude-code/MCC.local.md`
- `MCC.md` 中独占一行的 `@relative/path.md` 会被展开；导入路径必须留在用户配置目录或项目根内，总注入内容按预算截断
- `/init` 生成精简 `MCC.md`，只写 commands / project positioning / architecture / pitfalls / don'ts；已有文件默认不覆盖，
  `/init --force` 重写；旧 `PAI.md` 只作为迁移期读取回退

### Multi-Agent

- 三角色：Planner / Worker (默认 2 个) / Reviewer
- 流程：规划 → 按依赖分配 Worker → Reviewer 审查 → 未通过重试 (最多 2 次)
- SubAgent IOException 返回 ERROR 类型
- 所有子代理共享 ToolRegistry 和 MemoryManager

### HITL

- 危险工具：write_file (中) / execute_command (高) / create_project (中) / revert_turn (高)
- 审批选项：y (批准) / a (全部放行) / n (拒绝) / s (跳过) / m (修改参数)
- fail-safe：连续 5 次无效输入判为 REJECTED
- 并发：requestApproval 整体 synchronized

### 策略层

- `PathGuard`：路径限定在项目根内（绝对路径外逃 / `..` 穿越 / 符号链接逃逸）
- `CommandGuard`：fast-fail 黑名单（sudo/rm -rf/mkfs/dd/fork bomb/curl|sh 等）
- 资源上限（`ToolRegistry` 常量）：write_file 5MB / execute_command 60s + 命令输出 8KB
- `AuditLog`：JSONL 字段 timestamp/tool/args/outcome/reason/approver/durationMs
- 拦截顺序：HitlToolRegistry → ToolRegistry → 策略层。用户无法批准策略拒绝的请求

### 并行工具

- `executeTools()` 固定线程池并行，默认最多 4 个并发
- 返回结果保持原始顺序
- Agent/PlanExecuteAgent/SubAgent 三条路径都走 executeTools ()
- 三条路径不直接调 `executeTools()`，统一经 `agent/ToolCallRunner.execute()`：翻译 ToolInvocation、统一
  `[scope]` 日志口径（scope 依次是 `iteration=N` / `task <id>` / 子 agent 名）、按原顺序回调 `onResult`。ReAct 用
  `onResult` 挂 `emitToolResultSummary()`，另两条传 null
- 返回图片的工具结果统一由 `ToolCallRunner.appendImageMessages()` 补成一条 user message

### Web

- `web_search`：SearchProvider 接口，返回 SearchResult 列表
- `web_fetch`：NetworkPolicy → WebFetcher → HtmlExtractor，SPA/防爬墙返回空正文 + 边界提示
- 联网决策由模型通过原生 tool call 自主发起；Prompt 不包含 Freshness Policy，不强制 `web_search`。本地“当前项目/当前
  README/当前文件/当前代码”仍作为代码库任务交给模型在工具 schema 中选择本地工具。
- StepSearch 优先级：当前模型 provider=`step` 且 model 以 `step-3.7-flash` 开头，并且自动/显式
  `mcp__step_search__web_search` / `mcp__step_search__web_fetch` 已注册时，内置 `web_search` / `web_fetch` 会先代理到
  StepSearch MCP；MCP 未就绪或返回不可用结果时回退原实现。**Step provider 已移除，`currentProvider` 不再可能等于
  `step`，这条代理路径当前不可达**；代码保留在 `ToolRegistry.shouldPreferStepSearch()`，等决定是删除还是改判定条件。
- JS 渲染 fallback 到 Chrome DevTools MCP

### MCP

- stdio + Streamable HTTP 双 transport
- 工具注册为 `mcp__{server}__{tool}`
- McpSchemaSanitizer 清洗 inputSchema
- 所有 mcp__ 工具默认走 HITL + AuditLog
- resources 双轨：虚拟工具 + @-mention 输入层
- CLI 启动 MCP 时不等待 server 就绪，Logo 和首个输入提示立即进入 JLine；慢 server 保持 `starting` 并在后台注册工具，
  用 `/mcp` / `/mcp logs <name>` 追踪，全部结束后刷新状态栏
- notifications 路由：tools/list_changed → 工具全量替换，resources 变化 → cache 失效

### Chrome DevTools MCP

- 默认 server：chrome-devtools，`npx -y chrome-devtools-mcp@latest --isolated=true`
- `/browser connect`：切到 --autoConnect 复用登录态 Chrome
- `/browser connect <port>`：旧式 CDP 端口路径
- `/browser disconnect`：切回 isolated
- 敏感页面策略：改写型工具必须单步 HITL，不复用全部放行
- shared 模式 close_page 只允许关闭 Mini Claude Code 创建的 tab

### Skill

- 三层加载：jar 内置 < 用户级 ~/.mini-claude-code/skills/ < 项目级 .mini-claude-code/skills/
- frontmatter：name (必填) / description (必填,<=500) / version / author / tags
- system prompt 索引段注入到三处提示词末尾，上限 20 个 / 4KB
- load_skill 工具把 SKILL.md 正文 (5KB 截断)写入 SkillContextBuffer
- buffer 一次性消费，最多 3 个 skill body

### 终端渲染

- 三个实现：InlineRenderer (默认) / LanternaRenderer / PlainRenderer
- 工具调用文案唯一来源是 `render/ToolCallLabels`：`toolLabel()` / `extractKeyParam()` / `group()` /
  `expandedLines()` / `printExpanded()`。PlainRenderer、InlineRenderer 的折叠块和 Plan / SubAgent 的
  `PrintStream` 直写路径共用同一份，改文案只改这一处
- 环境变量：`MINI_CLAUDE_CODE_RENDERER=inline|lanterna|plain`
- `MINI_CLAUDE_CODE_TUI=true`(旧) → lanterna + deprecation 提示
- `MINI_CLAUDE_CODE_NO_STATUSBAR=true`：禁用底部状态栏
- `NO_COLOR=1`：禁用 ANSI 颜色
- 当前开屏 Banner 是无右侧盒线边框的简洁布局，避免 ANSI/CJK 字宽导致竖线错位
- InlineRenderer 复用 JLine 4 的编辑能力，默认提示符是 `* `，右提示显示 `message / @path / @image`
- BottomStatusBar 是 JLine `Status` 托管的底部 dock：由 JLine 负责滚动区域和状态行位置，不再手写 `\n`、`moveUp`、
  `CLEAR_TO_EOS` 或绝对光标行号；dock 上层展示 YOLO/HITL 与 MCP/Skill 摘要，下层展示 model、phase、ctx、token、cost、elapsed 与
  cwd。关键字段可用 JLine `AttributedString` 做克制彩色高亮，但纯文本格式和列宽裁剪仍要稳定。`ctx` 只表示当前仍会带入下一轮请求的上下文估算，
  `in/out/cache` 表示最近任务调用统计。
- `/clear` 清空当前 ReAct conversationHistory、shortTermMemory 和待注入 SkillContextBuffer，并重建不含上一轮检索记忆的
  system prompt；长期记忆条目保留，后续只会按新查询重新检索注入。
- `/compact` 手动压缩当前 ReAct conversationHistory，压缩期间显示动态 activity 面板，成功后刷新底部 ctx；不会清空
  shortTermMemory、长期记忆或待注入 SkillContextBuffer。
- `/export` 导出当前 ReAct conversationHistory 为 Markdown 到 `~/.mini-claude-code/exports/session-*.md`；包含完整 system
  prompt，便于检查 LLM 实际接收前的指令，命令不接受路径参数。
- 普通任务和斜杠命令提交后都会以 `>` 独立行回写原始输入，不添加整行背景色，避免 JLine accept 后清掉编辑行导致结果区看不到刚执行的命令
- InlineRenderer 不使用独立 JLine `Display.update()` 维护 thinking 临时区；真实终端验证发现独立 Display 会在
  transcript/status 输出后从错误位置向上清屏。当前实现用固定高度 live 区重写自身行，content/tool 边界先清理 live 区再追加
  transcript。
- 交互期输出优先走 `Renderer.stream()`；`Main`、`PlanExecuteAgent`、`Planner`、`AgentOrchestrator` 都可接收同一个 renderer
  输出流，避免绕过 inline renderer 直接写 stdout
- `CodeIndex` 通过 `ProgressListener` 上报索引开始 / 文件数量 / 进度 / 完成或失败，`/index` 绑定当前 renderer 输出流；内部异常细节写
  logger

JLine 交互约束（设计记录见 `docs/phase-22-jline-interaction-upgrade.md`）：

- 首屏通过 `InlineRenderer.installStartupScreen(...)` 挂到 `LineReader.CALLBACK_INIT`，首次进入输入时用 `printAbove`
  一次性显示 Banner + tips；不要在 `readLine` 前裸写 stdout，否则 logo 会被 LineReader 首次重绘滚出可视区
- 默认首屏是 MCC 彩色 logo，只展示模型、MCP、Skill、ReAct 状态和三条 getting-started tips，不再把 MCP server 明细刷成启动日志
- `InlineRenderer` 可绑定当前 `LineReader`：`isReading()` 为 true 时完整行输出走 `LineReader#printAbove`
  显示在输入行上方，未绑定 / 非读取态 / 测试路径回退原 `PrintStream`
- 输入态左提示 `* `，提交回显左提示 `>`；单行输入只占一行，不额外追加空白行
- `MiniClaudeCodeHighlighter` 做输入实时高亮：slash 命令、`@` 引用、`@image:`、`@clipboard`、敏感词、明显危险 shell
  片段在编辑阶段标记；视觉提示不得混入提交文本
- `MiniClaudeCodeCompleter` 是唯一补全出口：`/model` provider、`/mcp` 子命令与 server、`/skill` 子命令与 skill name、
  `/task` / `/browser` / `/snapshot` 子命令、`@image:` 本地路径、本地 `@path`、MCP resource `@server:uri`
- `MiniClaudeCodeHistory` 持久化输入历史到 `~/.mini-claude-code/history/input.history`；`mini-claude-code.history.file` /
  `MINI_CLAUDE_CODE_HISTORY_FILE` 指向目录时自动取该目录下的 `input.history`；默认忽略空白、重复、明显密钥/Bearer、base64
  图片和超长输入；`/history clear` 清空
- 普通输入进 Agent 前先展开 MCP resource mention，再由 `LocalPathMentionExpander` 展开本地 `@path`：文件内联为 `<file>`
  块，目录内联为 `<directory>` 列表；绝对路径或符号链接逃逸项目根时保持原文
- Markdown 表格按当前终端列宽分配列宽，长内容在单元格内换行，不依赖终端自动折行
- ReAct 正常结束不再把 `📊 Token: ...` 打进正文区；token/cost/elapsed 留在底部状态行，phase 回 `idle`

### LSP 诊断

- write_file 成功后对 Java 文件做 JavaParser 语法诊断
- 诊断作为合成 user message 注入下一轮 LLM 请求
- `MINI_CLAUDE_CODE_LSP_ENABLED=false` 关闭

### Git Side-History

- side-git 在 ~/.mini-claude-code/snapshots/ 维护独立仓库（JGit，不依赖系统 git）
- pre-turn 同步，post-turn 异步
- revert_turn 纳入 HITL/AuditLog，恢复前先创建 pre-restore 快照

### Prompt 分层

- 组装顺序：base → personality → mode → approval → runtime_context → project_context → skills → context_mgmt → handoff
- runtime_context 每轮注入当前日期和系统时区，供相对日期理解使用
- project_context 顺序：`MCC.md` 项目记忆 → 相关长期记忆 → MCP resources 索引
- `MCC.md` 加载顺序：`~/.mini-claude-code/MCC.md` → 项目根 `MCC.md` → 项目根 `.mini-claude-code/MCC.md` →
  `MCC.local.md` → `.mini-claude-code/MCC.local.md`；`@relative/path.md` 可导入项目根内文件；总注入有字符预算，避免项目记忆变成
  token 噪音
- `/init` 按当前项目生成短 `MCC.md`，只放 commands / project positioning / architecture / pitfalls / don'ts；默认不覆盖已有文件，
  `/init --force` 重写；旧 `PAI.md` 仅作兼容回退且不会覆盖同位置的 `MCC.md`
- 覆盖优先级：jar 内置 < 用户级 ~/.mini-claude-code/prompts/ < 项目级 .mini-claude-code/prompts/
- 必要校验：base.md 和最终 prompt 必须包含 `## Language`

### 后台任务与 Runtime API

- DurableTaskManager (SQLite) / CLI: /task, /task list, /task add, /task cancel, /task log
- Runtime API: `serve --http --port 8080`，仅 127.0.0.1，需 API Key
- 端点：POST /v1/threads / POST /v1/threads/{id}/turns / GET /v1/threads/{id}/events

### 图片输入

- ContentPart 支持图片 block（base64 + mimeType）
- ImageProcessor：铺白底/缩放 2000x2000/压缩 5MB
- 输入：`@image:file:///path.png` / `@image:/path.png` / `@image:relative.png`
- GLM-5V-Turbo 通过 `/model glm-5v-turbo` 切换
- Provider 通过 `supportsImageInput()` 声明是否接收图片；不支持时保留文字上下文并省略图片 payload
- 历史 image payload 替换为文本占位，避免旧截图消耗上下文

---

## 核心文件

### Main.java

CLI 入口 / Banner / .env 读取 / 日志初始化 / 模式切换 / JLine raw mode

### Agent.java

ReAct 主循环 / 对话历史 / 工具调用与结果回灌

### PlanExecuteAgent.java

规划后执行 / 计划审阅 / DAG 任务执行 / 并行批次 / 失败重规划

### AgentOrchestrator.java

Multi-Agent 编排器 / 三角色管理 / 按依赖分配 / 审查重试

### SubAgent.java

可配置角色子代理 / 独立对话历史 / Worker 用工具、Planner/Reviewer 不用

### Planner.java

LLM 生成计划 JSON / 简单任务最小计划 / 重编号 task_1..N / 依赖计算

### ExecutionPlan.java

DAG 拓扑排序 / 可执行任务判定 / 进度可视化

### ToolRegistry.java

11 个核心内置工具 + MCP 动态工具 / executeTools () 并行入口 / ToolInvocation / ToolExecutionResult。代码理解默认路径是
`glob_files` / `grep_code` / `read_file` 现用现查，`grep_code` 优先走 ripgrep 并按 `max_results` / `head_limit` /
`max_chars` 渐进返回，`search_code` 保留为 RAG 语义辅助。确定性搜索链路的回归样例见 `docs/code-search-golden-set.md`。

### MCP Package

McpServerManager / McpClient / JsonRpcClient / StdioTransport / StreamableHttpTransport / McpSchemaSanitizer /
resources/ / mention/ / notifications/

### TUI Package

TuiBootstrap / LanternaWindow / TuiSessionController / pane/ / hitl/ / history/ / highlight/

### LLM Clients

- GLMClient：glm-5.1，glm-5v 开头切多模态接口
- DeepSeekClient：deepseek-v4-flash，thinking + tool calls 带回 reasoning_content；SSE 调用强制 HTTP/1.1，避免部分网络/网关把
  HTTP/2 长流重置成 `stream was reset: INTERNAL_ERROR`；按文本 provider 处理，`supportsImageInput()` 返回
  false，历史或工具回灌里的图片 `ContentPart` 在序列化时替换为文本提示，不能发 `image_url` block
- KimiClient：kimi-k2.6，thinking + tool calls 带回 reasoning_content
- FreeLlmApiClient：auto，默认 http://localhost:5173/v1，OpenAI-compatible 本地网关；可用 `/config provider freellmapi ...`
  写入配置后 `/model freellmapi` 切换

已移除的 provider：StepClient（阶跃星辰）、XfyunMaaSClient（讯飞星辰 MaaS）、AgnesClient（Agnes AI）。移除原因：Agnes 无任何
provider 特例，能力被 FreeLLMAPI 覆盖；讯飞 MaaS `supportsTools()` 返回 false，无法驱动 Agent 主循环，而它的 `--lora-id`
是 CLI 里唯一的 provider 专属选项；Step 的 `reasoning_format` 转换与 Kimi 的 reasoning 回传重叠。四个保留 provider 已覆盖
endpoint 切换、HTTP/1.1 降级、reasoning 方言和本地网关四类适配差异。

`ProviderConfig` 不再有 `loraId` 字段，`/config provider ... --lora-id` 也已删除。旧的 `~/.mini-claude-code/config.json`
里残留的 `loraId` 会被 `@JsonIgnoreProperties(ignoreUnknown = true)` 忽略，不影响加载。

---

## 事实来源与验证

- 可由 `.env` 读取的配置键和示例值以 [`.env.example`](../.env.example) 为准；仅支持进程环境变量或系统属性的启动参数保留在本文件。
- 依赖和版本以 [`pom.xml`](../pom.xml) 为准。
- 自动化测试以 `src/test/java/` 和 Maven profile 为准；按场景选择命令见 [AGENTS.md](../AGENTS.md#验证)。
- 真实模型、Embedding、外部 MCP 和完整终端体验仍需要手工联调，记录放在对应设计文档或测试清单。
