# CLAUDE.md

## 执行优先级 ⚖️

冲突时按此顺序取舍：正确性 > 根因修复 > 简单方案 > 最小改动 > 复用成熟方案 > 长期可维护性 > 完成速度。

## 输出规范 📝

中文回复，先给结论和方案，言简意赅，合理使用 Emoji，不输出无价值 commentary。

## 动手前 🔍

- 回归第一性原理：先明确任务真正要解决的问题，拆成最小可验证单元，不照搬惯例。
- 改代码前必须能回答：问题真实存在吗？复现了吗？根因找到了吗？项目里已有实现吗？生态里有成熟方案吗？改动是最小必要范围吗？
- 答不上就继续分析，不动手。
- 实现前检索 GitHub / npm / PyPI 等生态与官方文档，成熟方案优先，不重复造轮子。

## 改代码 💻

- 仅实现必要功能，不过度设计，不顺手重构。
- 先做端到端可运行的最小版本，再演进。
- 不主动追求向后兼容：废弃路径直接删；保留兼容层必须写明理由。
- 代码检索优先用 CodeGraph（`codegraph_explore` 或 `codegraph explore`），仅在必要时 Read，禁止无目的读全文件、批量加载无关上下文。
- 关键技术决策必须说明「为什么」。

## Bug 分析 🐛

复现 → 验证根因 → 最小精准修复。禁止猜测式修改和试错堆补丁。

结论格式：无问题输出 `✅OK`；有问题给出「原因 + 验证依据 + 最小修复方案」。

## Fallback 原则 🛡️

仅用于明确设计的业务容错、网络异常、外部 API 边界。

禁止用 fallback / 默认值 / catch 掩盖主流程异常、数据错误和未知问题 —— 错误必须暴露，且有日志可观测。

## 验证原则 ✅

静态检查 → 最小复现 → 关键路径。不为简单修改跑全量测试，不为形式加无价值测试。

## Plan Mode / Subagent 🤖

简单修改直接执行；多文件修改、架构调整、复杂分析才用 Plan Mode 或 Subagent。

## Skill 使用 🧩

无明确收益不调用。`mattpocock-skills` 用于类型建模与接口契约设计；`superpowers` 用于复杂任务拆解与方案规划。

## Git 提交规范 📦

Conventional Commits，中文祈使句，不加句号：

```text
<type>(<scope>): <中文描述>
```

type：`feat` / `fix` / `docs` / `refactor` / `test` / `chore`；scope 可省略。

scope 仅表示项目中长期稳定的功能范围，例如 `desktop`、`sdk`、`mcp`。课程编号、issue 编号、迭代名称不得作为 scope，也不应为了标识
进度写入 subject。没有明确功能范围时省略 scope，subject 只描述实际代码或文档变化。

默认只写 subject 一行。不加 body（除非改动原因无法从 diff 看出）、不加 `Co-Authored-By` 等 trailer、不加额外 commentary。

## 学习循环 📖

本项目是 **学习项目**：通过手写一个 Java 版简易 Claude Code 来掌握 coding agent
的构造。参考课程 <https://learn.shareai.run/zh>（Python 实现，20 课递进，s01 → s20）。

**代码是副产品，学习效果才是目标。** 一切流程决策以「用户学到多少」为准，不以「多快写完」为准。

### 每课的循环

1. **课程基线** —— 完整阅读当前课的网站讲解、源码与深入内容，提取完整功能清单和可验证的验收标准。网站决定「必须有什么」。
2. **Java 设计 Grill** —— 使用 `$grill-with-docs` 逐项审议 Python → Java 的映射。事实与 SDK 能力由 agent 查证，设计决定由用户做；
   frontier 清空且用户确认形成共识后才能实现。
3. **功能对等实现** —— 用自然的 Java 设计完整实现当前课功能，不遗漏，也不提前混入后续课次能力。
4. **自动验证** —— 使用 Maven 完成静态检查、编译和关键路径验证，确认实现满足第 1 步的全部验收标准。
5. **用户 Debug** —— agent 提供断点、关键变量、调用链和观察问题，由用户亲自 Debug；用户确认能复述机制后才算本课完成。
6. **对照复盘** —— 逐项记录 Python 与 Java 的行为映射、设计差异及理由。
7. **沉淀** —— 复盘结论写入该课票文件的 `## Comments`，必要时再提炼领域术语或 ADR。

### 功能对等约束 ⛔

- 当前课网站是功能范围和行为的基准，课程已有功能必须全部保留。
- 「最小改动」只表示不实现后续课次功能，不表示删减当前课功能。
- 不逐行翻译 Python；优先采用自然、类型安全、可维护的 Java 表达。
- 任何无法功能对等的地方必须在实现前说明，并在复盘中记录差异和原因。
- 用户未确认 Debug 完成前，不进入下一课。

### 沉淀去处

| 内容                   | 去处                                                  |
|------------------------|-------------------------------------------------------|
| 流程与规则             | 本文件                                                |
| 领域术语               | `CONTEXT.md`                                          |
| 难以逆转的决策及其理由 | `docs/adr/`                                           |
| 每课 diff 复盘         | `.scratch/nano-agent/issues/NN-*.md` 的 `## Comments` |

仅当复盘结论构成硬决策时，才从票中提炼为 ADR。 **不要每课都写 ADR。**

### 工具链约定

本机 `java` 为 JDK 26，Maven 运行在 JDK 21。 **一律用 `mvn` 编译与运行，不使用裸 `java` 命令**，避免编译期与运行期版本不一致。详见
`docs/adr/0001-技术栈-java21-maven-官方sdk.md`。

## Agent skills

- **Issue tracker** —— issues 与 spec 以 markdown 存放在 `.scratch/<feature-slug>/`。见 `docs/agents/issue-tracker.md`。
- **Triage labels** —— 沿用五个规范角色的默认标签（needs-triage / needs-info / ready-for-agent / ready-for-human /
  wontfix）。见 `docs/agents/triage-labels.md`。
- **Domain docs** —— single-context：根目录 `CONTEXT.md` + `docs/adr/`。见 `docs/agents/domain.md`。
