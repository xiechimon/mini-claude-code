# 03 s03 Permission（权限管线）

Type: research

Status: ready-for-agent

> 结论：**契约层**（`sdk.d.ts`）把权限建模为「模式 + 判定 + 结果」三件套：6 个 `PermissionMode`、3 值
> `PermissionBehavior`、按 `behavior` 判别的 `PermissionResult`（`deny` 分支**必须**带 `message`，`allow` 分支可改写
> 工具输入并追加规则）。**参考解法**（课程 `code.py`）把这套压成一条三闸门管线：闸门 1 硬拒绝表（仅 `bash`，7 条子串）、
> 闸门 2 规则匹配（越界路径 / 破坏性命令）、闸门 3 用户审批（`[y/N]` 阻塞输入）；`check_permission` 返回裸 `bool`，
> 原因只打终端，模型收到的是字面串 `Permission denied.`。
>
> 本课**不只是加法**：`safe_path` 被整个删除，工作区边界由「file tool 硬拒绝」降级为「闸门 2 询问用户，批准后可写到
> 工作区外」；`run_bash` 同时失去 s01/s02 的内置 denylist、UTF-8 强制解码与 `OSError` 兜底。
>
> 本文记录契约事实与参考解法的实测行为；不含 Java 方案，也不把 s04 的 hooks 算进 s03。

## 研究范围与版本证据

### 官网不能作为基准（证据留档）

本课研究过程中发现**官网不能作为功能基准**，已按此结论修订 `CLAUDE.md`：

- 提交 `4d8d420`（2026-07-28T12:23:56Z）标题 `fix(s03): let Gate 2 own the workspace boundary instead of safe_path`
  修改了 `s03_permission/code.py`，但**没有重新生成**构建期产物 `web/src/data/generated/versions.json` 与 `docs.json`。
  实测该产物在 `4d8d420` 与 `7b564c3` 下的 s03 载荷 SHA-256 均为 `7a57fac4af96ec7bff5916fb6d6a713d38323a7f9f7aaeab2d11e2d55a0f08e8`，
  等于更早的 `1baf1ac` 版源码。
- 由此官网数据自相矛盾：线上 s02 载荷是 `7b564c3` 版（`c2f55dba…`），s03 载荷却是 `1baf1ac` 版。
  即官网「源码」与「学习」两个 tab 展示的 s03 都是**已被上游废弃的旧设计**（仍含 `safe_path`）。
- 上游 `main` 已于 2026-08-12 合入 PR #512 `feat/course-v22-refresh`（合并提交 `eb4307f`，其课程内容来自 `ab35e59`），
  重新生成了产物，但站点尚未重新部署。

本文所有课程链接指向研究当时的 `main` 提交 `eb4307f4e495d2ed22699e1e5682eb55f8076ade`（2026-08-12T01:47:36Z），
用于让下文的哈希与行号可复现。**它不是功能基准**——功能基准是 `.d.ts` 契约，见下文「契约基线」节与
[ADR-0005](../../../docs/adr/0005-基准从课程功能对等改为契约对齐.md)。课程源码后续演进不需要重新锁定，
每课直接读当时的 `main` 即可。

### 改版对已完成课次的影响

`ab35e59` 是课程级大改版，影响面已逐项核对：

| 项 | 旧（`7b564c3`） | 新（`eb4307f`） | 对本项目的影响 |
|---|---|---|---|
| 课程数 | 20 课 | **17 课** | 路线图缩短；用户已决定跟随删除 |
| 删除的课 | — | `s10_system_prompt`、`s11_error_recovery`、`s20_comprehensive` | 不再实现 |
| 重编号 | s12 Task System … s19 MCP | s10 Task System … s14 MCP Tools | 02 票第七节的后续课次清单已失效 |
| 新增 | — | `s17_goal_loop` | 收尾课改为 Goal Loop |
| s01 `code.py` | `f0bed0e0…` | `1c3e16ea…` | **仅注释与横幅文案**（中文改英文），零行为变化 |
| s02 `code.py` | `c2f55dba…` | `21f201d2…` | 同上，零行为变化 |

s01/s02 的行为断言全部继续成立，[01 票](01-s01-agent-loop.md) 与 [02 票](02-s02-tool-use.md)
的基线不需要重做。唯一需要同步的是两处**终端文案**（见 2.10）。

### 本课材料的哈希与口径

