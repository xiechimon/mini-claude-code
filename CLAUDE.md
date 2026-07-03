# CLAUDE.md

## 学习循环 📖

本项目是 **学习项目**：通过手写一个 Java 版简易 Claude Code 来掌握 coding agent
的构造。参考课程 <https://github.com/shareAI-lab/learn-claude-code>（Python 实现，17 课递进，s01 → s17）。

**代码是副产品，学习效果才是目标。** 主判据是**能造出一个真能跑的 agent harness**，一切流程决策以此为准，不以「多快写完」为准。

项目终局是**一次性学习脚手架**，不是长期自用工具 —— 不投入配置系统、错误恢复、跨平台、性能这类与理解底层无关的工程量。

### 基准来源 🎯

**功能基准是 Claude Code 的契约，不是课程的实现。** 三层分工：

| 层 | 来源 | 作用 |
|---|---|---|
| 契约层 | `@anthropic-ai/claude-agent-sdk` 的 `.d.ts`（`sdk.d.ts` + `sdk-tools.d.ts`，约 1.2 万行）、官方文档、可观测的运行时行为 | 定「接口和行为必须长什么样」，冲突时以它为准 |
| 参考解法 | 课程 `sNN_topic/code.py` | 同一契约的一种简化解法，回答「别人怎么解」 |
| 实现旁证 | <https://github.com/liuup/claude-code-analysis> 的 `analysis/` 18 章 | 回答「他们为什么这么做」。`src/` 的 51 万行源码不读 |

取契约：`npm pack @anthropic-ai/claude-agent-sdk` 解包后读 `.d.ts`。实现是 minified 的，读不了也不必读。

契约超出学习范围时（大量向后兼容字段、几十个可选参数）按 `code.py` 的裁剪走 —— 学的是结构，不是字段表。

官网 <https://learn.shareai.run/zh> **不作基准**。其页面数据来自构建期产物 `web/src/data/generated/`，实测存在源码已改
而该产物未重新生成的情况，且部署可能落后于 `main`。读课程一律读仓库源码。

### 每课的循环

1. **契约基线** —— 从 `.d.ts` 与官方文档提取本课能力的完整契约：类型名、枚举值、字段。这是「必须有什么」的清单。
2. **参考解法** —— 读 `sNN_topic/code.py` 与 `README.zh.md`，记录它怎么解、裁掉了什么。
3. **Java 设计 Grill** —— 使用 `$grill-with-docs` 逐项审议契约到 Java 的映射。事实与 SDK 能力由 agent 查证，设计决定由用户做；
   frontier 清空且用户确认形成共识后才能实现。
4. **实现** —— 按共识实现，不提前混入后续课次能力。
5. **验证** —— 按「验证原则」走契约核对、端到端场景、必要的单元测试。
6. **对照观测** —— 用 `claude --debug-file <path>` 抓一次真实运行日志，对照自己的实现。s04 之后改用全事件 hook 日志器。
7. **复盘** —— 三栏记录：契约说什么 / `code.py` 怎么解 / 我怎么解 + 差异理由。写入该课票文件的 `## Comments`。

### 契约对齐约束 ⛔

- **17 课全做**，含 s15 Integrated Harness —— 它约三成内容是 s01–s14 单独做长不出来的：并发、租约、投递保证、信任边界、
  流水线顺序。
- **名全录、行为按需** —— 契约里的枚举值和事件名**全部**录进类型，哪怕没有实现（成本只是一个 enum）；行为实现按端到端场景
  裁剪，未实现的显式标记。这样「没实现」是待办，不是遗漏。
- **卡住时分层降级** —— 先完成名全录、读懂、复盘，实现挂起并标记，继续下一课。能力面不留洞，留洞的只是实现深度。
- 不逐行翻译 Python；优先采用自然、类型安全、可维护的 Java 表达。
- 「最小改动」只表示不实现后续课次功能，不表示删减当前课契约。

### 沉淀去处

| 内容                   | 去处                                                  |
|------------------------|-------------------------------------------------------|
| 流程与规则             | 本文件                                                |
| 领域术语               | `CONTEXT.md`                                          |
| 难以逆转的决策及其理由 | `docs/adr/`                                           |
| 每课 diff 复盘         | `.scratch/nano-agent/issues/NN-*.md` 的 `## Comments` |

仅当复盘结论构成硬决策时，才从票中提炼为 ADR。 **不要每课都写 ADR。**

