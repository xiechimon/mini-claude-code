# 01 s01 Agent Loop 课程基线

Type: research

Status: resolved

> 结论：s01 的完整运行时范围是一个持久会话 REPL、一个 `bash` 工具、一个非流式 Messages API
> 工具循环，以及教学用途的轻量命令执行边界。本文只记录网站当前版本的事实、行为和验收标准；不包含任何 Java 方案，也不把后续课次或“深入
> CC 源码”中的生产机制算进 s01。

## 研究范围与版本证据

- 研究对象：官网 s01 当前中文页面的“学习/讲解”“源码”“深入探索”三部分，核对日期为
  2026-08-10。[官网 s01](https://learn.shareai.run/zh/s01/)
- 官网“源码”标签内嵌的 `s01_agent_loop/code.py` 与官方仓库固定提交 `1baf1aca5af439694cb3a1772c0b1ab44b482a01`
  的文件逐字节一致，SHA-256 均为
  `f0bed0e007fd4ab5307f61862d2ea5241e11918b177482a8a8a89674a9bd3334`。[固定版本源码](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/code.py)
- 官网“学习”和“深入探索”的正文与同一固定提交的中文 README 内容一致；该 README 的 SHA-256 为
  `1b52271434d051ccb37b1e6c64153a4928fd504eacb9e3de7fff783b5f40e81e`。[固定版本讲解](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/README.md)
- 官网卡片显示 `102 LOC`、`1 个工具`、`Minimal model/tool loop`。源码文件实际有 137 个物理行、113 个非空行；若排除空行和以
  `#` 开头的整行注释，恰为 102 行。因此 `102 LOC` 是展示统计口径，不能据此忽略 docstring、注释所对应的运行约定或 REPL
  代码。[官网 s01](https://learn.shareai.run/zh/s01/) · [固定版本源码](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/code.py)

## 一、课程要解决的问题

普通模型可以生成 Bash 命令，却不会自行执行，也不会自动看到执行结果后继续推理；人必须手动执行、复制结果、再次提问。s01
要自动化的正是这个中间层：模型决定是否行动，harness
执行工具并把结果放回消息历史。[讲解：问题与解决方案](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/README.md#%E9%97%AE%E9%A2%98)

课程把最小闭环归纳为两个信号：

| 响应信号                             | 教学版动作                                                                             |
|--------------------------------------|----------------------------------------------------------------------------------------|
| `response.stop_reason == "tool_use"` | 执行响应中的全部 `tool_use` 块，把结果作为下一条 `user` 消息加入历史，然后再次请求模型 |
| `response.stop_reason != "tool_use"` | 当前 `agent_loop` 立即返回                                                             |

这与 Claude Messages API 的客户端工具协议一致：`tool_use` 响应携带一个或多个工具调用块，调用方执行后用只包含对应
`tool_result` 块的 `user`
消息继续会话。[Anthropic：Handle tool calls](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls) · [Anthropic：Stop reasons](https://platform.claude.com/docs/en/build-with-claude/handling-stop-reasons)

## 二、完整功能清单

### 2.1 启动与配置

1. 依赖为 `anthropic>=0.25.0`、`python-dotenv>=1.0.0`，仓库全局 requirements 还声明 `pyyaml>=6.0`，但 s01 源码未导入或使用
   PyYAML。[requirements.txt](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/requirements.txt)
2. 启动时调用 `load_dotenv(override=True)`：自动查找并加载 `.env`，且 `.env` 中的同名值覆盖进程原有环境变量。该覆盖行为是源码明确选择，不是
   `python-dotenv`
   的默认值。[源码](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/code.py#L42-L47) · [python-dotenv 官方文档](https://bbc2.github.io/python-dotenv/#getting-started)
3. `ANTHROPIC_API_KEY` 和 `MODEL_ID` 是课程配置要求；`.env.example` 还提供可选的 `ANTHROPIC_BASE_URL`，用于
   Anthropic-compatible
   provider。[讲解：准备](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/README.md#%E8%AF%95%E4%B8%80%E4%B8%8B) · [.env.example](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/.env.example)
4. 若 `ANTHROPIC_BASE_URL` 为非空值，源码先从当前进程环境删除 `ANTHROPIC_AUTH_TOKEN`，再把该 Base URL 传给
   `Anthropic(...)`；API key 未显式传参，由 SDK
   从环境读取。[源码](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/code.py#L46-L52) · [Anthropic Python SDK](https://github.com/anthropics/anthropic-sdk-python#usage)
5. `MODEL_ID` 用 `os.environ["MODEL_ID"]` 直接读取，没有默认值；缺失会在进入 REPL 前暴露配置错误。
6. system prompt 在模块初始化时由当前工作目录拼成：
   `You are a coding agent at {cwd}. Use bash to solve tasks. Act, don't explain.`
   。它告诉模型工作目录、唯一操作途径和输出倾向。[源码](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/code.py#L51-L54)
7. 尝试导入可选的 `readline`，并通过四条 `parse_and_bind` 设置处理 macOS/libedit 中文输入退格问题；平台没有该模块时只忽略
   `ImportError`，其余程序继续。Python 文档确认 `readline` 是可选 Unix 模块、可能由 macOS 的 libedit 实现，其设置会影响内置
   `input()`。[源码](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/code.py#L31-L40) · [Python readline 文档](https://docs.python.org/3/library/readline.html)

### 2.2 唯一工具：`bash`

1. 对模型只注册一个客户端工具：名称 `bash`，描述 `Run a shell command.`。
2. 输入 schema 是 JSON object，仅声明一个 string 属性 `command`，并把 `command` 列为 required；schema 未声明
   `additionalProperties: false`。[源码](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/code.py#L57-L65)
3. s01 没有独立的 read/write/edit/find 工具；读文件、写文件、找文件等都由模型分别生成 `cat`、`echo ... >`、`find` 等 shell
   命令完成。[讲解：接下来](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/README.md#%E6%8E%A5%E4%B8%8B%E6%9D%A5)

### 2.3 Bash 执行行为

`run_bash(command: str) -> str`
的完整行为如下：[源码](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/code.py#L69-L81)

1. 先做大小写敏感、纯子串式 denylist 检查。任一命中 `rm -rf /`、`sudo`、`shutdown`、`reboot`、`> /dev/`，不启动子进程，直接返回
   `Error: Dangerous command blocked`。
2. 其余命令交给 `subprocess.run`：`shell=True`、`cwd=os.getcwd()`、`capture_output=True`、`text=True`、`timeout=120`。
3. `shell=True` 意味着输入字符串由平台 shell
   解释，可使用管道、通配符、变量展开和重定向；因此这不是参数化进程调用。[Python subprocess 文档](https://docs.python.org/3/library/subprocess.html#frequently-used-arguments)
4. 每次工具调用都会新建一个 shell 子进程；某次命令中的 `cd`、`export` 等只影响该子进程，不会改变下一次工具调用的父进程 cwd
   或环境。文件等外部副作用则会保留。
5. stdout 与 stderr 被分别捕获，完成后按 `stdout + stderr` 的固定顺序连接，再整体 `strip()`；这不保留两个流真实发生时的交错顺序。
6. 非空结果最多返回前 50,000 个 Python 字符；空结果返回精确文本 `(no output)`。
7. 120 秒超时捕获为精确文本 `Error: Timeout (120s)`。Python 的 `subprocess.run` 在 timeout 后会终止并等待子进程，再抛出
   `TimeoutExpired`。[Python subprocess 文档](https://docs.python.org/3/library/subprocess.html#subprocess.run)
8. `FileNotFoundError` 或其他 `OSError` 被转为 `Error: {exception-text}`。
9. 没有传 `check=True`，也不读取 `returncode`；因此命令非零退出本身不会被标记成工具错误，函数仍返回捕获到的文本，若两路均空则仍为
   `(no output)`。[Python subprocess 文档](https://docs.python.org/3/library/subprocess.html#subprocess.run)
10. 返回值始终是普通字符串。即便字符串以 `Error:` 开头，构造 `tool_result` 时也没有设置 Anthropic 协议的可选
    `is_error: true`。[源码](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/code.py#L99-L113) · [Anthropic：工具错误字段](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls#handling-errors-with-is_error)

### 2.4 Agent Loop

`agent_loop(messages)` 接受一个可变消息
list，并在原对象上追加历史。每轮严格执行以下步骤：[源码](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/code.py#L85-L113)

1. 非流式调用 `client.messages.create`，每次都传当前 `MODEL`、`SYSTEM`、完整 `messages`、同一 `TOOLS` 和 `max_tokens=8000`
   。Messages API
   是无服务器会话状态的多轮接口，因此历史由调用方随每次请求重传。[Anthropic Messages API](https://platform.claude.com/docs/en/api/python/messages/create)
2. 无论响应因何停止，先把完整 `response.content` 作为一条 `assistant` 消息追加到历史。
3. 若 `response.stop_reason != "tool_use"`，立即返回，不执行任何恢复或续写。实际因此把 `end_turn`、`max_tokens`、
   `stop_sequence`、`pause_turn`、`refusal`、`model_context_window_exceeded` 等所有非 `tool_use`
   原因都当作本轮终点；这是源码行为，而非它们在生产系统中都代表“成功完成”。可用 stop reason
   的官方定义见 [Anthropic Stop reasons](https://platform.claude.com/docs/en/build-with-claude/handling-stop-reasons)。
4. 若为 `tool_use`，顺序遍历 `response.content`；只处理 `block.type == "tool_use"`，同一响应中的 text 等其他块保留在
   assistant 历史里但不执行。
5. 对每个 `tool_use` 块，源码直接读取 `block.input["command"]` 并调用 `run_bash`。没有另行校验 `block.name`，原因是 s01
   只向模型暴露一个工具；输入缺少 `command` 等形状错误不会在本地 fallback，而会直接暴露异常。
6. 同一响应可以含多个 `tool_use` 块。它们按内容块顺序同步、串行执行，不并发。
7. 每次执行前在终端用黄色 ANSI 输出 `$ {command}`；执行后只向终端预览 `output[:200]`，无额外截断标记。
8. 给模型的结果不是 200 字符预览，而是 `run_bash` 返回的完整值（上限 50,000 字符）。每项结构为
   `{type: "tool_result", tool_use_id: block.id, content: output}`。
9. 本轮所有结果汇总为一个 list，再作为一条 `role: "user"` 消息追加。每个 `tool_result.tool_use_id` 精确关联原
   `tool_use.id`；随后回到循环顶部。
10. 没有 max-turn 或总时长上限；只要模型持续返回 `tool_use`，循环就会持续。若响应声称 `tool_use` 却没有任何对应内容块，源码会追加一个空的
    user result list 后继续。
11. 配置、SDK/API、消息形状等异常没有总 catch；除 `run_bash` 明确捕获的两类执行异常外，未知错误直接暴露。

典型历史形状：

```text
user      content = 用户问题字符串
assistant content = [text?/tool_use(id, name="bash", input.command), ...]
user      content = [tool_result(tool_use_id=id, content=完整命令输出), ...]
assistant content = [text ...]  # stop_reason 非 tool_use，agent_loop 返回
```

### 2.5 交互式 REPL 与多轮会话

1. 仅在文件作为主程序运行时进入 REPL；导入模块不会启动交互循环。
2. 启动输出两行：`s01: Agent Loop` 和 `输入问题，回车发送。输入 q 退出。`，其后有空行。
3. 输入提示为青色 ANSI 包裹的 `s01 >> `。
4. EOF 或 `KeyboardInterrupt` 退出最外层循环，不打印 traceback。
5. 对输入先 `strip().lower()`；空行、`q`、`exit`（大小写和两侧空白不敏感）均直接退出，不把该输入加入历史，也不调用模型。
6. 其他输入按原字符串（不 strip）追加为 `{"role": "user", "content": query}`，再调用 `agent_loop(history)`。
7. `history` 只在 REPL 启动时创建一次，处理新问题前不会清空；因此同一进程中的后续问题携带此前全部用户、assistant、tool
   result 历史，是连续多轮会话。
8. `agent_loop` 返回后，REPL 读取 `history[-1]["content"]`。仅当它是 list 时，遍历并打印所有 `type == "text"` 块的
   `block.text`
   ；其他块忽略。最后额外打印一个空行，然后继续提示下一问。[源码](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/code.py#L117-L137)

### 2.6 教学演示场景与安全提示

官网给出的运行方式为先安装 requirements、复制并填写 `.env`，再运行 `python s01_agent_loop/code.py`。建议观察三类任务：创建
`hello.py`、列出当前目录全部 Python 文件、查询当前 Git
分支。[讲解：试一下](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/README.md#%E8%AF%95%E4%B8%80%E4%B8%8B)

官网明确警告：该教学 demo 会执行模型生成的 shell 命令，建议放在临时测试目录；真正的权限系统到 s03 才出现。当前五项 denylist
只是源码已有的窄保护，不是沙箱、审批系统或完整命令安全策略。[讲解：教学 demo 提示](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/README.md#%E8%AF%95%E4%B8%80%E4%B8%8B)

## 三、输入/输出行为总表

| 边界      | 输入                                               | 可观察输出或状态变化                                                          |
|-----------|----------------------------------------------------|-------------------------------------------------------------------------------|
| 进程配置  | `.env`/环境中的 API key、`MODEL_ID`，可选 Base URL | 创建 SDK client；缺少必需配置时启动失败，不进入 REPL                          |
| REPL      | 普通非空文本                                       | 原样追加为 user 消息，触发一次完整 `agent_loop`                               |
| REPL      | 空白、`q`、`exit`，任意大小写/两侧空白             | 静默退出，不调用模型                                                          |
| REPL      | EOF、Ctrl-C                                        | 静默退出最外层循环，不显示 traceback                                          |
| 模型请求  | 当前完整 history                                   | 返回结构化 content blocks 和 `stop_reason`                                    |
| 模型响应  | `stop_reason != tool_use`                          | assistant content 已入 history；loop 返回；REPL 打印其中所有 text 块          |
| 模型响应  | 一个或多个 `tool_use` 块                           | 逐个顺序执行；终端显示命令和最多 200 字符输出预览                             |
| Bash 工具 | 命中五项危险子串                                   | 不执行；返回 `Error: Dangerous command blocked`                               |
| Bash 工具 | 正常命令                                           | 在当前进程 cwd 经 shell 执行，返回 `strip(stdout + stderr)`，最多 50,000 字符 |
| Bash 工具 | `cd`/`export` 等进程内状态修改                     | 只影响本次新建的 shell；下一次工具调用仍从父进程 cwd/环境开始                 |
| Bash 工具 | 无 stdout/stderr                                   | 返回 `(no output)`                                                            |
| Bash 工具 | 超过 120 秒                                        | 返回 `Error: Timeout (120s)`                                                  |
| Bash 工具 | `FileNotFoundError`/`OSError`                      | 返回 `Error: {异常文本}`                                                      |
| 工具反馈  | 每个命令的完整 `run_bash` 返回值                   | 汇成一条 user 消息中的 `tool_result[]`，以 `tool_use_id` 配对，再请求模型     |
| 连续提问  | 第二次及之后普通输入                               | 复用同一 `history`，保留此前所有对话和工具交互                                |

## 四、可验证验收标准

下面是从官网讲解和固定源码逐项推出的最小、完整验收集。模型响应应使用 stub/fake 控制，以免把模型随机性误当作 harness 行为。

### A. 配置与启动

- [ ] 正确配置依赖、API key 和 `MODEL_ID` 后能进入 REPL，并输出规定标题、说明和提示符。
- [ ] `.env` 同名值能覆盖进程已有环境值。
- [ ] 配置 Base URL 时，client 收到该 URL，且 `ANTHROPIC_AUTH_TOKEN` 在创建 client 前被移除。
- [ ] 缺少 `MODEL_ID` 时错误直接暴露，不使用默认模型、不静默 fallback。
- [ ] `readline` 不可用时仍能运行；可用时执行四条绑定设置。
- [ ] system prompt 精确包含启动时当前工作目录及 `Use bash to solve tasks. Act, don't explain.`。

### B. 工具声明

- [ ] 发给模型的工具列表只有 `bash`。
- [ ] `bash` schema 要求 string 类型的 `command`。
- [ ] 每次模型请求均携带相同 `TOOLS`、`SYSTEM`、配置的 `MODEL` 和 `max_tokens=8000`。

### C. Bash 执行

- [ ] 五个危险子串逐一命中时均不启动命令，返回精确阻断文本。
- [ ] 未命中时使用 shell、当前 cwd、文本模式和独立 stdout/stderr 捕获执行。
- [ ] 每次调用使用独立子进程，前一次 shell 内的 `cd`/`export` 不泄漏到后一次调用。
- [ ] 正常输出按 `stdout + stderr` 后整体 strip；空输出为 `(no output)`。
- [ ] 结果超过 50,000 字符时只返回前 50,000 字符。
- [ ] 超过 120 秒返回精确 timeout 文本。
- [ ] 非零退出码本身不抛 `CalledProcessError`；仍按捕获内容或 `(no output)` 返回。
- [ ] `FileNotFoundError`/`OSError` 分支返回带 `Error:` 前缀的异常文本。

### D. 核心循环

- [ ] 首次用户问题位于首条 user 消息；每轮请求前保留此前全部消息。
- [ ] 每个模型响应的 content 都先以 assistant 消息追加，再判断 stop reason。
- [ ] 任意非 `tool_use` stop reason 都立即结束当前 agent loop，不重试、不恢复。
- [ ] `tool_use` 响应中的所有工具调用按块顺序执行；非 tool-use 内容块不执行但仍留在 assistant 历史。
- [ ] 多个工具调用被同步顺序执行，并在同一条 user 消息中得到一一对应、顺序一致的 `tool_result`。
- [ ] 每个 `tool_result.tool_use_id` 等于原调用 id，content 为完整工具返回值，且不添加 `is_error`。
- [ ] 终端在执行前显示黄色 `$ command`，执行后最多预览 200 字符；送回模型的内容仍可达到 50,000 字符。
- [ ] 追加工具结果后继续调用模型，直到出现非 `tool_use` stop reason。
- [ ] 连续 `tool_use` 不受本地轮次上限打断；SDK/API 或消息形状异常不被静默吞掉。

### E. REPL

- [ ] 空输入、`q`、`exit` 的大小写/空白变体均退出且不请求模型。
- [ ] EOF 和 Ctrl-C 不打印 traceback。
- [ ] 普通输入不被 trim 后再入 history，而是保留用户原始字符串。
- [ ] 同一进程的多次普通输入复用一个 history。
- [ ] 每次 agent loop 结束后只打印最终 content 中的 text 块，按块顺序打印，并追加空行。

### F. 官网三个示例的端到端关键路径

- [ ] 在临时目录中，创建 `hello.py` 的提示能经 `bash` 工具产生文件，并在工具结果反馈后得到最终文本响应。
- [ ] “列出全部 Python 文件”能经 shell 命令取得当前目录结果并反馈给模型。
- [ ] “当前 Git 分支”能执行 Git 查询并反馈给模型。
- [ ] 三个场景都能明确观察到：收到 `tool_use` 时循环继续，最终非 `tool_use` 时循环停止。

## 五、Python 特有实现点

以下是源码采用的 Python/库级表达或运行特征。它们用于理解现有基准，不是 Java 设计建议。

| Python 特有点                            | 当前源码中的具体含义                                                                            |
|------------------------------------------|-------------------------------------------------------------------------------------------------|
| `if __name__ == "__main__"`              | 区分脚本启动与被导入；只有脚本启动进入 REPL                                                     |
| `list`/`dict` 直接表达消息和 JSON Schema | 消息历史、工具声明、tool result 都是动态容器；`messages: list` 只是类型注解，没有运行时元素校验 |
| Anthropic SDK content block 对象         | 响应用 `block.type`、`block.input`、`block.id`、`block.text` 属性读取，输入消息则用 dict 构造   |
| 可变 list 传参                           | `agent_loop` 原地修改调用者持有的 `history`，返回值为 `None`                                    |
| `load_dotenv(override=True)`             | `.env` 写入 `os.environ` 并优先于启动进程原值；这是 python-dotenv 特性                          |
| `os.getenv` 与 `os.environ[...]` 混用    | Base URL 缺失可得 `None`；`MODEL_ID` 缺失则立即抛错                                             |
| `os.environ.pop(..., None)`              | 有 Base URL 时原地删除 auth token，缺失也不报错                                                 |
| f-string                                 | system prompt、ANSI 命令输出和异常文本在运行时插值                                              |
| `subprocess.run(..., shell=True)`        | 由平台 shell 解释整段字符串；shell 行为和可用命令具有平台差异                                   |
| `capture_output=True, text=True`         | stdout/stderr 分别成为 Python 字符串；随后由源码手工连接                                        |
| 字符串切片 `[:50000]` / `[:200]`         | 以 Python 字符数截断，不是字节数或 token 数；工具返回与终端预览使用不同上限                     |
| 异常分支                                 | timeout、OS error 转成普通字符串；API 异常、配置错误、输入形状错误未捕获，会直接暴露            |
| `readline` 可选导入                      | Unix/macOS 终端输入增强；缺模块仅跳过，macOS/libedit 的中文退格通过四条绑定处理                 |
| ANSI 转义串                              | 提示符使用青色、命令使用黄色；没有检测终端是否支持颜色                                          |
| `getattr(block, "type", None)`           | 最终输出阶段对未知对象安全忽略，只打印 text block                                               |

## 六、“深入探索”完整记录

本节逐项保留官网深入区的四部分。它们是课程对生产级 Claude Code（CC）源码的比较说明，不是 s01 教学程序已经具备的功能，也不得纳入
s01
实现验收。[官网深入区/固定 README](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/README.md#%E6%8E%A5%E4%B8%8B%E6%9D%A5)

### 6.1 循环结构差异

- 教学版以非流式响应的 `response.stop_reason` 判断是否继续。
- 官网称 CC 不把 stop reason 当作 query loop 的唯一继续依据，因为流式过程中 stop reason 可能尚未更新而 `tool_use`
  内容块已经到达。
- 官网核查的 CC `query.ts` 使用 `needsFollowUp`：流式消息中一旦看到 `tool_use` 就置 `true`；`QueryEngine.ts` 仍从
  `message_delta` 捕获真实 stop reason 供其他逻辑使用，但 query loop 以 `needsFollowUp` 决定是否跟进。
- 官网给出的关键片段及定位是 `query.ts:554-558` 的注释与 `let needsFollowUp = false`，置位发生在 `query.ts:830-834`。
- 官方 Messages API 文档说明非流式响应的 stop reason 非 null，而流式生命周期中至少早期事件可能尚无最终 stop
  reason；这解释了教学版非流式判断与深入区流式实现关注点不同。[Anthropic Messages API response schema](https://platform.claude.com/docs/en/api/typescript/messages/create)

### 6.2 State 对象 10 个字段

官网称教学版只显式维护 `messages`，其核查的 CC State 还包含以下字段：

| #  | 字段                           | 官网给出的用途                  | 对应课程 |
|----|--------------------------------|---------------------------------|----------|
| 1  | `messages`                     | 当前迭代的消息数组              | s01      |
| 2  | `toolUseContext`               | 工具、信号、权限上下文          | s02      |
| 3  | `autoCompactTracking`          | 压缩状态追踪                    | s08      |
| 4  | `maxOutputTokensRecoveryCount` | token 恢复尝试次数，上限 3      | s11      |
| 5  | `hasAttemptedReactiveCompact`  | 本轮是否已尝试响应式压缩        | s08      |
| 6  | `maxOutputTokensOverride`      | 8K → 64K 的升级覆盖             | s11      |
| 7  | `pendingToolUseSummary`        | 后台 Haiku 生成的 tool use 摘要 | s08      |
| 8  | `stopHookActive`               | 停止钩子是否产生阻塞错误        | s04      |
| 9  | `turnCount`                    | 轮次计数，用于 maxTurns 检查    | s01      |
| 10 | `transition`                   | 上一次继续原因                  | s11      |

官网特别说明：`taskBudgetRemaining`（其定位为 `query.ts:291`）是 loop-local 局部变量，不属于 State；源码注释为
`Loop-local (not on State)`。

### 6.3 多条退出与继续路径

教学版只有源码层面的一条终止判断：stop reason 不是 `tool_use` 就返回。官网称生产版另外覆盖以下退出或继续场景：

- blocking limit
- prompt too long
- model error
- abort
- hook stop
- max turns
- token budget continuation
- reactive compact retry

这些路径在生产版各有恢复或退出策略；s01 没有这些策略。

### 6.4 流式工具执行与 QueryEngine

- 官网称 CC 的 `StreamingToolExecutor`（其定位为 `query.ts:561`）会在模型仍在生成时开始工具执行。
- 工具是否并发或独占取决于该工具是否 concurrency-safe。
- 官网称 `QueryEngine.ts` 还包含费用超限、结构化输出验证失败等保护。
- s01 明确不实现上述机制；它使用非流式请求，并在完整响应到达后顺序执行工具，目标是呈现最小循环而非生产性能与恢复能力。

深入区的最终归纳是：课程把 CC `query.ts` 的 1729 行视为围绕同一核心循环叠加保护机制；s01 要学习的是这个核心，不是复制这些生产保护。

## 七、明确不属于 s01 的能力

为防止把后续课程或深入区内容提前混入，以下均不属于当前功能基准：

- 独立的 read/write/edit/find/glob/grep 等结构化工具
- 工具 dispatch map、并行工具执行、concurrency-safe 判定
- 真正的权限规则、审批管线、沙箱或完整危险命令识别
- hooks、TodoWrite、subagent、skills、上下文压缩、持久记忆
- 系统 prompt 动态分段装配、错误恢复、fallback model、重试策略
- task system、后台任务、cron、teams、协议、自主认领、worktree、MCP
- 流式 Messages API、`needsFollowUp`、`StreamingToolExecutor`、QueryEngine 生产保护
- 会话落盘、恢复、fork；s01 的 history 只存在当前进程内
- 对 `max_tokens`、refusal、context overflow 等非 `tool_use` stop reason 的专项处理

## 八、来源与可信度说明

### 功能基准（一手）

- [s01 当前官网](https://learn.shareai.run/zh/s01/)
- [官方课程仓库：固定版本 README](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/README.md)
- [官方课程仓库：固定版本 code.py](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/s01_agent_loop/code.py)
- [官方课程仓库：固定版本 .env.example](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/.env.example)
- [官方课程仓库：固定版本 requirements.txt](https://github.com/shareAI-lab/learn-claude-code/blob/1baf1aca5af439694cb3a1772c0b1ab44b482a01/requirements.txt)

### API 与 Python 语义（一手）

- [Anthropic：Create a Message](https://platform.claude.com/docs/en/api/python/messages/create)
- [Anthropic：Handle tool calls](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)
- [Anthropic：Stop reasons and fallback](https://platform.claude.com/docs/en/build-with-claude/handling-stop-reasons)
- [Anthropic 官方 Python SDK](https://github.com/anthropics/anthropic-sdk-python)
- [Python：subprocess](https://docs.python.org/3/library/subprocess.html)
- [Python：readline](https://docs.python.org/3/library/readline.html)
- [python-dotenv 官方文档](https://bbc2.github.io/python-dotenv/)

### 仍存在的来源限制

- 官网深入区引用并概述了 CC 的 `src/query.ts` / `QueryEngine.ts`，但该课程页没有提供对应上游文件、具体 CC 版本或可复现
  commit 的第一方永久链接。因此本文完整记录官网四部分内容，并用“官网称/官网核查”标示；不会把具体行号和字段集合提升为不受版本约束的
  Claude Code 公共契约。
- 上述限制不影响 s01 教学版的功能基准：官网内嵌源码、官方仓库固定源码、官网讲解与固定 README 已完成一致性核对。

## Comments

- 2026-08-10：完成官网“讲解、源码、深入探索”研究并锁定固定提交；尚未进行 Python → Java 映射或任何实现。
- 2026-08-10：Java 映射 Grill 第一轮达成共识：课程代码采用单一实现持续演进，由 Git 历史和课程票保留阶段差异；s01 的
  `bash` 工具只承诺 macOS/Linux 的 POSIX shell 行为，以 `/bin/sh -c` 执行；`ANTHROPIC_BASE_URL` 按网站基线保持可选，缺失时使用
  SDK 默认的 Anthropic 官方端点，并相应修订 ADR-0001。尚未授权实现。
- 2026-08-10：Java 映射 Grill 第二轮达成共识：Conversation History 与 content block 复用官方 SDK 协议类型，模型调用通过小型
  `ModelClient` 接缝隔离生产 SDK adapter 与测试 fake adapter，并形成 ADR-0002；配置使用 `.env` 覆盖继承变量后的 Effective
  Environment，显式配置 SDK 且完整传给 Bash 子进程；REPL 采用 JLine，显式处理 Ctrl-C、EOF 和 Unicode 行编辑。核心术语已沉淀到
  `CONTEXT.md`。尚未授权实现。
- 2026-08-10：Java 映射 Grill 第三轮达成共识：Agent Loop 是有状态 Module，以
  `List<String> respond(String rawInput)` 作为行为 Interface，自身持有跨 Turn 的 Conversation History；过程输出通过构造时注入的
  `PrintWriter` 实时产生。`ModelClient` 使用 `Message create(MessageCreateParams request)`，由 Agent Loop 集中维护 model、system、
  tools、max tokens 和完整历史等请求不变量。Turn 失败不回滚已追加的 Conversation History，保持原课程的原地状态语义。尚未授权实现。
- 2026-08-10：Java 映射 Grill 第四轮达成共识：`BashTool` 作为只有 `execute(command)` 行为的 package-private 深 Module，直接以
  `/bin/sh` 和临时目录验证，不建立 `ShellRunner` port；`Repl` 独立封装 JLine 输入与终端渲染；`EffectiveEnvironment` 作为不可变
  Module 集中维护配置合并和子进程环境。Conversation History 完全私有，通过 fake `ModelClient` 收到的请求验证；线程中断不转成 Tool
  Result，由 `BashTool.execute` 和 `AgentLoop.respond` 显式抛出 `InterruptedException`。尚未授权实现。
- 2026-08-10：Java 映射 Grill 第五轮达成共识：除 `Main` 外，s01 Module 与 `ModelClient` 均为 package-private；production
  adapter 直接使用 `client.messages()::create`，不创建浅转发类；Tool Call 输入通过 `BashInput` record 与 SDK `JsonValue.convert`
  解码。`BashTool` 的生产 factory 固定 120 秒，package-private 构造入口允许测试注入短 timeout。使用 JUnit + fake
  `ModelClient` 聚焦验证确定性行为，真实模型只用于人工关键路径。尚未授权实现。
- 2026-08-10：Java 映射 Grill 第六轮达成共识：`BashInput` 保留 record 映射，但忽略额外 JSON 字段并显式拒绝 null
  `command`，从而保持 Python 版“只读取 command”的行为。`dotenv-java` 只作为 `.env` 文件解析 adapter，项目配置语义完全归
  `EffectiveEnvironment` 所有：只读取 file-only entries，以 `.env` 覆盖 inherited environment，禁止使用库自身 host-env-first 的
  `get()` 结果；从 working directory 向上查找首个 `.env`，缺失忽略，畸形内容直接失败。接受并记录不支持 `${VAR}` 展开和完整
  python-dotenv grammar 的 Python 特有差异。尚未授权实现。
- 2026-08-10：Java 映射 Grill 第七轮达成共识：课程对等优先，配置模板从 Base URL + auth token 迁移为
  `ANTHROPIC_API_KEY`；新增并忽略 `.env`，以 `.env.example` 声明相同配置。`Main` 是 composition root，负责捕获 cwd、加载
  Effective Environment、构造 SDK、创建并关闭 JLine Terminal，
  再装配各 Module。`AgentLoop` 构造 Interface 显式接收 `ModelClient`、`BashTool`、model ID、working directory 和
  `PrintWriter`，不增加 settings 包装。尚未授权实现。
- 2026-08-10：Java 映射 Grill 第八轮达成共识：配置值不 trim、不把 blank 归一化为缺失，保持网站的原始字符串语义；`.env.example` 延续
  DeepSeek 兼容端点与 `deepseek-v4-flash` 示例，但使用 API key 占位符。等待 Bash 时线程中断会先强制终止并等待直接 shell
  进程，再重新抛出 `InterruptedException`，不扩展为进程树治理。所有关键终端输出 flush 后检查 `PrintWriter.checkError()`，写入失败
  直接抛错，不静默吞掉。尚未授权实现。
- 2026-08-10：Java 映射 Grill 第九轮达成共识：认证来源严格映射课程源码——自定义 Base URL 存在时移除
  `ANTHROPIC_AUTH_TOKEN` 并要求 `ANTHROPIC_API_KEY`，官方端点则允许 SDK 支持的 API key 或 auth token；不把
  `anthropic.apiKey`、`anthropic.authToken`、`anthropic.baseUrl` 等 JVM system property 纳入项目配置契约，所有配置只来自继承环境
  与向上查找到的首个 `.env`，统一由 `EffectiveEnvironment` 定义。
- 2026-08-10：用户确认完整 Java 映射共识；设计 frontier 已清空，课程票转为 `ready-for-agent`，后续可按已确认方案进入功能对等实现。
- 2026-08-10：实现时进一步澄清第八、九轮组合语义：`ANTHROPIC_BASE_URL` 的值不做 trim 或 blank 归一化，键存在即把原始值显式传给
  SDK；只有原始值非空时才按课程源码移除 `ANTHROPIC_AUTH_TOKEN`。因此显式 blank 不等同于配置缺失，若 SDK 拒绝空 URL，错误应直接
  暴露；这不是回退到官方端点的信号。
- 2026-08-10：使用真实 `.env` 与注入的继承环境完成覆盖验证：`.env` 中的 `ANTHROPIC_BASE_URL` 能覆盖进程同名变量，非空自定义
  Base URL 仍会移除继承的 `ANTHROPIC_AUTH_TOKEN`。因此配置入口收敛为 `.env`，删除重复保存凭据的 `.envrc`、`.envrc.example`
  及 direnv 专用说明，避免双配置源漂移；该清理容易恢复，不单独建立 ADR。
- 2026-08-10：s01 功能对等实现提交为 `fc4324b`。`mvn verify` 通过 40 项自动测试，覆盖 Effective Environment、Bash Tool、Agent
  Loop、REPL 及官网三个端到端示例。双轴审查结果：Standards 无硬违规，保留两处局部输出/Unicode 逻辑与测试 fixture 重复，以免为 s01
  引入浅工具 Module；Spec 报告的 blank Base URL 建议与用户已确认的“保留原始 blank”决策冲突，故记录为非问题。课程票转为
  `ready-for-human`，等待用户 Debug；Debug 确认前不进入 s02。

### Debug 验收与对照复盘

- 2026-08-10：用户完成关键路径 Debug 并能复述运行机制：先取得继承环境，再以 `.env` 同名项覆盖；JLine 读取输入后交给
  `AgentLoop.respond`，输入作为 user message 加入持久 History；每个 `tool_use` 解码出 Bash 命令并顺序执行，输出连同对应 Tool ID
  包装为 `tool_result`，所有结果合并进同一条 user message 回填；未调用工具时返回文本并结束当前 Turn，调用工具时继续请求直至得到最终
  响应。补充边界：Java 不修改 JVM 的 `System.getenv()`，而是构造不可变 Effective Environment，并显式传给 SDK 与 Bash 子进程。
- Python 的 `load_dotenv(override=True)` 通过修改进程环境实现覆盖；Java 受 JVM 环境不可变约束，使用 `EffectiveEnvironment` 快照表达相同的
  项目配置语义。该差异隔离在 composition root，不泄漏进 Agent Loop。
- Python 使用动态字典和 content block；Java 复用官方 SDK 的 `MessageParam`、`ContentBlock`、`ToolUseBlock` 与
  `ToolResultBlockParam`，并在 API 响应 `Message` 与请求 History 之间显式调用 `toParam()`，获得类型安全且保持协议形状。
- Python 依赖终端输入/readline 行为；Java 使用 JLine 明确承载行编辑、EOF、Ctrl-C、ANSI 和 Unicode 宽度行为。两者都维持同一个进程内的
  多 Turn History，不做落盘持久化。
- Python 以 `subprocess.run(shell=True)` 执行 POSIX shell；Java 显式启动 `/bin/sh -c`，并发消费 stdout/stderr，超时后强制终止直接
  shell 进程。Java 额外按 Unicode code point 实现 50,000 字符结果与 200 字符预览截断，以避免 UTF-16 截断代理对。
- 两端均按响应顺序执行一个 assistant Turn 内的全部工具调用，将全部 `tool_result` 放进紧随其后的同一个 user message；错误仍作为普通
  Tool Result 文本回填，不设置 `is_error`，循环不设置最大轮数。这些是课程行为，不提前扩展并发执行、审批、进程树治理或持久化会话。
- Debug、对照复盘与沉淀均已完成，本课程状态转为 `resolved`；后续可以进入下一课的课程基线研究。