- 课程源码 `s03_permission/code.py`：**241 物理行**，SHA-256
  `dfe09572d4296c2ec993ea76f9f95e14bc81e2275fbafa9a6bdf689b5ecc13c8`。[固定版本源码](https://github.com/shareAI-lab/learn-claude-code/blob/eb4307f4e495d2ed22699e1e5682eb55f8076ade/s03_permission/code.py)
- 中文讲解 `s03_permission/README.zh.md`：**157 行**，SHA-256
  `5ab739727a22b1f04c582338685278ad970d71e4a30f929e3fddbcc3a39eee10`。[固定版本讲解](https://github.com/shareAI-lab/learn-claude-code/blob/eb4307f4e495d2ed22699e1e5682eb55f8076ade/s03_permission/README.zh.md)
  改版把 README 拆成 `README.md`（英文）/ `README.zh.md`（中文）/ `README.ja.md`，中文正文改用 `.zh.md`。
- 站点元数据口径：`versions.json` 中 s03 的 `loc = 180`、`tools` 为 5 个（与 s02 相同）、`newTools = []`、
  `coreAddition = "Permission gate"`、`classes = []`。**本课不新增工具**。
- 上游 17 课的 LOC 序列：s01=102、s02=143、s03=180、s04=202、s05=279、s06=285、s07=285、s08=404、s09=668、s10=420、
  s11=400、s12=638、s13=1523、s14=440、s15=2565、s16=722、s17=790。
- 本文所有行为结论均由该提交源码的函数体经 AST 原样抽取后在临时工作区实测得到（Python 3.14.7，macOS），未调用模型，不含推测。

## 契约基线（`sdk.d.ts` 的权限模型）

**这一节才是本课的功能基准。** 取法：`npm pack @anthropic-ai/claude-agent-sdk` 解包后读 `package/sdk.d.ts`。
以下为 `0.3.233` 版原文抽取，非推测。

### 类型与取值（名全录清单）

| 契约类型 | 取值 / 形状 |
|---|---|
| `PermissionMode` | `default` \| `acceptEdits` \| `bypassPermissions` \| `plan` \| `dontAsk` \| `auto` |
| `PermissionBehavior` | `allow` \| `deny` \| `ask` |
| `PermissionResult` | `{behavior:'allow', updatedInput?, updatedPermissions?, toolUseID?, decisionClassification?}` <br> `\| {behavior:'deny', message, interrupt?, toolUseID?, decisionClassification?}` |
| `PermissionRuleValue` | `{toolName, ruleContent?}` |
| `PermissionUpdate` | `addRules` \| `replaceRules` \| `removeRules` \| … ，各带 `{rules, behavior, destination}` |
| `PermissionUpdateDestination` | `userSettings` \| `projectSettings` \| `localSettings` \| `session` \| `cliArg` |
| `PermissionDecisionClassification` | `user_temporary` \| `user_permanent` \| `user_reject` |
| `CanUseTool` | `(toolName, input, {signal, suggestions?, …}) => PermissionResult` —— 宿主可注入的判定回调 |

`HookPermissionDecision`（`allow` / `deny` / `ask` / `defer`）与 `PermissionRequest`、`PermissionDenied` 两个 hook 事件
同属权限领域，但它们是 **hook 契约**，归 s04 的契约基线，本课不认领。

### 契约与参考解法的差距

`code.py` 是对上表的大幅裁剪。逐项列出，供设计 Grill 逐条裁决「认领名 / 认领行为 / 不做」：

| # | 契约 | `code.py` 的解法 | 性质 |
|---|---|---|---|
| 1 | `PermissionResult.deny` **必须**带 `message` | `check_permission` 返回裸 `bool`；原因只 print 到终端，模型收到固定串 `Permission denied.` | **结构性**：拒绝原因对模型完全丢失 |
| 2 | `PermissionBehavior` 三值，`ask` 是独立状态 | 二值 allow/deny；`ask` 隐含在「闸门 2 命中就调 `ask_user`」的控制流里 | **结构性**：状态被折叠进控制流 |
| 3 | `CanUseTool` 是可注入回调，宿主可替换 | 管线写死为模块级函数，`input()` 直读 stdin，无接缝 | **结构性**：不可测试、不可替换 |
| 4 | 6 个 `PermissionMode` | 无模式概念，行为恒等于 `default` | 整个维度未建模 |
| 5 | `allow` 可带 `updatedInput` 改写工具参数 | 无 | 能力缺失 |
| 6 | `allow` 可带 `updatedPermissions` 写入 5 种 `destination` | 无，同一操作每次都重新问 | 能力缺失（第七节「记住审批决定」即此项） |
| 7 | `decisionClassification` 区分临时/永久/拒绝 | 无 | 遥测维度，学习价值低 |

第 1–3 项是结构性差距，直接决定 Java 设计的形状，Grill 时逐条过；第 4–7 项按「名全录、行为按需」处理 ——
枚举和字段名录进类型，行为实现按端到端场景裁，未实现的显式标记。

## 一、课程要解决的问题

讲解的问题陈述：s02 的 file tools 受 `safe_path` 保护，但 `bash` 完全不受限制，让它「清理一下项目」可能执行 `rm -rf /`；
**安全边界由代码负责，判断发生在工具执行之前**。
[讲解 L12-16](https://github.com/shareAI-lab/learn-claude-code/blob/eb4307f4e495d2ed22699e1e5682eb55f8076ade/s03_permission/README.zh.md#L12-L16)

课程给出的解法是三道闸门，顺序固定：硬拒绝优先，软询问次之，都没命中就放行。

| 闸门 | 作用 | 命中后 |
|------|------|--------|
| 1. 拒绝列表 | 永远禁止的操作（`rm -rf /`、`sudo`） | 直接拒绝，不执行 |
| 2. 规则匹配 | 取决于上下文的操作（读/写工作区外、`rm` 文件） | 交给闸门 3 |
| 3. 用户审批 | 闸门 2 命中后，暂停等用户确认 | 用户决定允许或拒绝 |

上表为讲解原表。
[讲解 L28-32](https://github.com/shareAI-lab/learn-claude-code/blob/eb4307f4e495d2ed22699e1e5682eb55f8076ade/s03_permission/README.zh.md#L28-L32)

## 二、完整功能清单（相对 s02 的增量）

### 2.1 配置与启动的变化

s02 的配置流程（`load_dotenv(override=True)`、Base URL 非空时删除 `ANTHROPIC_AUTH_TOKEN`、`MODEL_ID` 无默认值、
`WORKDIR = Path.cwd()` 启动时捕获一次、可选 `readline` 四条绑定）全部保留。唯一变化是 **system prompt 被整体替换**：

| | 文本 |
|---|---|
| s02 | `You are a coding agent at {WORKDIR}. Use tools to solve tasks. Act, don't explain.` |
| s03 | `You are a coding agent at {WORKDIR}. All destructive operations require user approval.` |

即 `Use tools to solve tasks. Act, don't explain.` 两句被**删除**，换成一句权限声明。
（值得注意：s04 又把它换回 `Use tools to solve tasks. Act, don't explain.`，说明这是本课特有的临时措辞。）

### 2.2 工具声明：完全未变

`TOOLS` 仍是 5 元素 list，5 条 `name` / `description` / `input_schema` 与 s02 **逐字相同**；`TOOL_HANDLERS` 映射也逐字相同。
`versions.json` 的 `newTools` 为空数组印证了这一点。02 票 2.2 节的工具表继续有效。

### 2.3 `safe_path` 被删除 —— 工作区边界降级

这是本课**最容易被当成「s02 已有功能」而误保留**的一处。

```python
# s02 有，s03 无：
def safe_path(p: str) -> Path:
    path = (WORKDIR / p).resolve()
    if not path.is_relative_to(WORKDIR):
        raise ValueError(f"Path escapes workspace: {p}")
    return path
```

三个 file tool 的调用点相应改写：

| 工具 | s02 | s03 |
|---|---|---|
| `run_read` | `safe_path(path).read_text()` | `(WORKDIR / path).resolve().read_text()` |
| `run_write` | `file_path = safe_path(path)` | `file_path = (WORKDIR / path).resolve()` |
| `run_edit` | `file_path = safe_path(path)` | `file_path = (WORKDIR / path).resolve()` |

`run_glob` 的逐条包含性过滤（`(WORKDIR / match).resolve().is_relative_to(WORKDIR)`）**保留未变**。

后果（实测）：

- 越界路径不再产生 `Error: Path escapes workspace: {原始输入}`。该文案在 s03 中**不存在于任何代码路径**。
- 越界访问改由闸门 2 拦成「问用户」。**用户答 `y` 后，file tool 真的会读写工作区外的文件**：
  实测 `run_write("../escaped-s03.txt", "leaked")` 返回 `Wrote 6 bytes to ../escaped-s03.txt`，文件确实创建在工作区之外。
- 这是上游的**有意设计**，提交标题即 `let Gate 2 own the workspace boundary instead of safe_path`。

### 2.4 闸门 1：`check_deny_list`

```python
DENY_LIST = ["rm -rf /", "sudo", "shutdown", "reboot", "mkfs", "dd if=", "> /dev/sda"]

def check_deny_list(command: str) -> str | None:
    for pattern in DENY_LIST:
        if pattern in command:
            return f"Blocked: '{pattern}' is on the deny list"
    return None
```

1. **7 条**模式，纯子串、**大小写敏感**、按列表顺序**首个命中即返回**。
2. 命中返回 `Blocked: '{pattern}' is on the deny list`；未命中返回 `None`。
3. 只在 `block.name == "bash"` 时被调用，其余 4 个工具**完全不过闸门 1**。

实测：

| 输入 | 结果 |
|---|---|
| `rm -rf /` | `Blocked: 'rm -rf /' is on the deny list` |
| `sudo ls` | `Blocked: 'sudo' is on the deny list` |
| `mkfs.ext4 /dev/x` | `Blocked: 'mkfs' is on the deny list` |
| `dd if=/dev/zero` | `Blocked: 'dd if=' is on the deny list` |
| `cat x > /dev/sda` | `Blocked: '> /dev/sda' is on the deny list` |
| `sudo rm -rf /` | `Blocked: 'rm -rf /' is on the deny list`（列表顺序在前者胜出，不是输入顺序） |
| `rm -rf /tmp/build` | `Blocked: 'rm -rf /' is on the deny list`（**误伤**：清理临时目录被当成清空根目录） |
| `SUDO ls` | `None`（大小写敏感） |
| `pseudo-terminal` | `None`（`sudo` 不是 `pseudo` 的子串） |
| `echo hi > /dev/null` | `None` |
| `ls -la`、`` | `None` |

与 s01/s02 的 `run_bash` 内置 denylist 的差异：

| | s01/s02 | s03 |
|---|---|---|
| 位置 | `run_bash` 函数体内 | 闸门 1，工具执行之前 |
| 条目 | 5 条：`rm -rf /`、`sudo`、`shutdown`、`reboot`、`> /dev/` | 7 条：`> /dev/` 收窄为 `> /dev/sda`，新增 `mkfs`、`dd if=` |
| 拦截文案 | 工具返回 `Error: Dangerous command blocked` | 终端打印 `[blocked] Blocked: '…' is on the deny list`，**模型收到的是 `Permission denied.`** |
| 覆盖面 | 只有 `bash` | 仍只有 `bash` |

因 `> /dev/` 收窄为 `> /dev/sda`，`echo x > /dev/null`、`> /dev/tty` 等在 s03 中**从被拦改为放行**（实测确认）。

### 2.5 闸门 2：`check_rules`

```python
PERMISSION_RULES = [
    {"tools": ["read_file", "write_file", "edit_file"],
     "check": lambda args: not (WORKDIR / args.get("path", "")).resolve().is_relative_to(WORKDIR),
     "message": "Writing outside workspace"},
    {"tools": ["bash"],
     "check": lambda args: any(kw in args.get("command", "") for kw in ["rm ", "> /etc/", "chmod 777"]),
     "message": "Potentially destructive command"},
]

def check_rules(tool_name: str, args: dict) -> str | None:
    for rule in PERMISSION_RULES:
        if tool_name in rule["tools"] and rule["check"](args):
            return rule["message"]
    return None
```

1. 规则按顺序遍历，**工具名匹配且检查为真**才命中，首个命中即返回其 `message`。
2. 规则 1 覆盖 `read_file` / `write_file` / `edit_file`（**含只读的 `read_file`**），判据与 s02 `safe_path` 相同的
   `resolve()` + `is_relative_to`，但 `message` 是 `Writing outside workspace`——**读操作也用「Writing」措辞**。
3. 规则 2 覆盖 `bash`，三个关键词 `rm `（含尾随空格）、`> /etc/`、`chmod 777`，纯子串、大小写敏感。
4. `glob` **不被任何规则覆盖**，永远直接放行（包括 `../*` 这类模式）。
5. 参数缺失走 `args.get(key, "")` 默认空串：`path` 缺失时 `(WORKDIR / "")` 就是 `WORKDIR`，`is_relative_to` 自反为真，
   取反后为假 → **不命中**。

实测：

| 工具 / 参数 | 结果 |
|---|---|
| `read_file {path: "a.txt"}` | `None` |
| `read_file {path: "../outside.txt"}` | `Writing outside workspace` |
| `read_file {path: "/etc/passwd"}` | `Writing outside workspace` |
| `read_file {}` | `None`（缺 `path` 退化为工作区自身） |
| `write_file {path: "../x", …}` | `Writing outside workspace` |
| `edit_file {path: "/etc/hosts", …}` | `Writing outside workspace` |
| `glob {pattern: "../*"}` | `None`（不受任何规则约束） |
| `bash {command: "rm foo"}` | `Potentially destructive command` |
| `bash {command: "rm"}` | `None`（缺尾随空格不匹配） |
| `bash {command: "confirm the change"}` | `Potentially destructive command`（**误伤**：`confirm ` 含子串 `rm `） |
| `bash {command: "chmod 777 x"}` | `Potentially destructive command` |
| `bash {command: "echo x > /etc/hosts"}` | `Potentially destructive command` |
| `bash {command: "ls"}`、`bash {}` | `None` |
| `unknown_tool {…}` | `None`（未注册工具名不被任何规则覆盖） |

注意闸门 2 的越界判据用的是 `resolve()`，因此**符号链接逃逸也会被判为越界**（与 s02 `safe_path` 同源），
但它只触发询问，不构成拒绝。

### 2.6 闸门 3：`ask_user`

```python
def ask_user(tool_name: str, args: dict, reason: str) -> str:
    print(f"\n\033[33m[permission] {reason}\033[0m")
    print(f"   Tool: {tool_name}({args})")
    choice = input("   Allow? [y/N] ").strip().lower()
    return "allow" if choice in ("y", "yes") else "deny"
```

1. 打印顺序固定为三段：空行 + 黄色 `[permission] {reason}`、`   Tool: {tool_name}({args})`、输入提示 `   Allow? [y/N] `。
   `{args}` 是 Python dict 的 `repr`，即 `{'command': 'rm x'}` 这种带单引号的形式，**参数值完整回显、不截断**。
2. 判定：`strip().lower()` 后**只有 `y` 与 `yes` 允许**，其余一律拒绝。实测 `Y`/`YES`/`  y  ` 允许；
   `n`、空串、`allow`、`yep` 均拒绝。
3. **阻塞式**：调用 `input()` 等待终端输入，此时 Agent Loop 完全暂停。
4. `input()` 的 `EOFError` / `KeyboardInterrupt` **未被捕获**。实测 stdin 到达 EOF 时抛
   `EOFError: EOF when reading a line`，沿 `check_permission` → `agent_loop` 一路传播，最终由 REPL 的
   `except (EOFError, KeyboardInterrupt)` 捕获——但那是 REPL 层的退出分支，效果是**整个会话静默退出**，不是拒绝该次调用。

### 2.7 管线：`check_permission`

```python
def check_permission(block) -> bool:
    if block.name == "bash":
        reason = check_deny_list(block.input.get("command", ""))
        if reason:
            print(f"\n\033[31m[blocked] {reason}\033[0m")
            return False
    reason = check_rules(block.name, block.input)
    if reason:
        decision = ask_user(block.name, block.input, reason)
        if decision == "deny":
            return False
    return True
```

1. 返回 `bool`，不抛异常，不返回原因；原因只打到终端。
2. 闸门 1 命中：打印空行 + 红色 `[blocked] Blocked: '…' is on the deny list`，直接 `False`，**不进入闸门 2**。
3. 闸门 2 未命中：直接 `True`，**不进入闸门 3**（不询问）。
4. 闸门 2 命中 + 用户允许：`True`，继续正常执行。
5. 未注册的工具名同样会走完整条管线（不命中任何规则）→ `True` → 由分发层回填 `Unknown: {name}`。

实测管线整体：

| 调用 | stdin | 返回 | 终端输出 |
|---|---|---|---|
| `bash {command: "sudo ls"}` | `n` | `False` | `[blocked] Blocked: 'sudo' is on the deny list`（**未询问**） |
| `bash {command: "rm x"}` | `y` | `True` | `[permission] Potentially destructive command` + Tool 行 + 提示 |
| `bash {command: "rm x"}` | `n` | `False` | 同上 |
| `bash {command: "ls"}` | — | `True` | 无输出 |
| `read_file {path: "../x"}` | `y` | `True` | `[permission] Writing outside workspace` + Tool 行 + 提示 |
| `glob {pattern: "../*"}` | — | `True` | 无输出 |
| `unknown_tool {a: 1}` | — | `True` | 无输出 |

### 2.8 `run_bash` 的三处退化

s03 的 `run_bash` 相对 s02 **减少**了三样东西：

```python
def run_bash(command: str) -> str:
    try:
        r = subprocess.run(command, shell=True, cwd=WORKDIR,
                           capture_output=True, text=True, timeout=120)
        out = (r.stdout + r.stderr).strip()
        return out[:50000] if out else "(no output)"
    except subprocess.TimeoutExpired:
        return "Error: Timeout (120s)"
```

| # | s02 有 | s03 无 | 后果 |
|---|---|---|---|
| 1 | 函数体内 5 条 denylist | 删除 | 拦截职责整体移交闸门 1（条目与文案都变了，见 2.4） |
| 2 | `encoding="utf-8", errors="replace"` | 删除 | 回到 locale 默认解码且 `errors='strict'`；实测 `printf '\xff\xfe'` 抛 `UnicodeDecodeError` 并**逃出 `run_bash`**（只捕获 `TimeoutExpired`），最终终止进程 |
| 3 | `except (FileNotFoundError, OSError) as e: return f"Error: {e}"` | 删除 | 子进程创建期的 OS 异常不再转成 `Error:` 串，同样逃出 |

未变的部分：`shell=True`、`cwd=WORKDIR`、`capture_output=True`、`timeout=120`、`(stdout + stderr).strip()`、
`[:50000]`、`(no output)`、`Error: Timeout (120s)`、非零退出码不算工具错误、每次调用新建独立 shell 子进程。

### 2.9 Agent Loop 的变化

结构与 s02 相同：非流式 `client.messages.create(model=MODEL, system=SYSTEM, messages=messages, tools=TOOLS, max_tokens=8000)`
→ 追加 assistant 消息 → `if response.stop_reason != "tool_use": return` → 遍历 content 执行工具 → 汇总成一条 `user` 消息 →
回到循环顶。没有轮次上限、没有总时长上限、没有 API 异常兜底。

工具执行段的逐字变化：

| # | s02 | s03 |
|---|---|---|
| 1 | `if block.type == "tool_use":` 包住整段 | `if block.type != "tool_use": continue` 卫语句（行为等价） |
| 2 | `print(f"\033[33m> {block.name}\033[0m")` | `print(f"\033[36m> {block.name}\033[0m")` —— 黄色改**青色** |
| 3 | （无） | `if not check_permission(block): results.append({..., "content": "Permission denied."}); continue` |

由此得到 s03 特有的运行时事实：

1. **工具名先打印，权限再判断**。即使随后被拒，终端也已经出现过 `> {tool_name}` 一行。
2. 被拒绝的调用**仍然回填一条 `tool_result`**，内容是字面串 `Permission denied.`（含句点）。
   模型看不到具体拒绝原因——原因只打给用户。
3. 被拒绝时**不打印 200 字符输出预览**（那行在 `continue` 之后）。
4. `tool_result` 结构不变：`{type, tool_use_id: block.id, content: output}`，**不设 `is_error`**，
   `Permission denied.` 也不设。
5. 同一响应中的多个 `tool_use` 仍按 `response.content` 原始顺序**同步串行**处理；每一个都独立走完整条权限管线，
   因此一轮内可能连续弹出多次审批。
6. 一轮内混合「放行 + 拒绝」时，全部结果仍放进紧随其后的同一条 `user` 消息，顺序与 `tool_use` 一致。

典型历史形状：

```text
user      content = 用户问题字符串
assistant content = [text?/tool_use(id, name∈5 个工具, input=对应 schema), ...]
user      content = [tool_result(tool_use_id=id, content=完整工具返回值 或 "Permission denied."), ...]
assistant content = [text ...]  # stop_reason 非 tool_use，agent_loop 返回
```

### 2.10 REPL 的变化

s02 的全部 REPL 行为保留：只在主程序运行时进入、`strip().lower()` 后命中 `""`/`q`/`exit` 即退出且不入历史、
EOF 与 Ctrl-C 静默退出、普通输入按原串（不 strip）入历史、`history` 只创建一次因此多轮共享上下文、
每轮结束后打印所有 `text` 块并追加空行。

两处文案变化，且**这两处是课程大改版的全局改动，s01/s02 同样受影响**：

| | s02（旧） | s03（`eb4307f`） |
|---|---|---|
| 横幅第 1 行 | `s02: Tool Use — 在 s01 基础上加了 4 个工具` | `s03: Permission` |
| 横幅第 2 行 | `输入问题，回车发送。输入 q 退出。` + 空行 | `Enter a question, press Enter to send. Type q to quit.` + 空行 |
| 提示符 | 青色 `s02 >> ` | 青色 `s03 >> ` |

改版后 s01 的第 2 行同样是英文 `Enter a question, press Enter to send. Type q to quit.`，s02 第 1 行为
`s02: Tool Use - four tools added to s01`。项目里 s01/s02 的对应断言需要同步。

### 2.11 讲解与源码的差异

讲解正文的 Python 片段仍是**简化示意**，与 `code.py` 不一致。验收以 `code.py` 为准。

| 讲解片段 | 实际 `code.py` |
|---|---|
| 规则 1 的 `"message": "Access outside workspace"` | `"Writing outside workspace"` |
| `ask_user` 打印 `⚠  {reason}` | `\033[33m[permission] {reason}\033[0m` |
| `check_permission` 打印 `⛔ {reason}` | `\033[31m[blocked] {reason}\033[0m` |
| 「相对 s02 的变更」表只列「新增」三行 | 未提及 `safe_path` 被删除、`run_bash` denylist 与 UTF-8 解码被删除 |
| 「问题」节称 s02「file tools 受 `safe_path` 保护」 | 描述的是 s02 现状，正确；但 s03 自身已无 `safe_path` |

`README.zh.md` 中**不再有** `<details>深入 CC 源码</details>` 折叠块——改版把 s03 的 CC 源码深入附录整段删除了
（旧版 `7b564c3` 的 README 有该附录，含 PermissionResult 四态、8 个规则来源、YoloClassifier、权限冒泡等六节）。
本课因此没有官方「深入探索」正文可记录。

### 2.12 教学演示场景

运行方式：`cd learn-claude-code` 后 `python s03_permission/code.py`。讲解给出四个观察用 prompt：

1. `Create a file called test.txt in the current directory`（应该直接通过）
2. `Delete the file test.txt`（bash + rm 会触发闸门 2）
3. `What files are in the current directory?`（只读，全部通过）
4. `Try to write a file to /etc/something`（写工作区外，触发闸门 2）

观察重点：哪些操作直接通过？哪些需要确认？哪些被直接拒绝？
[讲解 L132-146](https://github.com/shareAI-lab/learn-claude-code/blob/eb4307f4e495d2ed22699e1e5682eb55f8076ade/s03_permission/README.zh.md#L132-L146)

## 三、输入输出行为总表

只列 s03 新增或改变的边界；s01/s02 的其余边界见 01、02 票。

| 边界 | 输入 | 可观察输出或状态变化 |
|---|---|---|
| 模型请求 | 每轮请求 | `tools` 恒为 s02 的 5 元素 `TOOLS`；`system` 为 `You are a coding agent at {WORKDIR}. All destructive operations require user approval.` |
| 终端 | 任意 `tool_use` | **青色** `> {tool_name}`（先于权限判断打印） |
| 闸门 1 | `bash` 命令含 7 条模式之一 | 打印红色 `[blocked] Blocked: '{pattern}' is on the deny list`；不执行；不询问 |
| 闸门 1 | 非 `bash` 工具 | 完全跳过闸门 1 |
| 闸门 2 | `read_file`/`write_file`/`edit_file` 的 `path` 解析后不在工作区内 | 进入闸门 3，理由 `Writing outside workspace` |
| 闸门 2 | `bash` 命令含 `rm ` / `> /etc/` / `chmod 777` | 进入闸门 3，理由 `Potentially destructive command` |
| 闸门 2 | `glob` 任意模式、未注册工具名、参数缺失 | 不命中，直接放行 |
| 闸门 3 | 终端输入 `y` / `yes`（大小写与首尾空白不敏感） | 允许，工具正常执行 |
| 闸门 3 | 其他任意输入（含空串） | 拒绝 |
| 闸门 3 | stdin EOF / Ctrl-C | `EOFError`/`KeyboardInterrupt` 未在管线内捕获，冒泡至 REPL 导致会话退出 |
| 闸门 3 | 命中时 | 打印空行 + 黄色 `[permission] {reason}`、`   Tool: {name}({args 的 dict repr})`、`   Allow? [y/N] ` |
| 工具执行 | 权限通过 | 与 s02 一致：查表分发、`**input` 展开、200 字符预览、完整结果回填 |
| 工具执行 | 权限被拒 | 不执行、不打印预览；回填 `tool_result.content = "Permission denied."`，无 `is_error` |
| `read_file` | 越界路径且用户批准 | **真的读工作区外文件**（`safe_path` 已删除） |
| `write_file` | 越界路径且用户批准 | **真的写到工作区外**，返回 `Wrote {字符数} bytes to {原始输入路径}` |
| `edit_file` | 越界路径且用户批准 | 真的改工作区外文件 |
| `glob` | 任意模式 | 与 s02 完全一致：逐条包含性过滤仍在，越界结果被丢弃 |
| `bash` | 含 `> /dev/null` 等 | s02 被 denylist 拦，**s03 放行** |
| `bash` | 输出含非 UTF-8 字节 | `UnicodeDecodeError` 逃出 `run_bash`，终止进程（s02 用 `errors="replace"` 兜住） |
| `bash` | 子进程创建失败（OS 异常） | 异常逃出（s02 转成 `Error: {e}`） |
| REPL | 启动 | 横幅 `s03: Permission` + `Enter a question, press Enter to send. Type q to quit.` + 空行，提示符青色 `s03 >> ` |

## 四、可验证验收标准

**这一节记录的是参考解法的行为断言**，从固定源码逐项推出。基准换成契约后
（[ADR-0005](../../../docs/adr/0005-基准从课程功能对等改为契约对齐.md)），它们不再是「必须逐字对等」的清单，
而是 Grill 时的对照材料：每一条都要问「契约要求这样吗？不要求的话我为什么跟」。

实际验收按「验证原则」三分工：**契约核对**（上文名全录清单是否已全部录入类型、未实现项是否显式标记）→
**端到端场景**（J 节四个场景，JUnit 加写死的假模型响应）→ **单元测试**（只保护真会写错的边界逻辑）。

模型响应用 stub/fake 控制；闸门 3 的终端输入必须可注入，否则交互会变成不可自动化的路径。
s01/s02 已验收项默认继续成立，此处只列 s03 新增或改变的断言。

### A. 配置

- [ ] system prompt 精确为 `You are a coding agent at {启动时工作目录}. All destructive operations require user approval.`，
      不再包含 `Use tools to solve tasks. Act, don't explain.`。
- [ ] 其余配置语义（`.env` 覆盖、Base URL 时删 `ANTHROPIC_AUTH_TOKEN`、`MODEL_ID` 无默认值、工作目录启动时捕获一次）未变。

### B. 工具声明

- [ ] 工具列表仍恰为 5 个，`name` / `description` / `input_schema` 与 s02 逐字相同，顺序不变。
- [ ] 本课**不新增任何工具**。
- [ ] 每次模型请求仍携带相同 `TOOLS`、`SYSTEM`、配置的 `MODEL` 与 `max_tokens=8000`。

### C. 闸门 1（硬拒绝）

- [ ] 拒绝表恰为 7 条：`rm -rf /`、`sudo`、`shutdown`、`reboot`、`mkfs`、`dd if=`、`> /dev/sda`，顺序一致。
- [ ] 匹配为纯子串、大小写敏感；按表内顺序取首个命中，理由文案为 `Blocked: '{pattern}' is on the deny list`。
- [ ] 只对 `bash` 生效；其余 4 个工具不经过闸门 1。
- [ ] 命中时不执行工具、不进入闸门 2/3，终端打印红色 `[blocked] {理由}`。
- [ ] `echo hi > /dev/null` 不再被拦（`> /dev/` 已收窄为 `> /dev/sda`）。
- [ ] `run_bash` 内部**不再有**任何 denylist，`Error: Dangerous command blocked` 文案不存在于任何代码路径。

### D. 闸门 2（规则匹配）

- [ ] 规则恰为 2 条，按序匹配，首个命中即返回其 message。
- [ ] 规则 1 覆盖 `read_file`、`write_file`、`edit_file` 三者（**含只读工具**），判据是路径解析后是否落在工作区内，
      message 为 `Writing outside workspace`。
- [ ] 规则 2 覆盖 `bash`，关键词恰为 `rm `（含尾随空格）、`> /etc/`、`chmod 777`，纯子串、大小写敏感，
      message 为 `Potentially destructive command`。
- [ ] `glob` 与未注册工具名不被任何规则覆盖，直接放行。
- [ ] 必填参数缺失时按空串处理，`path` 缺失退化为工作区自身因而不命中。
- [ ] 符号链接指向工作区外时规则 1 命中（判据在解析符号链接之后）。

### E. 闸门 3（用户审批）

- [ ] 仅在闸门 2 命中时触发；未命中时**不产生任何提示、不阻塞**。
- [ ] 提示为三段：空行 + 黄色 `[permission] {reason}`、`   Tool: {tool_name}({args})`、输入提示 `   Allow? [y/N] `。
- [ ] 参数在提示中完整回显，不截断。
- [ ] 输入经首尾去空白与转小写后，仅 `y` 与 `yes` 视为允许，其余（含空输入）一律拒绝。
- [ ] 等待期间 Agent Loop 阻塞，用户答复前不发生任何工具执行。
- [ ] 输入流结束（EOF）或中断时，异常不被权限管线吞掉。

### F. 管线与循环

- [ ] 判断顺序固定为闸门 1 → 闸门 2 → 闸门 3；闸门 1 命中即短路，闸门 2 未命中即放行。
- [ ] 权限判断发生在**工具执行之前**、且在工具名打印**之后**。
- [ ] 权限判断对所有 `tool_use` 块生效，包括未注册的工具名。
- [ ] 被拒绝的调用仍回填一条 `tool_result`，内容恰为 `Permission denied.`，`tool_use_id` 与原调用一致。
- [ ] 被拒绝时不执行 handler、不产生任何副作用、不打印输出预览。
- [ ] `Permission denied.` 同样**不设 `is_error`**。
- [ ] 一轮内多个 `tool_use` 各自独立走完整条管线，按原始顺序串行处理；放行与拒绝的结果混在同一条 `user` 消息中，
      顺序与 `tool_use` 一致。
- [ ] `stop_reason != "tool_use"` 时立即结束的语义未变。

### G. 工作区边界的降级

- [ ] `safe_path` 及其 `Path escapes workspace: {原始输入}` 文案**从实现中移除**。
- [ ] 越界路径不再由 file tool 直接拒绝，而是触发闸门 2 询问。
- [ ] 用户批准后，`read_file` / `write_file` / `edit_file` **确实能访问工作区外的路径**。
- [ ] `glob` 的逐条包含性过滤**保留不变**，越界匹配项仍被丢弃且不经询问。

### H. `bash` 的行为退化

- [ ] `run_bash` 不再固定 UTF-8 解码、不再替换非法字节。
- [ ] `run_bash` 只捕获超时；子进程输出解码失败与子进程创建期的 OS 异常均不再被转成 `Error:` 串。
- [ ] 120 秒超时、50,000 字符上限、`(no output)`、`stdout+stderr` 拼接后整体 strip 等继续成立。

### I. 终端与 REPL

- [ ] 工具名以**青色**打印（s02 为黄色）。
- [ ] 启动横幅为 `s03: Permission` + `Enter a question, press Enter to send. Type q to quit.` + 空行。
- [ ] 提示符为青色 `s03 >> `。
- [ ] 退出词、EOF/Ctrl-C、原串入历史、跨轮共享 history、末尾打印 text 块 + 空行等未变。

### J. 端到端关键路径（讲解四个示例）

- [ ] `Create a file called test.txt in the current directory`：三道闸门都不命中，直接执行。
- [ ] `Delete the file test.txt`：`bash` + `rm ` 触发闸门 2，出现审批提示；批准后执行，拒绝后回填 `Permission denied.`。
- [ ] `What files are in the current directory?`：只读操作全部直接通过，无提示。
- [ ] `Try to write a file to /etc/something`：`write_file` 越界触发闸门 2；批准后**真的写到工作区外**。
- [ ] 四个场景都能明确观察到：收到 `tool_use` 时循环继续，最终非 `tool_use` 时循环停止。

## 五、Python 特有实现点

s03 新引入的 Python/库级表达，用于理解现有基准，不是 Java 设计建议（s01/s02 的条目见 01、02 票）。

| Python 特有点 | 当前源码中的具体含义 |
|---|---|
| dict 里放 `lambda` | `PERMISSION_RULES` 每条规则的 `check` 是闭包，捕获模块级 `WORKDIR` |
| 规则表是 `list[dict]` | 无类型约束；`tools` / `check` / `message` 三个键靠约定维系 |
| `tool_name in rule["tools"]` | 列表成员判断，工具名是裸字符串 |
| `args.get(key, "")` | 缺参默认空串，把「参数缺失」静默折叠成「空值」而非报错 |
| `any(kw in cmd for kw in [...])` | 生成器 + 短路，等价于任一关键词子串命中 |
| `str \| None` 返回类型 | 用 `None` 表示「未命中」，命中则返回理由串 |
| `input()` 直接读 stdin | 权限审批与终端强耦合，无可注入的抽象层；`EOFError` 未捕获 |
| f-string 内嵌 dict | `f"Tool: {tool_name}({args})"` 依赖 dict 的 `repr` 产生单引号形式 |
| `print` 内嵌 ANSI 转义 | 颜色写死在字符串里：`\033[31m` 红（拒绝）、`\033[33m` 黄（询问）、`\033[36m` 青（工具名/提示符） |
| 模块级可变常量 | `DENY_LIST` / `PERMISSION_RULES` 是模块级 list，运行期可被改写（课程未利用这点） |
| 只捕获 `subprocess.TimeoutExpired` | `run_bash` 的其余异常全部逃逸，包括 `UnicodeDecodeError` |
| 删除函数而非留兼容层 | `safe_path` 直接删除，调用点全部改写，没有过渡期 |

## 六、站点数据（锁定提交下）

改版后站点数据与源码**不再有 s02 那种 4/5 工具级别的冲突**，三项均可作为理解辅助（但仍不作为验收依据）：

- **执行流程**（`execution-flows.ts` 的 `s03`）：`Tool Call → Hard Deny? →(deny) Blocked` / `→(ok) Rule Match? →(allow)
  Execute Tool`，`Rule Match? →(needs approval) Ask User → Approved? →(no) Blocked` / `→(yes) Execute Tool → Append Result`。
  与源码控制流一致。
  [execution-flows.ts:265-288](https://github.com/shareAI-lab/learn-claude-code/blob/eb4307f4e495d2ed22699e1e5682eb55f8076ade/web/src/data/execution-flows.ts#L265-L288)
- **设计决策**（`annotations/s03.json`）三条，与源码一致：
  1. `Permission Runs Before Tool Execution` —— 权限检查插在模型 tool call 和 handler 之间；
     替代方案「把权限放进每个工具内部」会重复策略且容易被新工具遗漏。
  2. `Three Gates Keep Policy Explainable` —— 硬拒绝 / 规则匹配 / 用户确认分开，能区分「绝对禁止、有风险、只是在等确认」；
     替代方案「单个 allow/deny 函数」更短但隐藏了停下来的原因。
  3. `Blocked Calls Still Produce Loop State` —— 被拦截的调用仍要让循环状态保持一致；
     替代方案「静默跳过」更简单，但模型可能重复发起同样的不安全请求。
- **模拟**（`scenarios/s03.json`）：6 步演示 `rm -rf /tmp/build-cache` 走闸门 1 通过、闸门 2 命中、用户批准、正常回填。
  其中的 `system_event` 文案是站点特有的示意，源码没有这种事件流。

## 七、明确不属于本课的能力

判断依据：锁定提交下的课程目录清单（s01–s17）、各课 README 的「接下来」指向、以及 `s04_hooks/code.py` 实测。

**紧邻的下一课（s04 Hooks）**——讲解「接下来」明确写「权限检查每次都在循环里硬编码 `check_permission()`……
s04 Hooks → 给循环加钩子」。实测 `s04_hooks/code.py`：`TOOLS` 仍是同样的 5 个；新增
`HOOKS = {"UserPromptSubmit": [], "PreToolUse": [], "PostToolUse": [], "Stop": []}` 与 `register_hook` / `trigger_hooks`，
**s03 的三道闸门被改写成挂在 `PreToolUse` 上的回调**；system prompt 换回
`Use tools to solve tasks. Act, don't explain.`。因此以下能力必须留给 s04，不得在 s03 出现：

- 任何 hook 注册表、事件名、回调链
- 把权限逻辑做成可插拔扩展点
- `UserPromptSubmit` / `PostToolUse` / `Stop` 三类时机

**更后续课次**（上游 17 课目录依次为）：s05 TodoWrite、s06 Subagent、s07 Skill Loading、s08 Context Compact、s09 Memory、
s10 Task System、s11 Background Tasks、s12 Cron Scheduler、s13 Agent Team Runtime、s14 MCP Tools、s15 Integrated Harness、
s16 Workflow Runtime、s17 Goal Loop。对应能力均不属于 s03。

**权限领域中属于生产级 CC 而非本课的**（旧版 README 附录曾记录，改版已删除，此处仅作边界说明）：
`PermissionResult` 的 `passthrough` 第四态、8 个规则来源与优先级合并、`~/.claude/settings.json` 等外部配置、
会话内临时授权、`--allowedTools` 等 CLI 参数、YoloClassifier 自动审批、权限冒泡到父 Agent、
`tool.checkPermissions()` 式的工具自判、Zod 验证与 `validateInput()`。

**契约里有、但参考解法裁掉的**——基准换成契约后，这些**不能再算「不属于本课」而静默排除**，必须在 Grill 时逐条裁决
并显式标记（对应契约差距表 1、5、6、7 项）：

- 拒绝原因回传给模型（契约的 `PermissionResult.deny.message` 是必填，`code.py` 丢掉了）
- 记住用户的审批决定（契约的 `updatedPermissions` + 5 种 `PermissionUpdateDestination`）
- `allow` 时改写工具输入（契约的 `updatedInput`）
- 决策分类遥测（契约的 `decisionClassification`）

**确实不属于本课的**：

- 权限规则的外部配置化、热加载（`~/.claude/settings.json` 等外部来源）
- 对 `glob` 或未注册工具名的权限约束
- 审批的超时、默认值、非交互模式
- 恢复 `safe_path` 式的硬边界（上游已明确废弃）

## 八、来源与可信度说明

### 8.1 一手来源

**功能基准（契约层）**：

- `@anthropic-ai/claude-agent-sdk@0.3.233` 的 `package/sdk.d.ts` —— `npm pack @anthropic-ai/claude-agent-sdk` 解包取得
- [Agent SDK 官方文档](https://docs.claude.com/en/api/agent-sdk/overview)

**参考解法**（链接均指向 `eb4307f`，仅用于哈希与行号可复现）：

- [s03_permission/code.py](https://github.com/shareAI-lab/learn-claude-code/blob/eb4307f4e495d2ed22699e1e5682eb55f8076ade/s03_permission/code.py)
- [s03_permission/README.zh.md](https://github.com/shareAI-lab/learn-claude-code/blob/eb4307f4e495d2ed22699e1e5682eb55f8076ade/s03_permission/README.zh.md)
- [s04_hooks/code.py](https://github.com/shareAI-lab/learn-claude-code/blob/eb4307f4e495d2ed22699e1e5682eb55f8076ade/s04_hooks/code.py)（仅用于界定 s03 的上界）
- 站点数据：
  [versions.json](https://github.com/shareAI-lab/learn-claude-code/blob/eb4307f4e495d2ed22699e1e5682eb55f8076ade/web/src/data/generated/versions.json) ·
  [execution-flows.ts](https://github.com/shareAI-lab/learn-claude-code/blob/eb4307f4e495d2ed22699e1e5682eb55f8076ade/web/src/data/execution-flows.ts) ·
  [annotations/s03.json](https://github.com/shareAI-lab/learn-claude-code/blob/eb4307f4e495d2ed22699e1e5682eb55f8076ade/web/src/data/annotations/s03.json) ·
  [scenarios/s03.json](https://github.com/shareAI-lab/learn-claude-code/blob/eb4307f4e495d2ed22699e1e5682eb55f8076ade/web/src/data/scenarios/s03.json)
- 关键提交：
  [4d8d420 `fix(s03): let Gate 2 own the workspace boundary instead of safe_path`](https://github.com/shareAI-lab/learn-claude-code/commit/4d8d420e411d9307b99e5be721037b28bf42867b) ·
  [ab35e59 `Refine course progression and runtime safety`](https://github.com/shareAI-lab/learn-claude-code/commit/ab35e59672e3501373f51c2fdf18492ab09b21d4) ·
  [PR #512 合并提交 eb4307f](https://github.com/shareAI-lab/learn-claude-code/commit/eb4307f4e495d2ed22699e1e5682eb55f8076ade)

### 8.2 API 与 Python 语义（一手）

- [Anthropic：Implement tool use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/implement-tool-use)
- [Anthropic：Handle tool calls](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)
- [Python：pathlib](https://docs.python.org/3/library/pathlib.html)
- [Python：subprocess](https://docs.python.org/3/library/subprocess.html)

### 8.3 官网已不可作为基准（本课的核心版本结论）

见「研究范围与版本证据」首节。要点：官网 s03 的「源码」与「学习」tab 展示的是 `1baf1ac` 版（仍含 `safe_path`、
规则 1 只覆盖 `write_file`/`edit_file`、message 为 `Writing outside workspace`），
与其 s02 载荷所属的提交都不一致，成因是 `4d8d420` 改源码时未重新生成 `web/src/data/generated/`。
**若照官网实现 s03，会实现一个上游已主动废弃的设计。**

### 8.4 仍存在的来源限制

- 上游 `main` 仍在演进，本文引用的提交只保证哈希与行号可复现，不再作为功能基准（见 ADR-0005）。
  课程源码若已变动，以「参考解法的另一版」对待即可，不需要重新核对对等性。
- `.d.ts` 抽取自 `@anthropic-ai/claude-agent-sdk@0.3.233`。SDK 按周演进，实现前应重新 `npm pack` 确认契约未变。
- `README.zh.md` 与 `code.py` 在三处文案上不一致（见 2.11），上游未修正。本文一律以 `code.py` 为准。
- 改版删除了 s03 的「深入 CC 源码」附录，因此本课没有官方的生产级实现对照材料；
  第七节中关于 CC 权限系统的描述来自旧版 README，仅作边界说明，不构成本课事实。
- `run_bash` 未指定 encoding，其解码行为随运行环境 locale 变化。本文实测环境为 UTF-8 locale。
- 闸门 3 的实测通过替换 `sys.stdin` 完成，未使用真实 TTY；真实终端下 `readline` 绑定可能影响输入编辑体验，
  但不影响 `strip().lower()` 后的判定语义。

## Comments

- 2026-08-14：完成 s03 课程基线研究。研究过程中发现官网数据陈旧且自相矛盾，用户决策改为跟随上游 `main`，
  锁定提交 `eb4307f`，课程由 20 课缩为 17 课并跟随删除 System Prompt / Error Recovery / Comprehensive 三课。
  `CLAUDE.md` 的「基准来源」与循环第 1 步已相应修订。核心发现：s03 不是纯加法——`safe_path` 被上游主动删除，
  工作区边界降级为闸门 2 的询问；`run_bash` 同时失去内置 denylist、UTF-8 强制解码与 `OSError` 兜底。
  这三点与本项目现有的 `Workspace` 深 Module、`BashTool` denylist、ADR-0004 直接冲突，是 Grill 阶段的首要议题。
  尚未进行 Python → Java 映射或任何实现。

- 2026-08-16：完成基准变更 Grill（16 项决定），功能基准从「课程 `code.py` 功能对等」改为「Claude Code 契约对齐」，
  已落 [ADR-0005](../../../docs/adr/0005-基准从课程功能对等改为契约对齐.md)。本票据此修订四处：新增「契约基线」节
  （`sdk.d.ts` 权限模型的名全录清单，以及与 `code.py` 的 7 项差距）；第四节验收标准降级为「参考解法的行为断言」，
  实际验收改走契约核对 + 端到端场景 + 边界单测三分工；第七节把「契约里有但被 `code.py` 裁掉」的 4 项从「不属于本课」
  移出，改为必须在 Grill 时逐条裁决并显式标记；锁定提交降级为可复现性引用，不再是基准。全部实测事实原样保留。

  新增的核心发现：契约差距的前 3 项是**结构性**的 —— `PermissionResult.deny` 必须带 `message`（`code.py` 把拒绝原因
  整个丢给了终端，模型只收到固定串）、`ask` 在契约里是独立状态而非藏在控制流里、`CanUseTool` 是宿主可注入的回调而非
  写死的模块级函数。这三点直接决定 Java 设计的形状，与既有的三条冲突并列为 Grill 首要议题。

  另已定：s03 仍按 `code.py` 的内联管线实现，s04 实现 hook 系统时再迁移到 `PreToolUse` —— s15 揭示生产实现中权限就是
  `PreToolUse` hook，这段「内联 → hook」的迁移过程本身是学习内容。仍未进行 Java 映射或任何实现。