## 输出规范 📝

中文回复，先给结论和方案，言简意赅，不输出无价值 commentary。

## 动手前 🔍

- 回归第一性原理：先明确任务真正要解决的问题，拆成最小可验证单元，不照搬惯例。
- 改代码前必须能回答：问题真实存在吗？复现了吗？根因找到了吗？项目里已有实现吗？生态里有成熟方案吗？改动是最小必要范围吗？
- 答不上就继续分析，不动手。
- 实现前检索 GitHub / npm / PyPI 等生态与官方文档，成熟方案优先，不重复造轮子。

## 改代码 💻

- 冲突时正确性和根因修复优先于速度和改动量。
- 仅实现必要功能，不过度设计，不顺手重构。
- 先做端到端可运行的最小版本，再演进。
- 不主动追求向后兼容：废弃路径直接删；保留兼容层必须写明理由。
- 代码检索优先用 CodeGraph，禁止无目的读全文件、批量加载无关上下文。
- 关键技术决策必须说明「为什么」。

### Java 注释

- 每个顶层类型（含测试类）加简洁中文 Javadoc，聚焦职责、边界或设计原因。
- public 方法与接口方法加中文 Javadoc 描述契约，配齐 `@param` / `@return` / `@throws`。
- 私有方法、字段、方法体注释默认不写；仅在命名和结构无法表达设计原因、边界或副作用时补充。

## Bug 分析 🐛

复现 → 验证根因 → 最小精准修复。禁止猜测式修改和试错堆补丁。

结论格式：无问题输出 `✅OK`；有问题给出「原因 + 验证依据 + 最小修复方案」。

## Fallback 原则 🛡️

仅用于明确设计的业务容错、网络异常、外部 API 边界。

禁止用 fallback / 默认值 / catch 掩盖主流程异常、数据错误和未知问题 —— 错误必须暴露，且有日志可观测。

## 验证原则 ✅

三者分工，不混用：

- **契约核对** 定「做什么」—— 对着 `.d.ts` 逐项确认枚举值和事件名已全部录入，未实现的显式标记。
- **端到端场景** 定「做完了」—— JUnit 加写死的假模型响应，可重跑。假响应能精确构造自然触发不了的场景：撑爆上下文、
  等定时器、等后台进程结束。s13 Agent Teams、s16 Workflow 这类响应序列过于复杂的课，降级为交互跑加结论写票。
- **单元测试** 只保护真会写错的边界逻辑（如 `Workspace` 的 realpath 解析），**不给每个类补测试**。

不为简单修改跑全量测试，不为形式加无价值测试。

## Plan Mode / Subagent / Skill 🤖

简单修改直接执行；多文件修改、架构调整、复杂分析才用 Plan Mode 或 Subagent。

Skill 无明确收益不调用。`mattpocock-skills` 用于类型建模与接口契约设计。

## Git 提交规范 📦

Conventional Commits，中文祈使句，不加句号：

```text
<type>(<scope>): <中文描述>
```

type：`feat` / `fix` / `docs` / `refactor` / `test` / `chore`；scope 可省略。

scope 仅表示项目中长期稳定的功能范围，例如 `desktop`、`sdk`、`mcp`。课程编号、issue 编号、迭代名称不得作为 scope，也不应为了标识
进度写入 subject。没有明确功能范围时省略 scope，subject 只描述实际代码或文档变化。

默认只写 subject 一行。不加 body（除非改动原因无法从 diff 看出）、不加 `Co-Authored-By` 等 trailer、不加额外 commentary。

## 工具链约定 🔧

本机 `java` 为 JDK 26，Maven 运行在 JDK 21。 **一律用 `mvn` 编译与运行，不使用裸 `java` 命令**，避免编译期与运行期版本不一致。详见
`docs/adr/0001-技术栈-java21-maven-官方sdk.md`。

## Agent skills 🧩

- **Issue tracker** —— issues 与 spec 以 markdown 存放在 `.scratch/<feature-slug>/`。见 `docs/agents/issue-tracker.md`。
- **Triage labels** —— 沿用五个规范角色的默认标签（needs-triage / needs-info / ready-for-agent / ready-for-human /
  wontfix）。见 `docs/agents/triage-labels.md`。
- **Domain docs** —— single-context：根目录 `CONTEXT.md` + `docs/adr/`。见 `docs/agents/domain.md`。
