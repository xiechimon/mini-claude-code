---
name: lesson-loop
description: 开一课（sNN）时走的 7 步循环，以及契约 / 参考解法 / 实现旁证三层基准的取用方法。用法：/lesson-loop s04
---

# 每课的循环

参数 `$1` 为课次编号（如 `s04`）。未给出时先问用户开哪一课。

本文件只描述「开一课怎么走」。项目定位、契约对齐约束、沉淀去处等常驻规则见根目录 `CLAUDE.md`，它们在任何时候都生效，不因本 skill 是否加载而改变。

## 基准来源

**功能基准是 Claude Code 的契约，不是课程的实现。** 三层分工：

| 层 | 来源 | 作用 |
|---|---|---|
| 契约层 | `@anthropic-ai/claude-agent-sdk` 的 `.d.ts`（`sdk.d.ts` + `sdk-tools.d.ts`，约 1.2 万行）、官方文档、可观测的运行时行为 | 定「接口和行为必须长什么样」，冲突时以它为准 |
| 参考解法 | 课程 `sNN_topic/code.py` | 同一契约的一种简化解法，回答「别人怎么解」 |
| 实现旁证 | <https://github.com/liuup/claude-code-analysis> 的 `analysis/` 18 章 | 回答「他们为什么这么做」。`src/` 的 51 万行源码不读 |

取契约：`npm pack @anthropic-ai/claude-agent-sdk` 解包后读 `.d.ts`。实现是 minified 的，读不了也不必读。

契约超出学习范围时（大量向后兼容字段、几十个可选参数）按 `code.py` 的裁剪走 —— 学的是结构，不是字段表。

课程仓库：<https://github.com/shareAI-lab/learn-claude-code>（Python 实现，17 课递进，s01 → s17）。

官网 <https://learn.shareai.run/zh> **不作基准**。其页面数据来自构建期产物 `web/src/data/generated/`，实测存在源码已改而该产物未重新生成的情况，且部署可能落后于 `main`。读课程一律读仓库源码。

## 七步

1. **契约基线** —— 从 `.d.ts` 与官方文档提取本课能力的完整契约：类型名、枚举值、字段。这是「必须有什么」的清单。
2. **参考解法** —— 读 `sNN_topic/code.py` 与 `README.zh.md`，记录它怎么解、裁掉了什么。
3. **Java 设计 Grill** —— 使用 `$grill-with-docs` 逐项审议契约到 Java 的映射。事实与 SDK 能力由 agent 查证，设计决定由用户做；frontier 清空且用户确认形成共识后才能实现。
4. **实现** —— 按共识实现，不提前混入后续课次能力。
5. **验证** —— 按 `CLAUDE.md` 的「验证原则」走契约核对、端到端场景、必要的单元测试。
6. **对照观测** —— 用 `claude --debug-file <path>` 抓一次真实运行日志，对照自己的实现。s04 之后改用全事件 hook 日志器。
7. **复盘** —— 三栏记录：契约说什么 / `code.py` 怎么解 / 我怎么解 + 差异理由。写入 `.scratch/nano-agent/issues/NN-*.md` 的 `## Comments`。

每一步完成后向用户确认再进入下一步，不连跑。
