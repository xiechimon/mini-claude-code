# Mini Claude Code 文档

`docs/` 保存实现说明、设计记录和手工验证清单。使用项目先读根目录 [README.md](../README.md)
；修改代码先读 [AGENTS.md](../AGENTS.md)。

## 当前维护文档

| 文档                                                                       | 什么时候看                                             |
|----------------------------------------------------------------------------|--------------------------------------------------------|
| [agents-reference.md](agents-reference.md)                                 | 修改具体模块，需要配置优先级和实现边界                 |
| [inline-tui-manual-tests.md](inline-tui-manual-tests.md)                   | 验证 inline / Lanterna / plain 终端行为                |
| [code-search-golden-set.md](code-search-golden-set.md)                     | 修改 `glob_files`、`grep_code`、`read_file` 或搜索预算 |
| [prompt-analysis-template.md](prompt-analysis-template.md)                 | 分析实际组装后的 system prompt                         |
| [phase-21-image-input-manual-test.md](phase-21-image-input-manual-test.md) | 手工验证剪贴板和图片输入                               |

## 设计记录

数字 phase 表示实现顺序，不是产品版本，也不是当前功能清单。这些文件保留当时的问题、取舍、草图和验证证据；其中的版本号、命令或计划可能已经过时。

| 主题                         | 记录                                                                                                                               |
|------------------------------|------------------------------------------------------------------------------------------------------------------------------------|
| MCP 核心与高级能力           | [phase-10](phase-10-mcp-core.md)、[phase-11](phase-11-mcp-advanced.md)                                                             |
| 长上下文                     | [phase-12](phase-12-long-context.md)                                                                                               |
| Chrome DevTools 与登录态会话 | [phase-13](phase-13-chrome-devtools-mcp.md)、[phase-14](phase-14-cdp-session-reuse.md)                                             |
| Skill                        | [phase-15](phase-15-skill-system.md)                                                                                               |
| 终端与 JLine                 | [phase-16](phase-16-tui-productization.md)、[inline pivot](inline-tui-pivot.md)、[phase-22](phase-22-jline-interaction-upgrade.md) |
| LSP 诊断                     | [phase-17](phase-17-lsp-diagnostics.md)                                                                                            |
| Side-Git 快照                | [phase-18](phase-18-side-history-snapshot.md)                                                                                      |
| Prompt 分层                  | [phase-19](phase-19-prompt-layering.md)                                                                                            |
| 后台任务与 Runtime API       | [phase-20](phase-20-runtime-api.md)                                                                                                |
| 图片输入                     | [phase-21](phase-21-image-input.md)                                                                                                |
| 微信 iLink                   | [phase-23](phase-23-wechat-channel.md)                                                                                             |

## 维护规则

- 当前行为只在 README 和 reference 中描述一次；设计记录通过链接补充背景。
- 代码、文档冲突时先验证代码，再修正文档。
- 新功能只有在交付状态变化时更新 Roadmap。
- 历史记录不追求改写成当前时态，但应移除无关署名、教程营销文案和机器特定路径。
- 真实密钥、用户目录和本机调试数据不进入文档。
