# 02 s02 Tool Use（工具分发）课程基线

Type: research

Status: resolved

> 结论：s02 在 s01 的运行时上只做一件事——把「硬编码调用 `run_bash`」换成「按工具名查 `TOOL_HANDLERS` 表分发」，并新增
> `read_file` / `write_file` / `edit_file` / `glob` 四个文件类工具与 `safe_path` 工作区包含性校验，工具总数由 1 变 5。Agent
> Loop 的请求、`stop_reason` 判断、消息追加结构与 s01 逐行一致；变化的只有工具执行那几行、system prompt 的一个词、`run_bash`
> 的解码参数，以及终端预览由「打印命令」改为「打印工具名」。本文只记录网站当前版本的事实、行为和验收标准；不含任何 Java
> 方案，也不把「深入 CC 源码」或后续课次内容算进 s02。

## 研究范围与版本证据

- 研究对象：官网 s02 当前中文页面，核对日期为 2026-08-10。该页面共 **4 个 tab**：`学习` / `模拟` / `源码` /
  `深入探索`（不是三个）。[官网 s02](https://learn.shareai.run/zh/s02/)
- 全程锁定官方仓库固定提交 **`7b564c3ee6996039cb4e13a53024dfe2d4388d35`**（`main` 于 2026-07-28T17:27:46Z 的最新提交），下文所有
  GitHub 链接均指向该提交。
- 课程源码 `s02_tool_use/code.py`：**190 物理行**，SHA-256
  `c2f55dba14b1e9f7671369d1b279be6895547295eaf04b12b419bbd3b9bd2bc0`。[固定版本源码](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/code.py)
- 中文讲解 `s02_tool_use/README.md`：**222 行**，SHA-256
  `68a645eaf607e72e5437f206b204cdbef558165a11c77974dedd29ccf43ea883`。[固定版本讲解](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/README.md)
- **源码一致性（逐字节）**：把线上 `/_next/static/chunks/ad7bbf29c78e4be3.js` 中 `JSON.parse('…')` 的载荷解出
  UTF-8 字符串（先按 JS 单引号字符串解转义，再 `JSON.parse`，取 `versions[id="s02"].source`），其 SHA-256 为
  `c2f55dba14b1e9f7671369d1b279be6895547295eaf04b12b419bbd3b9bd2bc0`，与仓库文件**完全相等**。归一化方法：不做任何
  HTML 反转义或去高亮标签的手工处理，直接取渲染前的数据源字符串，因此不存在标签污染。
- **讲解一致性**：线上 `/_next/static/chunks/4db06eeaa211f81c.js` 中 `docs` 数据的 `{version:"s02", locale:"zh"}` 条目内容
  SHA-256 为 `0a1eea15a1261633d281a98f9d8dde9773f9cfb8aa673137bd8277ed8e48d9e1`，与固定提交下
  `web/src/data/generated/docs.json` 的同一条目**完全相等**。该条目相对 `README.md` 只有 3 处构建期改写（见下），正文其余部分逐字节一致。
- 讲解的 3 处构建期改写（`diff README.md` vs 站点内容，无其他差异）：删除 `[中文]·[English]·[日本語]` 语言切换行；
  `](../s03_permission/)` → `](/zh/s03)`；两处 `images/*.svg` → `/course-assets/s02_tool_use/*.svg`。改写规则见
  [extract-content.ts:173-207](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/web/scripts/extract-content.ts#L173-L207)。
- **卡片 LOC 口径**：官网卡片显示 `135 LOC`、`5 个工具`、`Tool dispatch map`。源码 190 物理行、153 非空行；按站点生成脚本
  `countLoc` 的规则（trim 后非空、且不以 `#` 开头）恰为 135 行，与 s01 的 102 口径相同。`深入探索` tab 显示的
  `+33 lines` 即 `135 - 102`，来自 `buildDiffs` 的 `locDelta`。因此 `135` 是展示统计口径，不能据此忽略 docstring 或注释所承载的运行约定。
  [countLoc:121-126](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/web/scripts/extract-content.ts#L121-L126) ·
  [locDelta:358](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/web/scripts/extract-content.ts#L358)
- **s01 基线未变**：固定提交下 `s01_agent_loop/code.py` 的 SHA-256 仍为
  `f0bed0e007fd4ab5307f61862d2ea5241e11918b177482a8a8a89674a9bd3334`，与 01 票锁定的 `1baf1ac…` 完全相同。因此
  [01 票](01-s01-agent-loop.md) 记录的 s01 基线在本课依然成立，本文只写增量。
- 配套配置文件在本提交下的 SHA-256：`.env.example` = `7e26b938ef5e9d5b867c4c1548bdd74366366422eaeaa9f72b929fa7e71a7f77`、
  `requirements.txt` = `afb62cc77dcf2c0e33a6c9ffb5cd0e416dfce9238e98356256898651aab7057c`；内容与 s01 时一致（`anthropic>=0.25.0`、
  `python-dotenv>=1.0.0`、`pyyaml>=6.0`，s02 源码同样未导入 PyYAML）。

## 一、课程要解决的问题

s01 的 Agent 只有一个 `bash`。讲解给出的问题陈述是：读文件要 `cat`，写文件要 `echo "..." > file.py`，改文件要 `sed`
；模型想的是「读这个文件」却必须拼出 `cat path/to/file`——多了一层翻译，浪费 token，还容易拼错。
[讲解 L12-16](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/README.md#L12-L16)

课程给出的解法只有一句：**加一个工具 = 在 `TOOLS` 数组加一条 + 在 `TOOL_HANDLERS` 字典加一行，循环不变**。

| 层面           | s01                                | s02                                          |
|----------------|------------------------------------|----------------------------------------------|
| 工具数量       | 1（`bash`）                        | 5（`+read_file/write_file/edit_file/glob`）  |
| 工具执行       | 硬编码 `run_bash(block.input[...])`| `TOOL_HANDLERS[block.name](**block.input)`   |
| 路径安全       | 无                                 | `safe_path` 工作区包含性校验（仅 file tools）|
| 循环           | `while True` + `stop_reason`       | 结构与 s01 一致                              |

上表为官网「相对 s01 的变更」原表。
[讲解 L124-131](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/README.md#L124-L131)

这与 Claude Messages API 的客户端工具协议一致：`tools` 数组里每个工具由 `name` / `description` / `input_schema` 声明，模型返回
`tool_use` 块，调用方执行后用只含 `tool_result` 块的 `user`
消息继续会话。[Anthropic：Implement tool use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/implement-tool-use) ·
[Anthropic：Handle tool calls](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)

## 二、完整功能清单（相对 s01 的增量）

### 2.1 配置与启动的变化

s01 的配置流程（`load_dotenv(override=True)`、Base URL 非空时删除 `ANTHROPIC_AUTH_TOKEN`、`MODEL_ID` 无默认值、可选
`readline` 四条绑定）全部保留，只有三处变化：

1. 新增 `from pathlib import Path`，并在模块初始化时把工作目录**捕获成常量** `WORKDIR = Path.cwd()`。s01 是在每次
   `subprocess.run` 时现取 `os.getcwd()`；s02 改为启动时取一次并全程复用。语义差异只在「进程 cwd 中途被改变」时才可见，源码本身不改
   cwd。[源码 L17,L35](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/code.py#L35-L37)
2. system prompt 由 `Use bash to solve tasks.` 改为 **`Use tools to solve tasks.`**，完整文本为
   `You are a coding agent at {WORKDIR}. Use tools to solve tasks. Act, don't explain.`
   。[源码 L39](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/code.py#L39)
3. **Python 版本下限抬高到 3.10**：`run_read` 的注解 `limit: int | None = None`（PEP 604，函数注解在定义时求值）、
   `glob.glob(..., root_dir=...)`（3.10 新增）都要求 3.10+。实测在 Python 3.9 下 `def f(limit: int | None = None)` 直接抛
   `TypeError: unsupported operand type(s) for |`。s01 无此约束。仓库 `requirements.txt` 未声明 Python 版本，这是源码隐含要求。

`.env.example` 与 `requirements.txt` 无变化，`.env` 中仍是 `ANTHROPIC_API_KEY`（必填）、`MODEL_ID`（必填）、
`ANTHROPIC_BASE_URL`（可选）。[.env.example](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/.env.example)

### 2.2 工具声明：从 1 个到 5 个

`TOOLS` 是一个 5 元素 list，每项都是 `{name, description, input_schema}`。均未声明 `additionalProperties: false`
。[源码 L121-132](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/code.py#L121-L132)

| name         | description（逐字）                      | properties                                          | required                        |
|--------------|------------------------------------------|-----------------------------------------------------|---------------------------------|
| `bash`       | `Run a shell command.`                   | `command: string`                                   | `command`                       |
| `read_file`  | `Read file contents.`                    | `path: string`, `limit: integer`                    | `path`                          |
| `write_file` | `Write content to a file.`               | `path: string`, `content: string`                   | `path`, `content`               |
| `edit_file`  | `Replace exact text in a file once.`     | `path: string`, `old_text: string`, `new_text: string` | `path`, `old_text`, `new_text` |
| `glob`       | `Find files matching a glob pattern.`    | `pattern: string`                                   | `pattern`                       |

`bash` 的声明与 s01 逐字相同。`read_file` 的 `limit` 是**唯一的可选参数**。

### 2.3 `safe_path`：工作区包含性校验

`safe_path(p: str) -> Path`
只有三行，是四个文件类工具唯一的安全边界。[源码 L66-70](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/code.py#L66-L70)

1. `(WORKDIR / p).resolve()`：按 `pathlib` 语义，若 `p` 是绝对路径则**直接取 `p` 本身**（不是拼接）；`resolve()`
   会规范化 `..` 并**解析符号链接**。[Python pathlib](https://docs.python.org/3/library/pathlib.html)
2. `if not path.is_relative_to(WORKDIR): raise ValueError(f"Path escapes workspace: {p}")`——异常消息中回显的是**原始输入串**，不是解析后的路径。
3. 该函数**抛异常**，不返回错误串；错误串是各工具自己 `except Exception` 转换出来的。

实测行为（在临时工作区 `/private/tmp/s02probe/ws` 下，含指向 `/etc` 的符号链接 `etclink`）：

| 输入                | 结果                                            |
|---------------------|-------------------------------------------------|
| `a.txt`             | `…/ws/a.txt`                                    |
| `.`                 | `…/ws`（工作区自身通过，`is_relative_to` 自反）  |
| `sub/../a.txt`      | `…/ws/a.txt`（`..` 先规范化，通过）              |
| `../x`              | `ValueError: Path escapes workspace: ../x`      |
| `/etc/passwd`       | `ValueError: Path escapes workspace: /etc/passwd` |
| `etclink/passwd`    | `ValueError: Path escapes workspace: etclink/passwd`（符号链接逃逸被 `resolve()` 拦下） |

**`safe_path` 只保护 4 个文件类工具，`bash` 完全不受它约束**——官网在「接下来」一节明确写出这一点：`rm -rf /` 还是能跑（此处指
denylist 之外的破坏性命令，五项子串 denylist 仍在
`run_bash` 内保留）。[讲解 L153-157](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/README.md#L153-L157)

### 2.4 `read_file` 执行行为

`run_read(path: str, limit: int | None = None) -> str`
。[源码 L73-80](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/code.py#L73-L80)

1. `safe_path(path).read_text()`：**未指定 encoding**，因此使用 Python 的 locale 默认编码；解码失败会抛异常并被转为
   `Error: …`。实测（locale 为 UTF-8）读取 `b"\xff\xfe\x00abc"` 返回
   `Error: 'utf-8' codec can't decode byte 0xff in position 0: invalid start byte`。
2. `.splitlines()` 后 `"\n".join(...)`。因此结果被规范化：**丢失末尾换行**、`\r\n` 与 `\r` 变 `\n`，且
   `\x0b`、`\x0c`、`\x85`、`U+2028`、`U+2029` 等 Unicode 行边界也会被当作换行切开（实测确认；普通空格不受影响）。
   [Python str.splitlines](https://docs.python.org/3/library/stdtypes.html#str.splitlines)
3. 截断判据是 `if limit and limit < len(lines)`。实测：
   - `limit=2`（文件 4 行）→ `l1\nl2\n... (2 more lines)`，末行是字面标记 `... ({len(lines) - limit} more lines)`。
   - `limit=4`（恰等于行数）→ **不截断**，无标记。
   - `limit=0` → falsy，**不截断**。
   - `limit=-1` → 截断为 `lines[:-1]`，标记算成 `... (5 more lines)`（`4-(-1)`），是源码算式的直接后果。
4. **不加行号**。工具描述、schema 与实现都没有行号概念。
5. 空文件返回**空字符串** `""`（不是 `(no output)` 一类占位符），空串会原样成为 `tool_result.content`。
6. 目录路径返回 `Error: [Errno 21] Is a directory: {绝对路径}`；不存在返回 `Error: [Errno 2] No such file or directory: {绝对路径}`
   ——注意这两类消息里回显的是**解析后的绝对路径**，而 `safe_path` 抛的越界消息回显的是原始输入。
7. `except Exception as e: return f"Error: {e}"`——捕获范围是 `Exception`，覆盖 `ValueError`、`OSError`、`UnicodeDecodeError` 等全部。

### 2.5 `write_file` 执行行为

`run_write(path: str, content: str) -> str`
。[源码 L83-90](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/code.py#L83-L90)

1. `file_path.parent.mkdir(parents=True, exist_ok=True)`：**自动创建多级父目录**。实测 `run_write('new/deep/c.txt','hi')` 成功。
2. `file_path.write_text(content)`：整文件覆盖写，未指定 encoding（locale 默认），无备份、无 diff、无「文件必须先读过」前置条件。
3. 成功返回 `f"Wrote {len(content)} bytes to {path}"`。**`len(content)` 是 Python 字符数，不是字节数**，文案与实际不符：实测写入
   `你好` 返回 `Wrote 2 bytes to u.txt` 而文件实际 6 字节；写入 `🙂` 返回 `Wrote 1 bytes to emo.txt` 而文件实际 4 字节。回显的
   `path` 是**原始输入串**。
4. 失败返回 `Error: {异常文本}`；越界返回 `Error: Path escapes workspace: {原始输入}`；目标是已存在目录返回
   `Error: [Errno 21] Is a directory: {绝对路径}`。

### 2.6 `edit_file` 执行行为

`run_edit(path: str, old_text: str, new_text: str) -> str`
。[源码 L93-102](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/code.py#L93-L102)

1. 先整文件 `read_text()`，再判断 `if old_text not in text`——**纯子串包含判断，不是正则，大小写敏感**。
2. 未命中时 `return f"Error: text not found in {path}"`（回显原始输入串）。这是**普通返回值，不是异常**，因此不经过
   `except` 分支，但仍以 `Error:` 开头。
3. 命中时 `text.replace(old_text, new_text, 1)`——**只替换第一处**。实测 `x x x` 中把 `x` 换 `Y` 得到 `Y x x`。
4. **不校验唯一性**：出现多次也不报错，静默只改第一处。
5. `old_text` 为空串时 `"" in text` 恒为 `True`，`replace("", new, 1)` 会把 `new_text` **插到文件开头**。实测 `abc` +
   `old_text=""`/`new_text="PRE"` → `PREabc`，返回 `Edited e.txt`。
6. 成功返回 `f"Edited {path}"`（原始输入串）。文件不存在返回 `Error: [Errno 2] …`。

### 2.7 `glob` 执行行为

`run_glob(pattern: str) -> str`
。[源码 L105-114](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/code.py#L105-L114)

1. `import glob as g` 是**函数体内的延迟导入**，唯一一处不在模块顶部的 import。
2. `g.glob(pattern, root_dir=WORKDIR)`：**未传 `recursive=True`**，因此 `**` 退化为单层 `*`。实测在含
   `top.py`、`sub/b.py`、`sub/deep/d.py` 的工作区：`**/*.py` 与 `*/*.py` 结果相同，都只有 `sub/b.py`，`sub/deep/d.py` 不被匹配。
   [Python glob](https://docs.python.org/3/library/glob.html)
3. 结果是**相对 `root_dir` 的相对路径**，顺序为文件系统枚举顺序（实测非字典序，未排序）。
4. **`run_glob` 不调用 `safe_path`**；包含性是**逐条结果**用 `(WORKDIR / match).resolve().is_relative_to(WORKDIR)` 过滤的。实测后果：
   - `/etc/host*`（绝对模式）→ `(no matches)`：glob 返回绝对路径，过滤器全部丢弃。
   - `etclink/host*`（经符号链接出去）→ `(no matches)`：`resolve()` 后落在 `/etc`，被丢弃。
   - `../*` → `../ws`：父目录的兄弟项被丢弃，但工作区自身 `resolve()` 后仍在工作区内，于是以 `../ws` 这种**带逃逸外观的相对路径**返回。
     即：**内容不会泄漏，但模式本身不受限制，返回串可以带 `../` 前缀**。
5. 隐藏文件遵循 glob 默认规则：`*.py` 不匹配 `.hidden.py`，`.*` 才匹配。
6. 无匹配返回字面量 `(no matches)`；非法模式如 `[` 不报错，实测同样返回 `(no matches)`。
7. `except Exception as e: return f"Error: {e}"` 仍在，只是常规路径下不易触发。

### 2.8 `bash` 执行行为的两处变化

`run_bash` 的 denylist（`rm -rf /`、`sudo`、`shutdown`、`reboot`、`> /dev/`，大小写敏感纯子串）、`shell=True`、
`capture_output=True`、`timeout=120`、`(stdout + stderr).strip()`、`[:50000]`、`(no output)`、
`Error: Timeout (120s)`、`Error: {e}` 全部与 s01
逐字相同。[源码 L46-59](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/code.py#L46-L59)

只有两处改动：

1. `cwd=os.getcwd()` → `cwd=WORKDIR`（启动时捕获的常量，见 2.1）。
2. 新增 **`encoding="utf-8", errors="replace"`**。s01 只有 `text=True`，走 locale 默认解码，遇到非法字节会抛
   `UnicodeDecodeError`；s02 固定 UTF-8 并把非法字节替换为 U+FFFD，因此子进程输出**不再因编码问题失败**。
   [Python subprocess](https://docs.python.org/3/library/subprocess.html)

其余 s01 结论继续成立：每次调用新建独立 shell 子进程（`cd`/`export` 不跨调用）、不设 `check=True`、非零退出码不算工具错误、返回值永远是普通字符串且不设
`is_error`。

### 2.9 `TOOL_HANDLERS` 分发映射

```python
TOOL_HANDLERS = {
    "bash": run_bash, "read_file": run_read, "write_file": run_write,
    "edit_file": run_edit, "glob": run_glob,
}
```

[源码 L138-141](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/code.py#L138-L141)

`TOOLS`（给模型看的声明）与 `TOOL_HANDLERS`（给运行时用的实现）是**两份独立数据**，源码没有任何校验保证二者的键集合一致。这是官网「加一个工具 =
两条定义」的直接表述。

### 2.10 Agent Loop 的变化

`agent_loop(messages: list)` 的整体结构与 s01
逐行对应：非流式 `client.messages.create(model=MODEL, system=SYSTEM, messages=messages, tools=TOOLS, max_tokens=8000)` →
追加 assistant 消息 → `if response.stop_reason != "tool_use": return` → 遍历 content 执行工具 → 汇总成一条 `user` 消息 →
回到循环顶。没有轮次上限、没有总时长上限、没有 API 异常兜底。
[源码 L150-170](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/code.py#L150-L170)

工具执行那一段有四处逐字变化：

| # | s01                                              | s02                                                             |
|---|--------------------------------------------------|-----------------------------------------------------------------|
| 1 | `print(f"\033[33m$ {block.input['command']}\033[0m")` | `print(f"\033[33m> {block.name}\033[0m")` —— 黄色输出**工具名**，不再输出参数 |
| 2 | `output = run_bash(block.input["command"])`      | `handler = TOOL_HANDLERS.get(block.name)`                       |
| 3 | （无）                                            | `output = handler(**block.input) if handler else f"Unknown: {block.name}"` |
| 4 | `print(output[:200])`                            | `print(str(output)[:200])`                                       |

由此得到 s02 特有的运行时事实：

1. **终端不再显示工具参数**，只显示工具名。要看命令内容只能看后面的 200 字符输出预览。
2. **未知工具名不抛异常**：`.get()` 返回 `None` 时把字面串 `Unknown: {name}` 当作工具结果回填，循环继续。s01 没有这条路径（它不看
   `block.name`）。
3. **参数形状错误会终止进程**：`handler(**block.input)` 是 kwargs 展开，`agent_loop` 外层没有 `try`，REPL 也只在 `input()`
   处捕获 `EOFError`/`KeyboardInterrupt`。实测 `run_read(**{"path":"top.py","bogus":1})` 抛
   `TypeError: run_read() got an unexpected keyword argument 'bogus'`，`run_read(**{})` 抛
   `TypeError: run_read() missing 1 required positional argument: 'path'`；两者都会以 traceback 终止程序。
   这与 s01 的 `block.input["command"]` 触发 `KeyError` 属同类「不 fallback、直接暴露」策略，但触发条件更宽（多传一个字段就会崩）。
4. 送回模型的仍是**完整**结果串（`bash` 上限 50,000 字符，文件类工具无额外上限），终端预览仍是 200 字符。
5. `tool_result` 结构不变：`{type, tool_use_id: block.id, content: output}`，**不设 `is_error`**，即使 `output` 以
   `Error:` 开头。[Anthropic：工具错误字段](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)
6. 同一响应中的多个 `tool_use` 块按 `response.content` 原始顺序**同步串行**执行，全部结果放进紧随其后的同一条 `user`
   消息。官网明确写出这是教学版取舍，CC 的做法是分 batch
   并发。[讲解 L105-109](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/README.md#L105-L109)

典型历史形状（与 s01 同构，只是 `tool_use` 可以是异构工具）：

```text
user      content = 用户问题字符串
assistant content = [text?/tool_use(id, name∈5 个工具, input=对应 schema), ...]
user      content = [tool_result(tool_use_id=id, content=完整工具返回值), ...]
assistant content = [text ...]  # stop_reason 非 tool_use，agent_loop 返回
```

### 2.11 REPL 的变化

s01 的全部 REPL 行为保留：只在主程序运行时进入、`strip().lower()` 后命中 `""`/`q`/`exit` 即退出且不入历史、EOF 与 Ctrl-C
静默退出、普通输入按原串（不 strip）入历史、`history` 只创建一次因此多轮共享上下文、每轮结束后打印所有 `text` 块并追加空行。
[源码 L173-190](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/code.py#L173-L190)

三处变化：

1. 启动横幅第一行改为 `s02: Tool Use — 在 s01 基础上加了 4 个工具`，第二行仍是 `输入问题，回车发送。输入 q 退出。` 后跟空行。
2. 提示符改为青色 `s02 >> `。
3. **移除了 s01 的 `isinstance(response_content, list)` 守卫**，直接 `for block in history[-1]["content"]`。由于
   `agent_loop` 必定在追加 assistant 消息（其 content 为 SDK content block 列表）之后才 `return`，实际可观察行为与 s01
   相同；这是一处防御性代码的删除，不是行为变更。

### 2.12 讲解正文的示意代码与实际源码的差异

官网「学习」tab 的 Python 片段是**简化示意**，与 `code.py` 不一致。验收必须以 `code.py` 为准。
[讲解 L55-99](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/README.md#L55-L99)

| 讲解片段                                     | 实际 `code.py`                                                   |
|----------------------------------------------|-------------------------------------------------------------------|
| `run_read`：`if limit: lines = lines[:limit]` | `if limit and limit < len(lines)`，且追加 `... (N more lines)` 标记 |
| `run_write`：直接 `write_text`                | 先 `parent.mkdir(parents=True, exist_ok=True)`                     |
| `run_edit`：`return "Error: text not found"`  | `return f"Error: text not found in {path}"`                        |
| `run_glob`：直接 `"\n".join(g.glob(...))`     | 逐条包含性过滤 + 空结果 `(no matches)`                             |
| 分发：`handler = TOOL_HANDLERS[block.name]`   | `TOOL_HANDLERS.get(...)` + `Unknown: {name}` 兜底                  |
| 四条 description 文案（`Write content to file.` / `Replace text in file once.` / `Find files by pattern.`） | `Write content to a file.` / `Replace exact text in a file once.` / `Find files matching a glob pattern.` |
| 四个工具函数无 `try/except`                   | 四个都有 `except Exception as e: return f"Error: {e}"`             |

### 2.13 教学演示场景与安全提示

运行方式：`cd learn-claude-code` 后 `python s02_tool_use/code.py`。官网给出四个观察用 prompt：

1. `Read the file README.md and tell me what this project is about`
2. `Create a file called test.py that prints "hello", then read it back`
3. `Find all Python files in this directory`
4. `Read both README.md and requirements.txt, then create a summary file`

官网点明观察重点：**模型什么时候只调一个工具、什么时候一次调多个；多个工具调用的顺序和结果是否正确**。
[讲解 L135-149](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/README.md#L135-L149)

安全提示：file tools 受 `safe_path` 保护，**但 `bash` 不受限制**；真正的权限门在 s03。s01 的「放在临时测试目录运行」提示继续适用。

## 三、输入输出行为总表

只列 s02 新增或改变的边界；s01 的边界（配置、REPL 退出、`stop_reason`、bash 常规执行）见 01 票。

| 边界        | 输入                                          | 可观察输出或状态变化                                                        |
|-------------|-----------------------------------------------|------------------------------------------------------------------------------|
| 模型请求    | 每轮请求                                      | `tools` 恒为 5 元素 `TOOLS`；`system` 含 `Use tools to solve tasks.`         |
| 工具分发    | `block.name` ∈ 5 个已注册名                   | 调用对应 handler，`**block.input` 展开为关键字参数                          |
| 工具分发    | `block.name` 不在映射中                       | 不执行、不抛错；结果为字面串 `Unknown: {name}`，循环继续                    |
| 工具分发    | `block.input` 多一个键 / 少一个必填键         | `TypeError` 未捕获 → traceback 终止进程                                     |
| 终端        | 任意 `tool_use`                               | 黄色 `> {tool_name}`（**不显示参数**），其后 `str(output)[:200]`            |
| `read_file` | 工作区内可读文本文件                          | `splitlines()` 后 `\n` 连接：丢末尾换行、CRLF 归一、Unicode 行边界也切分     |
| `read_file` | `limit=N` 且 `N < 行数`                       | 前 N 行 + 末行 `... (行数-N more lines)`                                     |
| `read_file` | `limit=0`、`limit≥行数`、缺省                 | 不截断、无标记                                                               |
| `read_file` | 空文件                                        | 返回空字符串 `""`（无占位符）                                                |
| `read_file` | 非法编码 / 目录 / 不存在 / 越界               | `Error: {异常文本}`；越界文案为 `Error: Path escapes workspace: {原始输入}`  |
| `write_file`| 工作区内路径（父目录可缺失）                  | 递归建父目录后整文件覆盖；返回 `Wrote {字符数} bytes to {原始输入路径}`      |
| `write_file`| 越界路径                                      | 不写入；`Error: Path escapes workspace: {原始输入}`                          |
| `edit_file` | `old_text` 在文件中出现 ≥1 次                 | 只替换**第一处**，返回 `Edited {原始输入路径}`；出现多次不报错               |
| `edit_file` | `old_text` 未出现                             | 不改文件，返回 `Error: text not found in {原始输入路径}`                     |
| `edit_file` | `old_text` 为空串                             | 把 `new_text` 插到文件开头，返回 `Edited {path}`                             |
| `glob`      | 相对模式                                      | 相对 `WORKDIR` 的相对路径列表，`\n` 连接，未排序                             |
| `glob`      | 含 `**` 的模式                                | `**` 只匹配一层（未开 `recursive`）                                          |
| `glob`      | 绝对模式 / 经符号链接出界的模式               | 逐条包含性过滤后全部丢弃 → `(no matches)`                                    |
| `glob`      | `../*`                                        | 界外项被丢弃；工作区自身以 `../{名字}` 形式返回                              |
| `glob`      | 无匹配 / 非法模式                             | `(no matches)`                                                               |
| `bash`      | 输出含非 UTF-8 字节                           | 以 `errors="replace"` 解码为 U+FFFD，不再抛 `UnicodeDecodeError`（s01 会抛） |
| `bash`      | 任意命令                                      | **不经 `safe_path`**，工作区边界对 bash 无效                                 |
| 工具反馈    | 一轮内所有工具的完整返回值                    | 汇成一条 `user` 消息的 `tool_result[]`，顺序与 `tool_use` 一致，无 `is_error` |
| REPL        | 启动                                          | 横幅 `s02: Tool Use — 在 s01 基础上加了 4 个工具`，提示符 `s02 >> `          |

## 四、可验证验收标准

从官网讲解和固定源码逐项推出的最小、完整验收集。模型响应应使用 stub/fake 控制，以免把模型随机性误当作 harness 行为。
s01 已验收项（01 票 A–F）默认继续成立，此处只列 s02 新增或改变的断言。

### A. 配置

- [ ] 工作目录在启动时捕获一次并全程复用（`bash` 的 cwd 与 system prompt 中的目录来自同一次捕获）。
- [ ] system prompt 精确为 `You are a coding agent at {启动时工作目录}. Use tools to solve tasks. Act, don't explain.`。
- [ ] `.env`/环境配置语义（覆盖、Base URL 时删 `ANTHROPIC_AUTH_TOKEN`、`MODEL_ID` 无默认值）与 s01 完全一致，未因本课改变。

### B. 工具声明

- [ ] 发给模型的工具列表恰为 5 个：`bash`、`read_file`、`write_file`、`edit_file`、`glob`，顺序与 `TOOLS` 一致。
- [ ] 5 条 description 与源码逐字相同（含 `Write content to a file.`、`Replace exact text in a file once.`、
      `Find files matching a glob pattern.`）。
- [ ] 各 schema 的 properties 与 required 与 2.2 表一致；`read_file.limit` 是唯一可选参数且类型为 integer。
- [ ] `bash` 的声明与 s01 逐字未变。
- [ ] 每次模型请求仍携带相同 `TOOLS`、`SYSTEM`、配置的 `MODEL` 与 `max_tokens=8000`。

### C. 工具执行

**`safe_path` 边界**

- [ ] 工作区内相对路径、`.`、含 `..` 但最终落回区内的路径全部通过。
- [ ] `../x` 形式的越界路径被拒绝，错误串为 `Path escapes workspace: {原始输入}`。
- [ ] 绝对路径若不在工作区内被拒绝（不是拼接成 `WORKDIR/绝对路径`）。
- [ ] 指向工作区外的符号链接被拒绝（校验发生在解析符号链接之后）。
- [ ] `bash` 不经过该校验。

**`read_file`**

- [ ] 正常读取按行重组，末尾换行丢失、CRLF 归一为 LF。
- [ ] `limit < 行数` 时返回前 N 行并追加 `... ({行数-N} more lines)`；`limit == 行数`、`limit=0`、缺省时均不截断且无标记。
- [ ] 空文件返回空字符串，不返回任何占位文本。
- [ ] 不存在、目录、解码失败、越界四类失败都返回以 `Error:` 开头的普通字符串，不抛出到调用方。
- [ ] 输出**不含行号**。

**`write_file`**

- [ ] 缺失的多级父目录被自动创建。
- [ ] 已存在文件被整体覆盖，无备份、无「先读后写」前置条件。
- [ ] 成功文案为 `Wrote {字符数} bytes to {原始输入路径}`（多字节字符下该数字与实际字节数不同，属课程既有行为）。
- [ ] 越界路径不产生任何写入副作用。

**`edit_file`**

- [ ] 命中时只替换第一处，其余相同文本保持不变。
- [ ] 未命中时文件字节不变，返回 `Error: text not found in {原始输入路径}`。
- [ ] 匹配是精确子串、大小写敏感，不做正则或空白归一。
- [ ] 空 `old_text` 把 `new_text` 插到文件开头并返回成功文案。
- [ ] 成功文案为 `Edited {原始输入路径}`。

**`glob`**

- [ ] 返回相对工作目录的相对路径，多条以 `\n` 连接。
- [ ] `**` 不递归（与单层 `*` 等价）。
- [ ] 绝对模式与经符号链接出界的模式因逐条包含性过滤而得到 `(no matches)`。
- [ ] `*` 不匹配隐藏文件，`.*` 匹配。
- [ ] 无匹配与非法模式均返回 `(no matches)`，不抛错。

**`bash`**

- [ ] 五项 denylist、120 秒超时、50,000 字符上限、`(no output)`、`stdout+stderr` 拼接后整体 strip 等 s01 行为逐条未变。
- [ ] 子进程输出以 UTF-8 解码且非法字节被替换，不因编码抛异常。

### D. 分发与循环

- [ ] 工具执行入口是「按 `block.name` 查表」，而非任何硬编码分支。
- [ ] 声明表与实现表是两份数据；新增一个工具只需各加一条，循环代码不动。
- [ ] 未注册的工具名不抛异常，回填 `Unknown: {name}` 并继续循环。
- [ ] `block.input` 多余字段或缺失必填字段导致错误直接暴露、不被静默吞掉（不得 fallback 成 `Error:` 文本）。
- [ ] 一个 assistant 轮内的多个 `tool_use` 按响应顺序**串行**执行，不并发。
- [ ] 全部结果放进紧随其后的同一条 `user` 消息，`tool_use_id` 一一对应且顺序一致。
- [ ] 每个 `tool_result.content` 是工具完整返回值；即使以 `Error:` 开头也不设置 `is_error`。
- [ ] `stop_reason != "tool_use"` 时立即结束，不重试、不恢复；无轮次或时长上限。

### E. REPL 与终端输出

- [ ] 启动横幅为 `s02: Tool Use — 在 s01 基础上加了 4 个工具` + `输入问题，回车发送。输入 q 退出。` + 空行。
- [ ] 提示符为青色 `s02 >> `。
- [ ] 每次工具执行前以黄色输出 `> {工具名}`，**不输出工具参数**。
- [ ] 执行后终端预览不超过 200 字符，而回填模型的内容不受该 200 字符限制。
- [ ] 退出词、EOF/Ctrl-C、原串入历史、跨轮共享 history、末尾打印 text 块 + 空行等 s01 行为未变。

### F. 端到端关键路径（官网四个示例）

- [ ] `Read the file README.md and tell me what this project is about`：模型经 `read_file` 取得内容并给出最终文本响应。
- [ ] `Create a file called test.py that prints "hello", then read it back`：`write_file` 后 `read_file` 能读回同一内容（同一轮或相邻轮）。
- [ ] `Find all Python files in this directory`：`glob` 返回工作区内相对路径并回填模型。
- [ ] `Read both README.md and requirements.txt, then create a summary file`：能观察到**同一 assistant 轮返回多个 `tool_use`**，
      按原始顺序串行执行，结果在同一条 `user` 消息中一一对应回填。
- [ ] 四个场景都能明确观察到：收到 `tool_use` 时循环继续，最终非 `tool_use` 时循环停止。

## 五、Python 特有实现点

以下是 s02 新引入的 Python/库级表达或运行特征，用于理解现有基准，不是 Java 设计建议（s01 的条目见 01 票）。

| Python 特有点                         | 当前源码中的具体含义                                                                 |
|---------------------------------------|----------------------------------------------------------------------------------------|
| `pathlib.Path` + `/` 运算符           | `WORKDIR / p` 在 `p` 为绝对路径时返回 `p` 本身，而非拼接；这是 `safe_path` 的关键前提   |
| `Path.resolve()`                      | 同时做 `..` 规范化与符号链接解析，因此符号链接逃逸被拦下                                |
| `Path.is_relative_to()`               | 纯路径前缀比较（3.9+），自反：工作区自身通过                                            |
| `int \| None` 注解（PEP 604）         | 函数注解在定义时求值，把最低 Python 版本抬到 3.10                                       |
| `glob.glob(root_dir=...)`             | 3.10 新增参数；未传 `recursive=True` 时 `**` 退化为单层                                 |
| 函数体内 `import glob as g`           | 唯一的延迟导入，与模块顶部 import 风格不一致                                            |
| `dict.get()` + 真值判断               | 未知工具名走 `Unknown: {name}` 分支而非 `KeyError`                                       |
| `handler(**block.input)`              | 字典 kwargs 展开：形参名即协议字段名，多传/漏传都是未捕获的 `TypeError`                 |
| 一等函数放进 dict                     | `TOOL_HANDLERS` 直接存函数对象；声明表与实现表无静态关联                                 |
| `str.splitlines()`                    | 按 Unicode 行边界切分（含 `\x0b\x0c\x85U+2028U+2029`），比「按 `\n` 切」更宽             |
| `Path.read_text()` / `write_text()` 无 encoding | 走 locale 默认编码；解码失败抛异常并被转成 `Error:` 串                          |
| `len(str)`                            | 返回字符数，却被写进 `Wrote {n} bytes` 文案                                              |
| `str.replace(old, new, 1)`            | 只替换第一处；`old` 为空串时在开头插入                                                  |
| `except Exception`                    | 四个文件工具统一把任意异常转成 `Error: {e}` 串；`agent_loop` 层无此兜底                 |
| `subprocess.run(encoding=, errors=)`  | 覆盖 `text=True` 的 locale 解码，非法字节替换为 U+FFFD                                   |
| 移除 `isinstance(..., list)` 守卫     | 依赖「`agent_loop` 返回时最后一条必为 assistant content 列表」这一不变量                |

## 六、深入探索完整记录

本节完整保留官网深入内容。它们是课程对生产级 Claude Code（CC）源码的比较说明，**不是 s02 教学程序已具备的功能，不得纳入 s02 验收**。

官网的深入内容分布在**两个位置**，需要分开看：

- **「学习」tab 末尾的可折叠块 `深入 CC 源码`**——即 README 的 `<details>` 段落，共六节，是本课真正的「深入探索」正文。
  [讲解 L159-220](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/README.md#L159-L220)
- **「深入探索」tab**——由站点数据生成的结构化视图（执行流程图、架构、新增内容、设计决策），不含上述六节文字。

官网称以下内容基于 CC 源码 `Tool.ts`、`tools.ts`、`toolOrchestration.ts`、`toolExecution.ts`、`StreamingToolExecutor.ts` 的核查。

### 6.1 工具定义方式

- 教学版：`TOOLS` 数组 + `TOOL_HANDLERS` 字典，定义与实现分开。
- 官网称 CC 中每个工具是 `buildTool()` 创建的独立对象，包含 schema、验证、权限、执行；`getAllBaseTools()` 汇总所有工具。
- 官网自评：教学版的分离方式对教学更清晰——读者一眼看到「加一个工具 = 两条定义」。

### 6.2 并发安全判断：`isConcurrencySafe()`

教学版按原始顺序逐个执行，不做并发。官网称 CC 用 `isConcurrencySafe(input)` 判断能否并发，且**按具体输入判断**，不是简单的「只读 vs 写」：

|             | `isReadOnly` | `isConcurrencySafe` |
|-------------|--------------|----------------------|
| FileRead    | true         | true                 |
| Glob        | true         | true                 |
| Bash `ls`   | true         | **true** ← 关键差异  |
| Bash `rm`   | false        | false                |
| TaskCreate  | false        | **true**（改状态但可并发；官网称 TaskCreate 在 s12 介绍） |

官网称 CC 的 Bash tool 的 `isConcurrencySafe` 等于 `isReadOnly`——只读命令可并发、写命令不可；TaskCreate 虽改任务文件但每次写不同文件，故可并发。

### 6.3 分区算法

官网称 CC 的 `partitionToolCalls()`（其定位为 `toolOrchestration.ts:91-115`）不是分两组，而是把工具调用**按连续块分批**：

```text
[read A, read B, glob *.py, bash "rm x", read C]
  → batch1(并发): [read A, read B, glob *.py]
  → batch2(串行): [bash "rm x"]
  → batch3(并发): [read C]
```

官网称并发安全的连续块编入同一 batch，batch 内真正并发执行（其定位为 `toolOrchestration.ts:152-176`，有并发上限）；遇到非并发安全的就开新
batch 串行执行，batch 之间严格顺序。

### 6.4 验证管线

官网称 CC 的每个工具调用经过 5 步验证（`toolExecution.ts`），并给出行号定位：

1. **Zod schema 验证**（`614-680`）：参数类型/结构检查——官网称教学版用 JSON Schema 替代。
2. **工具级 `validateInput()`**（`682-733`）：参数值验证，如路径是否在工作区内。
3. **PreToolUse hooks**（`800-862`，官网称 s04 详细介绍）：钩子可返回消息、修改输入、阻止执行。
4. **权限检查**（`921-931`，官网称是 s03 的核心内容）：`canUseTool` + `checkPermissions` → allow/deny/ask。
5. **执行 `tool.call()`**（`1207-1222`）。

官网自评：教学版省略了 Zod（用 JSON Schema）、省略了 `validateInput`（用安全函数），保留了权限检查和钩子**概念**——注意「保留概念」指后续课次，s02
本身既无权限也无钩子。

### 6.5 流式工具执行

官网称 CC 的 `StreamingToolExecutor`（`StreamingToolExecutor.ts`）让工具在模型还在生成时就启动，`read_file`
可能在模型还在输出「我来分析」时就跑完了。官网称教学版不实现这个，目标和 s01 一致——概念清晰，不追求性能极致。

### 6.6 工具结果持久化

官网称 CC 每个工具有一个 `maxResultSizeChars` 字段，结果超过该值就落盘，模型看到的是预览 + 文件路径；FileRead 特殊，设为
`Infinity`，以防读文件的输出又被当成文件落盘——否则模型下次读那个落盘文件时又会触发落盘，形成无限循环。

s02 没有任何结果落盘机制：`bash` 的 50,000 字符是**硬截断**，文件类工具则完全无上限。

### 6.7 「深入探索」tab 的结构化视图

该 tab 由站点数据生成，内容为：

- **执行流程**：`User Input → LLM Call → tool_use? →(yes) Tool Dispatch → bash / read / write / edit → Append Result → LLM Call`，
  `no` 分支到 `Output`。数据源
  [execution-flows.ts:32-51](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/web/src/data/execution-flows.ts#L32-L51)。
- **架构**：`No classes in this version (functions only)`，工具列 `bash / read_file / write_file / edit_file / glob`。
- **新增内容**：新增工具 4 个（`read_file`/`write_file`/`edit_file`/`glob`）、新增函数 5 个
  （`safe_path`/`run_read`/`run_write`/`run_edit`/`run_glob`）、代码量差异 `+33 lines`。
- **设计决策**：三条，来自
  [annotations/s02.json](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/web/src/data/annotations/s02.json)：
  「为什么恰好四个工具」「模型本身就是代理」「每个工具都有 JSON Schema」。

**这三条设计决策与 s02 源码存在事实冲突，不得作为验收依据**（成因见 8.3）：

| 决策文案称                                        | s02 源码实际                                             |
|---------------------------------------------------|-------------------------------------------------------------|
| 「四个工具分别是 bash、read_file、write_file 和 edit_file」 | 共 5 个，含 `glob`                                    |
| 「read_file 提供带行号的精确文件读取」            | `run_read` 不产生任何行号                                    |
| 「edit_file 要求 `old_string` 和 `new_string`」   | schema 字段名是 `old_text` / `new_text`                      |
| 「API 会在执行前校验 schema，模型无法传递格式错误的输入」 | 源码无本地校验；实测多传一个键即 `TypeError` 终止进程 |

### 6.8 「模拟」tab

该 tab 是 7 步的静态演示（用户请求 → `read_file` → `write_file` → 最终文本），数据源
[scenarios/s02.json](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/web/src/data/scenarios/s02.json)。
其中的工具结果文案是**虚构示意**（如 `File written successfully`，而 `run_write` 实际返回 `Wrote N bytes to {path}`），
描述文字也称「4 tools beat 1」。同样不作为验收依据。

## 七、明确不属于本课的能力

判断依据：固定提交下的课程目录清单（s01–s20）、官网左侧课程导航，以及各课 README 的「接下来」指向。

**紧邻的下一课（s03 Permission）**——官网「接下来」明确写「s03 → 在工具执行之前加一道门：这个操作安全吗？需要用户批准吗？」。
实测固定提交下 `s03_permission/code.py` 的 `TOOLS` **仍是同样的 5 个工具**，新增的是三道门（硬拒绝清单 / 规则匹配 / 用户批准）与循环中的
`if not check_permission(block): continue`。因此以下能力**必须留给 s03，不得在 s02 出现**：

- 工具执行前的权限判定、规则匹配、`allow/deny/ask` 三态
- 交互式用户批准、暂停等待确认
- 「写到工作区外」「破坏性命令」等在 `bash` 上的额外拦截（s02 的 `bash` 只有 s01 那五项子串 denylist）

**更后续课次**（目录主题依次为）：s04 Hooks、s05 TodoWrite、s06 Subagent、s07 Skill Loading、s08 Context Compact、s09 Memory、
s10 System Prompt、s11 Error Recovery、s12 Task System、s13 Background Tasks、s14 Cron Scheduler、s15 Agent Teams、
s16 Team Protocols、s17 Autonomous Agents、s18 Worktree Isolation、s19 MCP/Plugin、s20 Comprehensive。对应的以下能力均不属于 s02：

- hooks（PreToolUse/PostToolUse/Stop 等）、TodoWrite、subagent、skills
- 上下文压缩、持久记忆、system prompt 动态分段装配
- 错误恢复、重试、fallback model、`max_tokens` 恢复、轮次上限
- task system、后台任务、cron、agent teams、协议、自主认领、worktree 隔离、MCP/plugin

**「深入探索」中的 CC 生产机制**同样不属于 s02：

- `isConcurrencySafe` 判定、`partitionToolCalls` 分批、batch 内并发与并发上限
- Zod 验证、工具级 `validateInput()`、5 步验证管线
- `StreamingToolExecutor` 流式工具执行、`maxResultSizeChars` 结果落盘
- `buildTool()` 式的「schema + 验证 + 权限 + 执行」聚合对象

**s01 已有、s02 不改的部分**（继续沿用 01 票基线，不在本课重新设计）：Agent Loop 的请求与终止条件、`bash` 的 denylist 与截断、
消息历史形状、REPL 退出语义、Effective Environment 配置语义。

**其他不属于本课的**：

- 任何形式的工具结果落盘、预览+路径回填
- `read_file` 的行号输出、文件必须先读后写的前置约束、`edit_file` 的唯一性校验或多处替换
- `glob` 的递归 `**`、结果排序、忽略规则（.gitignore 等）
- `grep` / `find` / `list_directory` / `http_request` 等第六个及以上的工具
- `is_error` 标记、工具级超时（除 `bash` 的 120 秒外）、并行工具执行

## 八、来源与可信度说明

### 8.1 功能基准（一手）

- [s02 当前官网页面](https://learn.shareai.run/zh/s02/)
- [固定版本 code.py](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/code.py)
- [固定版本 README.md](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s02_tool_use/README.md)
- [固定版本 .env.example](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/.env.example)
- [固定版本 requirements.txt](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/requirements.txt)
- [固定版本 s03_permission/code.py](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/s03_permission/code.py)（仅用于界定 s02 的上界）
- 站点数据与生成规则：
  [extract-content.ts](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/web/scripts/extract-content.ts) ·
  [versions.json](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/web/src/data/generated/versions.json) ·
  [docs.json](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/web/src/data/generated/docs.json) ·
  [execution-flows.ts](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/web/src/data/execution-flows.ts) ·
  [annotations/s02.json](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/web/src/data/annotations/s02.json) ·
  [scenarios/s02.json](https://github.com/shareAI-lab/learn-claude-code/blob/7b564c3ee6996039cb4e13a53024dfe2d4388d35/web/src/data/scenarios/s02.json)

### 8.2 API 与 Python 语义（一手）

- [Anthropic：Implement tool use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/implement-tool-use)
- [Anthropic：Handle tool calls](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)
- [Anthropic：Create a Message](https://platform.claude.com/docs/en/api/python/messages/create)
- [Anthropic：Stop reasons](https://platform.claude.com/docs/en/build-with-claude/handling-stop-reasons)
- [Python：pathlib](https://docs.python.org/3/library/pathlib.html)
- [Python：glob](https://docs.python.org/3/library/glob.html)
- [Python：str.splitlines](https://docs.python.org/3/library/stdtypes.html#str.splitlines)
- [Python：subprocess](https://docs.python.org/3/library/subprocess.html)

### 8.3 站点内部不一致（已定位成因，需在实现中忽略）

固定提交的仓库里除课程目录外，还存在一套 **legacy 路径 `agents/s02_tool_use.py`**（151 行，SHA-256
`b9acf74bb6e1d37915c47d8e5c56cbf9850fed885bb95ba1a70163574df4d9f5`）。实测该文件**只有 4 个工具**（`bash`/`read_file`/
`write_file`/`edit_file`，**无 `glob`**），description 文案也是旧版（`Write content to file.`、`Replace exact text in file.`）。
站点的**卡片元数据与「源码」tab** 取自 `s02_tool_use/code.py`（`versions[id="s02"].filename` 实测为
`s02_tool_use/code.py`），而**设计决策文案（`annotations/s02.json`）、页首「Tool Dispatch Map」可视化（渲染出的
`const handlers = { bash, read_file, write_file, edit_file }`，4 项）与执行流程图节点标签（`bash / read / write / edit`）
是围绕这套 legacy 4 工具版本写的**，故与 5 工具的课程源码冲突。

**结论：本课以 `s02_tool_use/code.py` 为唯一实现基准**；卡片的 `5 个工具` 与「深入探索/新增内容」的 4 个新工具一致，可信；
`annotations/s02.json`、页首可视化、`scenarios/s02.json`、以及讲解正文中的简化片段（见 2.12）都是示意或陈旧数据，不作为验收依据。

### 8.4 仍存在的来源限制

- 官网深入区引用并概述了 CC 的 `Tool.ts`、`tools.ts`、`toolOrchestration.ts`、`toolExecution.ts`、`StreamingToolExecutor.ts`
  与具体行号，但该页面没有提供对应上游文件、具体 CC 版本或可复现 commit 的第一方永久链接。因此本文完整记录官网六节内容，并用「官网称」标示；
  不会把这些行号与字段集合提升为不受版本约束的 Claude Code 公共契约。
- `README.md` 未声明最低 Python 版本，`requirements.txt` 也没有 `python_requires`。本文给出的「≥3.10」是由源码语法与 API
  推出并在本机 Python 3.9.6 / 3.14.6 上实测确认的结论，不是官网声明。
- `read_text()`/`write_text()` 未指定 encoding，其行为随运行环境的 locale 变化。本文实测环境
  `locale.getencoding()` 为 `UTF-8`；在非 UTF-8 locale 下读写行为会不同，源码未对此做任何约束。
- 本文所有 `run_read`/`run_write`/`run_edit`/`run_glob`/`safe_path` 的行为结论，均由固定提交源码的函数体原样抽取后在临时工作区
  实测得到（Python 3.14.6，macOS），未调用模型，不含推测。

## Comments

- 2026-08-10：完成官网四个 tab（学习 / 模拟 / 源码 / 深入探索）研究并锁定固定提交
  `7b564c3ee6996039cb4e13a53024dfe2d4388d35`；已核对线上源码与讲解与该提交逐字节一致，已定位并解释站点 4 工具/5
  工具不一致的成因。尚未进行 Python → Java 映射或任何实现。（后续复盘结论在此回填）
- 2026-08-10：Java 映射 Grill 第一轮达成共识。工具建模保留课程的两份数据结构：`List<Tool>` 声明表 + `Map<String, ToolHandler>`
  实现表，`ToolHandler` 为 `String execute(JsonValue)` 函数式接口，每个 file tool 是独立 package-private 类，`BashTool` 复用。
  工具输入解码去掉 `@JsonIgnoreProperties(ignoreUnknown = true)`，依赖 SDK `JsonMapper` 默认开启的 `FAIL_ON_UNKNOWN_PROPERTIES`
  抛错，必填字段由 record compact constructor 的 `requireNonNull` 抛 NPE，两者都不被捕获——这订正了 s01 第六轮「只读取 command」
  的误读。`safe_path` 采用尽力 realpath（向上找首个存在的祖先解析后拼回剩余段）。file tool 的 OS 异常用 `e.toString()` 渲染，
  `BashTool` 保持 `getMessage()` 不变。`read_file` 用 `String.lines()` 并接受 Unicode 行边界差异，负数 `limit` 按 Python 语义复刻。
  `glob` 自实现按段匹配（JDK `PathMatcher` 的 `**` 跨目录、`*` 匹配隐藏文件，与 Python 非 recursive 语义相反，不可直接用）。
  文件读写固定 UTF-8，`Wrote {N} bytes` 的 N 取 `codePointCount`。尚未授权实现。
- 2026-08-10：Java 映射 Grill 第二轮达成共识。新增 `Workspace` 深 Module 承载路径边界，暴露 `resolveInside(String)`（越界抛
  `IllegalArgumentException("Path escapes workspace: " + raw)`）与 `containsResolved(Path)`（供不走 `safe_path` 的 glob 逐条过滤）；
  工作目录在 `Main` 与 `Workspace` 构造处各 `toRealPath()` 一次，成因与理由见 ADR-0004。新增 `ToolRegistry` 持有两份表并注入
  `Workspace`，暴露 `definitions()` 与 `handler(String)`，`Unknown: {name}` 兜底留在 `AgentLoop` 循环内以保持与 Python 的逐行对照。
  `ToolHandler` 声明 `throws InterruptedException` 使中断沿调用链传播；错误边界固定为「解码在 try 之外、执行在 try 之内、
  `catch (IOException | RuntimeException)`」，选择该捕获宽度而非 `catch (Exception)`，是为了在类型层面保证不误吞中断。
  四个 file tool 各自独立成类，glob 分段匹配算法留作 `GlobTool` 私有方法。测试沿用 s01 分层，新增 `WorkspaceTest` 与四个 tool
  测试类。尚未授权实现。
- 2026-08-10：Java 映射 Grill 第三轮达成共识。`BashTool` 改为**实现** `ToolHandler` 并新增 `execute(JsonValue)` 契约入口（内部调用
  既有的 `execute(String)`），`BashInput` 下沉为其私有嵌套 record——属扩展而非替换，`BashTool` 核心实现与 `BashToolTest` 零改动，
  `ToolRegistry` 因此收敛成不含任何工具逻辑的纯组装点。本课对 s01 的全部侵入仅两处：删除 `AgentLoop` 的 `BashInput` 私有 record，
  以及 `AgentLoopTest` 因构造参数由 `BashTool` 改为 `ToolRegistry` 而调整。`CONTEXT.md` 新增 `Tool Definition`、`Tool Handler`、
  `Workspace` 三个术语并修订 `Bash Tool`（原定义称其为「s01 唯一暴露给模型的工具」，已不成立）；形成 ADR-0003（两份表不聚合）与
  ADR-0004（Workspace 尽力 realpath），`ignoreUnknown` 订正不单独立 ADR 而记入两张课程票。确认并接受四项功能不对等：
  `read_file` 不按 `\x0b`/`\x0c`/`\x85`/`U+2028`/`U+2029` 切行、file tool 错误文案为 Java 异常形态而非 errno 形态、
  文件读写固定 UTF-8 不跟随 locale、`.env` 不支持 `${VAR}` 展开（继承自 s01）。四项均不触及任何验收断言。尚未授权实现。
- 2026-08-10：用户确认完整 Java 映射共识，设计 frontier 已清空，进入功能对等实现。
- 2026-08-10：实现完成，`mvn clean verify` 通过 84 项测试（s01 为 40 项）。新增 `Workspace`、`ToolHandler`、`ToolRegistry`
  与四个 file tool；`BashTool` 扩展出 `execute(JsonValue)` 契约入口并把子进程解码改为 UTF-8；`AgentLoop` 改为查表分发。
  新增 `WorkspaceTest`、`ReadFileToolTest`、`WriteFileToolTest`、`EditFileToolTest`、`GlobToolTest`，
  `AgentLoopEndToEndTest` 扩充官网四个 s02 场景（原 s01 三个场景保留且继续通过）。
  实现期间发现并修正一处共识细节：Round 2 商定的 `Workspace` 解析步骤（向上找首个存在的祖先做 `toRealPath()` 后拼回）
  对**断链符号链接**存在逃逸漏洞——`ws/link` 指向工作区外的不存在路径时，该步骤把它判为区内，而 `Files.writeString`
  会顺着链接在工作区外创建文件。Python 的 `Path.resolve(strict=False)` 会解析断链符号链接因而不受影响。
  修正方式是在 `toRealPath()` 抛出时读取链接目标后按同样语义继续解析，并以 40 跳上限终止环形链接；
  `WorkspaceTest.danglingSymbolicLinkLeavingTheWorkspaceIsRejected` 覆盖该路径，ADR-0004 已记录。
  另确认 SDK 的 `JsonMapper` 未关闭 Jackson 默认开启的 `FAIL_ON_UNKNOWN_PROPERTIES`：
  `AgentLoopTest.unexpectedToolInputFieldIsExposedRatherThanIgnored` 断言异常消息包含多余字段名，
  且失败发生在第一次模型请求之后、没有产生第二次请求。等待用户 Debug 验收。
- 2026-08-12：双轴 code-review 完成（fixed point `ce6aa72`，改动当时全在工作区，故无 commit 区间）。**Spec 轴 1 项实质发现**：
  越界文案经 `"Error: " + failure` 的隐式 `toString()` 渲染成 `Error: java.lang.IllegalArgumentException: Path escapes
  workspace: …`，违反 C 组 `safe_path` 第 2 条与三、行为总表的逐字断言，且 `ReadFileToolTest`、`WriteFileToolTest`、
  `EditFileToolTest` 三处已把错误文案固化。成因是第一轮共识「file tool 的 OS 异常用 `e.toString()` 渲染」被统一套用到了
  `Workspace` 抛出的越界异常上，而第三轮把「错误文案为 Java 异常形态」列为可接受不对等时判断「不触及任何验收断言」，遗漏了越界文案
  是逐字断言项。修复方式是在通用错误边界之前增加 `catch (IllegalArgumentException escape)` 只回显 `getMessage()`，OS 异常保持
  `toString()`。`GlobTool` 不在修复之列——它调 `contains` 而非 `resolveInside`，本就没有越界路径（票 2.7 第 4 条），
  加 catch 会是死代码。Spec 轴未发现 scope creep；F 组四个端到端场景走 fake model，「真实模型自主选择工具」属未能确认而非缺失。
- 2026-08-12：**Standards 轴 2 项硬违规 + 5 项判断题**。头号硬违规「四个 file tool 的 `catch (IOException | RuntimeException)`
  违反 Fallback 原则，应收窄为只 catch `IOException`」经核实**驳回**：Python 基准是 `except Exception`（票 2.4 第 7 条），
  收窄会让 `Workspace` 抛的 `IllegalArgumentException` 逃出工具边界，直接违反 C 组「不存在、目录、解码失败、越界四类失败都返回以
  `Error:` 开头的普通字符串，不抛出到调用方」；Round 2 选择该宽度而非 `catch (Exception)` 正是为了在类型层面保证不误吞中断，
  理由成立。此处与 s01 的 blank Base URL 属同类情形：审查建议与已确认的功能对等决策冲突时，以课程基准为准。已采纳三项：
  `CONTEXT.md` 的 Workspace 定义由「判定任意已解析路径」改为「判定原始路径解析后是否落在区内」以匹配 `contains(String)` 的实际契约；
  `ReadFileTool.join` 更名 `truncateAndJoin` 并补全 `null`/0/不小于总行数、负数去尾、标记仍按 `size - limit` 计算三个分支的
  Javadoc；`AgentLoopTest` 的 `STRING_SCHEMA`/`INTEGER_SCHEMA` 更名为与生产代码一致的 `STRING_TYPE`/`INTEGER_TYPE`。
  保留三项判断题：四个 file tool 的错误边界模板重复、5 个 `*Input` 紧凑构造器同形、`GlobTool` 的 `workspace.root().getRoot()`，
  依据是 s01「不为本课引入浅 Module」的先例。修复后 `mvn clean verify` 仍通过 84 项测试。
  课程票转为 `ready-for-human`，等待用户 Debug；Debug 确认前不进入 s03。
- 2026-08-12：学习循环取消「用户 Debug」步骤——CLAUDE.md 已删除该步及「用户未确认 Debug 完成前，不进入下一课」的门禁，
  循环由 7 步变 6 步。上一条记录的「等待用户 Debug」随之失效，s02 由自动验证与双轴审查直接进入对照复盘与沉淀。
