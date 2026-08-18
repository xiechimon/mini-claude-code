# Mini Claude Code Roadmap

这份文件只记录项目状态和下一步，不承担教程目录或功能说明。已交付行为以代码和 `AGENTS.md` 为准，使用方式以 `README.md`
为准，历史设计过程放在 `docs/`。

状态：

- ✅ 已交付并有自动化或手工验证
- 🚧 正在完善，主路径可用但仍有明确缺口
- 🧭 候选方向，尚未承诺

## 当前基线

| 领域             | 状态 | 说明                                                                |
|------------------|------|---------------------------------------------------------------------|
| ReAct            | ✅   | 默认执行路径，支持流式内容和工具调用                                |
| Plan-and-Execute | ✅   | 计划审阅、DAG、依赖批次和失败重规划                                 |
| Multi-Agent      | ✅   | Planner / Worker / Reviewer 编排                                    |
| Memory           | ✅   | 对话压缩、显式长期记忆、`MCC.md` 项目记忆                           |
| 代码理解         | ✅   | 实时 glob/grep/read，RAG 作为语义辅助                               |
| HITL 与策略      | ✅   | 审批、路径围栏、命令快速拒绝、审计                                  |
| 多模型           | ✅   | 四类 provider，运行时切换                                           |
| Web 与 Browser   | ✅   | 搜索、抓取、Chrome DevTools、shared 会话保护                        |
| MCP              | 🚧   | stdio/HTTP、tools/resources/prompts 已有；OAuth、sampling、恢复待补 |
| Skill            | ✅   | 三层加载、按需展开和启用状态                                        |
| 终端体验         | 🚧   | inline 主路径可用，仍持续修正 JLine 边界                            |
| LSP 诊断         | 🚧   | 当前是 Java 语法诊断 MVP，不是完整语言服务器编排                    |
| Side-Git         | ✅   | pre/post 快照、审计和恢复                                           |
| Runtime API      | 🚧   | 本机 HTTP 与持久任务 MVP                                            |
| 图片输入         | ✅   | 本地图片预处理、多模态 provider 路由                                |
| 微信 iLink       | 🚧   | 文本收发 MVP，采用非交互式安全策略                                  |

## 接下来

### 1. MCP 可靠性

目标：让 MCP 从“能连接”变成“长时间使用仍可恢复”。

- OAuth 授权流程
- client sampling
- server 异常退出检测与受控重启
- 重连后的工具、resources 和 prompts 状态恢复
- 超时、取消和错误信息的一致化

完成条件：stdio 与 HTTP 各有可重复的断线恢复测试，重连不会遗留旧工具或绕过 HITL。

### 2. 终端稳定性

目标：把 inline 模式的 transcript、输入区和状态栏边界继续做稳。

- 窄终端、远程 shell、`TERM=dumb` 和 `NO_COLOR` 回归
- 多行输入、宽字符和 Markdown 表格的宽度处理
- activity、审批弹窗和后台输出并发时的光标稳定性
- 把高价值手工场景逐步变成伪终端测试

完成条件：`docs/inline-tui-manual-tests.md` 的核心路径可重复通过，新增视觉修复都有聚焦回归。

### 3. Provider 一致性

目标：不同 OpenAI-compatible 服务在流式、thinking、tools、图片和 token usage 上表现一致。

- 统一 provider capability 描述
- 明确 reasoning 内容的历史回灌规则
- 统一错误分类、重试边界和超时
- 为图片不支持、tools 不支持等降级路径补协议测试

完成条件：每个 provider 都有不依赖真实 Key 的请求序列化测试。

### 4. Runtime 与非交互通道

目标：让终端之外的入口复用相同的 Agent 能力，同时保持更保守的权限模型。

- Runtime task 恢复与事件回放
- 微信通道的断线恢复、限流和可观察性
- 非交互式工具白名单的配置与审计
- 文本之外的消息类型评估

完成条件：进程重启后任务和通道状态可解释，不会因缺少审批界面扩大权限。

## 候选方向

这些方向有学习价值，但目前不算承诺：

- 容器、gVisor 或 microVM 级执行隔离
- 更完整的多语言 LSP 生命周期管理
- Agent 运行 trace 与可视化调试
- prompt、tool schema 和上下文预算的离线评测
- 手写实现与 Spring AI / LangGraph4j 的对照实验

## 明确边界

- 项目用于学习和技术交流，不规划商业版。
- 当前策略层不是容器或 VM 沙箱。
- `ROADMAP.md` 中的候选项不能写进“已支持”列表。
- Banner 版本和 Maven artifact 版本各自服务不同用途，暂不强制同步。
- 历史 phase 文档是设计记录，可能保留当时的版本号、方案和未实现草图。

## 历史设计记录

详细设计和验证记录统一从 [docs/README.md](docs/README.md) 进入。数字 phase 只表示实现顺序，不再作为 README 的产品叙事。
