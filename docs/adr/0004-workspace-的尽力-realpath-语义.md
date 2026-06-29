# ADR-0004：Workspace 采用尽力 realpath 判定路径包含性

`Workspace` 把模型给出的路径判定为区内或越界，语义固定为三步：`workdir.resolve(raw)`（`raw` 为绝对路径时直接取
`raw`，与 Python `/` 一致）→ **从目标向上找到首个存在的祖先做 `toRealPath()`，再把剩余路径段 `normalize()` 后拼回** →
`startsWith(workdir)`。工作目录本身在 `Main` 捕获时和 `Workspace` 构造时各做一次 `toRealPath()`，保证比较的两侧是同一种表示。

中间那步是本篇存在的理由。课程基准是 Python 的 `Path.resolve()`，它默认 `strict=False`：既解析符号链接，又允许路径不存在。JDK
没有等价物——`toRealPath()` 解析符号链接但**文件不存在即抛 `NoSuchFileException`**，而 `write_file` 必须能写尚不存在的文件并递归创建父目录；`normalize()`
能处理不存在的路径但**纯词法、不解析符号链接**，用它会静默丢掉「指向工作区外的符号链接必须被拒绝」这条边界。按存在性二选一同样不成立：父目录是逃逸符号链接而文件本身不存在时（`etclink/new.txt`），会落进词法分支被放行。

工作目录两次 `toRealPath()` 也不是冗余。Python 的 `Path.cwd()` 来自 POSIX `getcwd()`，本来就是解析后的路径；Java 的
`Path.of("").toAbsolutePath()` 与测试传入的临时目录都可能仍是符号链接路径（macOS 的 `@TempDir` 位于 `/var/folders/…`，而 `/var`
是符号链接），此时目标解析到 `/private/var/…` 而根未解析，`startsWith` 会把**所有**路径判为越界。

这段逻辑读起来像可以简化成一次 `normalize()`，而简化后的失效是静默的：功能全部正常，只有符号链接逃逸这一条防线消失，且除测试外没有任何征兆。修改
`Workspace` 的解析步骤前须先确认符号链接逃逸用例仍然失败。
