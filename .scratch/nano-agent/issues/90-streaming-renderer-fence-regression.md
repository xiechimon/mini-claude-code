# 90 流式渲染回归：未闭合代码块导致已完成行被重复打印

Type: task

Status: resolved

> 非课次票。s02/s03 之后手工试跑时发现的渲染 bug，编号从 90 起，避开 01–17 的课次号段。

## 现象

nano-agent 写出文件后用 fenced code block 展示内容时，围栏前后的行被打两遍：

```
before
before
FENCE```
  hello
  world
FENCE```
after
```

字节数也不对：write_file 报 `Wrote 67 bytes`，`ls` 显示 `145 bytes`。

## 根因

`MarkdownRenderer.render()` 每次增量都重 parse 整个 buffer、算 `completeCount`、赋给 `lastLineCount`。但
`renderBlockChildren` 在遇到未闭合的 `FencedCodeBlock` 时**故意不输出内容行和关闭边框**——见
`MarkdownRenderer.java:166-174` 的注释，说明是怕增量到来时行数变化让 `lastLineCount` 越界。

两个约束打起来了：

- 内容行暂不渲染 → 新解析出的 `lines` 列表比上一轮的 `lastLineCount` 还**短**
- `lastLineCount = completeCount` 把计数器跟着回退到更小的下标
- 下一段 delta 到达、围栏闭合、内容行才出现时，从回退后的下标开始打印 → 围栏前的行被重打

字符一次到达的最小复现：

```java
String md = "before\n```\nhello\nworld\n```\nafter\n";
for (int i = 0; i < md.length(); i++) r.append(md.substring(i, i+1));
r.flush();
```

字符 1 到达：`render()` 解析出 `["before"]`（围栏刚到一半，还没识别为 fence），`completeCount=0`，`lastLineCount=0`。
字符 2、3 到达同样。到 `\n` 后 `completeCount=1`，`lastLineCount=1`，输出 `before`。
然后 ``` 单独到达：parse 出 fenced block 但未闭合，`renderBlockChildren` 只输出 `border(```)`，
`newLines = ["border(```)"]`，长度 1，**`completeCount` 因为不换行 = `size - 1 = 0`**，
`lastLineCount` 从 1 回退到 0。
后续 `hello\n` 到达：parse 出 fence 内容行，`newLines = ["border(```)", "  hello"]`，
从 0 开始输出 → 重新打 `border(```)`，把 `hello` 当成下标 1 输出。
依此类推，围栏前的 `before` 也在某次回到 0 之后被重打。

## 修复

`MarkdownRenderer.java:137`：

```java
// 旧
lastLineCount = completeCount;
// 新
lastLineCount = Math.max(lastLineCount, completeCount);
```

monotonic only。围栏前后的已完成行一旦落定就不再回退；围栏内未闭合的暂存行等围栏闭合后从
更高下标继续追加。

字节数 bug 同步修：`WriteFileTool.java:48` 用 `codePointCount` 计 UTF-8 字节，
多字节字符下偏小。改 `getBytes(StandardCharsets.UTF_8).length`。

## 测试位置

加在 `MarkdownRendererTest`，与已有的 `strayClosingFenceAfterFlushIsSwallowed` 同款 seam：
直接 stream 字符、IdentityTheme、断言终端输出。**不**为这种 bug 单独建测试文件——
FenceRepro 跑通后即删。

`WriteFileToolTest.reportedByteCountIsUtf8Bytes` 同步更新：从原「字符数 2 bytes」断言改为
「UTF-8 6 bytes」，跟 `Files.readAllBytes(...).length` 对齐。

## Comments

### 2026-08-18 — 复盘

- 「临时吞行保护 lastLineCount」与「lastLineCount = completeCount」是两个独立写下的策略，
  合并时没考虑临时吞行会让 completeCount < 旧 lastLineCount 的场景。修法只追加单调性约束，
  不动临时吞行的策略。
- 测试用 chunk=1 流式输入而非单次整段，正是这个 bug 触发的关键 —— 整段输入时 fence 一开始就闭合，
  临时吞行策略根本不触发，bug 不复现。
- `WriteFileTool` 的字节口径在 s02 课程源码里就是 codePointCount，注释也明说「文案与实际字节数
  在多字节字符下并不一致」。复盘时看到这条注释时已在写 fix —— 修文案而非修计数，但保留「按 UTF-8
  字节」的契约更直接，少一层心智负担。
