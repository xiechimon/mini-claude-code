package com.xmon.nanoagent.hook;

import com.anthropic.core.JsonValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 按契约的传输面执行一个外部进程 hook
 *
 * <p>事件数据 JSON 走 stdin，判定走 exit code 加 stdout。四条 exit code 规则全部实现：
 *
 * <ul>
 *   <li><b>0</b>——成功。stdout 首个非空白字符是 <code>{</code> 时按 JSON 解析，否则按纯文本。
 *       纯文本只有 {@code UserPromptSubmit}（以及未实现的 {@code UserPromptExpansion} 与
 *       {@code SessionStart}）会送进模型上下文，其余事件的纯文本 stdout 丢弃。
 *   <li><b>2</b>——阻断。JSON 盖不住它：即使 stdout 的判定是放行，仍然阻断。阻断原因取 JSON 里的
 *       原因，没有则取 stderr。
 *   <li><b>其他</b>——不阻断。stdout 的 JSON 若解析成功则单独生效。
 *   <li><b>超时</b>——输出丢弃、不产生判定、<b>不阻断</b>。契约明确警告不要指望一个卡住的 hook 充当闸门。
 * </ul>
 *
 * <p>hook 的 stdout 是不可信输入：它来自用户配置的外部进程。解析失败一律降级为「无判定」而不是抛异常，
 * 与契约的「非阻塞错误，动作继续」一致。降级同时把原因写进 {@link HookOutput.Sync#systemMessage()}——
 * 这是契约自带的用户可见通道，真实 Claude Code 在此处显示 {@code <hook name> hook error}。
 * 静默降级是不允许的：一个解析失败的闸门看起来和一个放行的闸门完全一样。
 */
final class CommandHookRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 契约对 hook 输出字符串的长度上限。 */
    private static final int MAX_OUTPUT_CHARACTERS = 10_000;

    private final Path workingDirectory;
    private final Map<String, String> environment;

    /**
     * 创建外部进程 hook 执行器
     *
     * @param workingDirectory hook 进程的工作目录
     * @param environment hook 进程的环境变量
     */
    CommandHookRunner(Path workingDirectory, Map<String, String> environment) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }

    /**
     * 执行一个外部进程 hook 并按 exit code 契约解读结果
     *
     * @param command hook 的命令与超时
     * @param input 送入 stdin 的事件数据
     * @return 同步判定，无判定时为 {@link HookOutput.Sync#none()}
     * @throws InterruptedException 等待子进程时被中断
     */
    HookOutput.Sync run(HookHandler.Command command, HookInput input) throws InterruptedException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(input, "input");

        Process process = null;
        ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            process = start(command.command());
            writeStdin(process, toJson(input));
            Process running = process;
            // 同时消费两个输出流，避免任一缓冲区写满后阻塞子进程。
            Future<String> stdout = readers.submit(() -> read(running.getInputStream()));
            Future<String> stderr = readers.submit(() -> read(running.getErrorStream()));

            if (!process.waitFor(command.timeout().toNanos(), TimeUnit.NANOSECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                closeStreams(process);
                return warning("hook timed out after " + command.timeout().toSeconds()
                        + "s and produced no verdict: " + command.command());
            }

            return interpret(
                    input,
                    command.command(),
                    process.exitValue(),
                    await(stdout).strip(),
                    await(stderr).strip());
        } catch (IOException failure) {
            return warning("hook failed to run: " + command.command() + " (" + failure.getMessage() + ")");
        } catch (InterruptedException interrupted) {
            if (process != null) {
                destroyAndWaitUninterruptibly(process);
                closeStreams(process);
            }
            throw interrupted;
        } finally {
            readers.shutdownNow();
        }
    }

    /**
     * 按 exit code 与 stdout 解读一次 hook 执行的结果
     *
     * @param input 事件数据，用于取事件名与工具名组成阻断信息的前缀
     * @param command 命令行，出现在阻断信息里
     * @param exitCode 子进程退出码
     * @param stdout 标准输出，已去掉两端空白
     * @param stderr 标准错误，已去掉两端空白
     * @return 同步判定
     */
    static HookOutput.Sync interpret(
            HookInput input, String command, int exitCode, String stdout, String stderr) {
        HookEvent event = input.event();
        Optional<HookOutput.Sync> parsed = stdout.startsWith("{")
                ? parse(event, stdout)
                : Optional.empty();

        if (exitCode == 2) {
            // 阻断原因：JSON 自己给的原因优先，否则用 stderr。JSON 的放行判定盖不住 exit 2。
            // JSON 给了原因时按裸原因回填，与真实 Claude Code 的 JSON 判定路径一致；
            // 只有走 stderr 的路径才加 "<事件>:<工具> hook error: [<命令>]: " 前缀 —— 同样照抄实测行为。
            // 前缀不是装饰：模型据此分辨「谁拦的、哪条命令拦的」，否则一段裸 stderr 与工具自己的错误无从区分。
            Optional<String> declared = parsed
                    .flatMap(HookOutput.Sync::reason)
                    .filter(value -> !value.isBlank());
            if (declared.isPresent()) {
                return HookOutput.Sync.block(truncate(declared.get()));
            }
            String detail = stderr.isBlank() ? "hook exited 2 without a reason" : stderr;
            return HookOutput.Sync.block(truncate(
                    label(input) + " hook error: [" + command + "]: " + detail));
        }

        // 校验通过的 JSON 单独决定结果，退出码被忽略——契约明文。
        if (parsed.isPresent()) {
            return parsed.get();
        }

        if (stdout.startsWith("{")) {
            return warning("hook returned unparsable JSON on stdout (exit " + exitCode + ")");
        }

        if (exitCode == 0) {
            // 纯文本 stdout：只有 UserPromptSubmit 会把它当上下文送进模型，其余事件丢弃。
            if (event == HookEvent.USER_PROMPT_SUBMIT && !stdout.isBlank()) {
                return HookOutput.Sync.of(new HookSpecificOutput.UserPromptSubmit(
                        Optional.of(truncate(stdout)), false));
            }
            return HookOutput.Sync.none();
        }

        // 非 0 非 2 且没有可用 JSON：契约归为非阻塞错误，动作继续。但必须让用户看见——
        // settings.json 里一个打错的脚本路径会以 exit 127 落到这里，静默处理等于闸门没装上却不知道。
        return warning("hook exited " + exitCode + " without a verdict; the action proceeds"
                + (stderr.isBlank() ? "" : ": " + truncate(stderr)));
    }

    /**
     * 组装阻断信息的前缀，格式取自真实 Claude Code 的实测输出
     *
     * <p>工具类事件带工具名（{@code PreToolUse:bash}），其余事件只有事件名。
     *
     * @param input 事件数据
     * @return 前缀
     */
    private static String label(HookInput input) {
        return switch (input) {
            case HookInput.PreToolUse pre -> pre.event().contractValue() + ":" + pre.toolName();
            case HookInput.PostToolUse post -> post.event().contractValue() + ":" + post.toolName();
            default -> input.event().contractValue();
        };
    }

    /**
     * 解析 hook 的 stdout JSON
     *
     * <p>解析失败返回空而不是抛异常：stdout 来自用户配置的外部进程，是不可信输入。
     *
     * @param event 触发的事件，用于校验 {@code hookSpecificOutput.hookEventName}
     * @param stdout 标准输出
     * @return 解析出的判定，解析失败时为空
     */
    private static Optional<HookOutput.Sync> parse(HookEvent event, String stdout) {
        JsonNode root;
        try {
            root = MAPPER.readTree(stdout);
        } catch (IOException malformed) {
            return Optional.empty();
        }
        if (!root.isObject()) {
            return Optional.empty();
        }
        return Optional.of(new HookOutput.Sync(
                optionalBoolean(root, "continue"),
                optionalText(root, "stopReason"),
                optionalText(root, "decision").flatMap(HookOutput.Decision::fromContractValue),
                optionalText(root, "reason"),
                optionalText(root, "systemMessage"),
                parseSpecific(event, root.get("hookSpecificOutput"))));
    }

    /**
     * 解析事件专属判定
     *
     * <p>{@code hookEventName} 缺失或与触发事件不符时返回空：契约要求这个字段存在且匹配，不匹配属于
     * schema 校验失败，按契约降级为「无判定，动作继续」。
     *
     * @param event 触发的事件
     * @param node {@code hookSpecificOutput} 节点，可为 {@code null}
     * @return 事件专属判定，不适用时为空
     */
    private static Optional<HookSpecificOutput> parseSpecific(HookEvent event, JsonNode node) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        if (!optionalText(node, "hookEventName")
                .filter(event.contractValue()::equals)
                .isPresent()) {
            return Optional.empty();
        }
        return switch (event) {
            case PRE_TOOL_USE -> Optional.of(new HookSpecificOutput.PreToolUse(
                    optionalText(node, "permissionDecision")
                            .flatMap(HookPermissionDecision::fromContractValue),
                    optionalText(node, "permissionDecisionReason"),
                    optionalText(node, "additionalContext")));
            case POST_TOOL_USE -> Optional.of(new HookSpecificOutput.PostToolUse(
                    optionalText(node, "additionalContext"),
                    optionalText(node, "updatedToolOutput")));
            case USER_PROMPT_SUBMIT -> Optional.of(new HookSpecificOutput.UserPromptSubmit(
                    optionalText(node, "additionalContext"),
                    optionalBoolean(node, "suppressOriginalPrompt").orElse(false)));
            case STOP -> Optional.of(new HookSpecificOutput.Stop(
                    optionalText(node, "additionalContext")));
            default -> Optional.empty();
        };
    }

    /**
     * 把事件数据序列化成契约字段名的 JSON
     *
     * <p>手工构建而不是给 record 加注解：字段名要与契约逐字一致（{@code snake_case}），而同一批 record
     * 还要服务进程内回调路径，在它们上面挂一套 JSON 注解会让「Java 命名」和「契约命名」互相牵制。
     *
     * @param input 事件数据
     * @return JSON 文本
     */
    static String toJson(HookInput input) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("hook_event_name", input.event().contractValue());
        node.put("session_id", input.base().sessionId());
        node.put("cwd", input.base().cwd());
        node.put("permission_mode", input.base().permissionMode());
        switch (input) {
            case HookInput.PreToolUse pre -> {
                node.put("tool_name", pre.toolName());
                node.set("tool_input", toNode(pre.toolInput()));
                node.put("tool_use_id", pre.toolUseId());
            }
            case HookInput.PostToolUse post -> {
                node.put("tool_name", post.toolName());
                node.set("tool_input", toNode(post.toolInput()));
                node.put("tool_response", post.toolResponse());
                node.put("tool_use_id", post.toolUseId());
            }
            case HookInput.UserPromptSubmit submit -> node.put("prompt", submit.prompt());
            case HookInput.Stop stop -> {
                node.put("stop_hook_active", stop.stopHookActive());
                node.put("last_assistant_message", stop.lastAssistantMessage());
            }
        }
        return node.toString();
    }

    /**
     * 把 SDK 的 JSON 值转成 Jackson 节点
     *
     * @param value SDK JSON 值
     * @return Jackson 节点
     */
    private static JsonNode toNode(JsonValue value) {
        return MAPPER.valueToTree(value.convert(new TypeReference<Object>() {}));
    }

    /**
     * 读取可选文本字段
     *
     * @param node 父节点
     * @param field 字段名
     * @return 字段值，缺失或非文本时为空
     */
    private static Optional<String> optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? Optional.of(value.asText()) : Optional.empty();
    }

    /**
     * 读取可选布尔字段
     *
     * @param node 父节点
     * @param field 字段名
     * @return 字段值，缺失或非布尔时为空
     */
    private static Optional<Boolean> optionalBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? Optional.of(value.asBoolean()) : Optional.empty();
    }

    /**
     * 构造只携带用户可见警告的空判定
     *
     * @param message 警告文本
     * @return 无判定但带警告的同步结果
     */
    private static HookOutput.Sync warning(String message) {
        return new HookOutput.Sync(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(message),
                Optional.empty());
    }

    /**
     * 按契约的 10,000 字符上限截断 hook 输出
     *
     * <p>契约的做法是超限时写入文件并替换成预览加路径。本项目直接截断并标注：不落盘 transcript
     * 的项目里没有存放这种文件的地方（见 {@link HookInput.Base}）。
     *
     * @param value 原始文本
     * @return 截断后的文本
     */
    private static String truncate(String value) {
        if (value.length() <= MAX_OUTPUT_CHARACTERS) {
            return value;
        }
        return value.substring(0, MAX_OUTPUT_CHARACTERS) + "… [truncated]";
    }

    /**
     * 启动 hook 子进程
     *
     * @param command 命令文本
     * @return 已启动的子进程
     * @throws IOException 子进程启动失败
     */
    private Process start(String command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(false);
        builder.environment().clear();
        builder.environment().putAll(environment);
        return builder.start();
    }

    /**
     * 把事件数据写入子进程 stdin 并关闭
     *
     * <p>不关闭 stdin 的话，读 stdin 到 EOF 的 hook 脚本会一直等下去直到超时。
     *
     * <p>写入失败按<b>预期情况</b>处理，不当错误：契约不要求 hook 读 stdin，一个只看
     * {@code $CLAUDE_PROJECT_DIR} 就干活的 hook 完全合法。这类 hook 往往在我们写完之前就退出了，
     * 管道随之断开，写入抛 {@code Stream closed}。把它当失败会让「不读 stdin 的 hook」随机地
     * 变成「hook 执行失败」——取决于两个进程的调度顺序。
     *
     * <p>这不是掩盖异常：真的需要输入却读不到的 hook 会自己失败，它的退出码和 stderr 照常被解读。
     *
     * @param process 子进程
     * @param json 事件数据 JSON
     */
    private static void writeStdin(Process process, String json) {
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException childClosedStdin) {
            // hook 不读 stdin 就提前退出，管道断开。见方法文档。
        }
    }

    /**
     * 读取输入流的全部内容
     *
     * @param stream 输入流
     * @return 以 UTF-8 解码的文本，非法字节替换为 U+FFFD
     * @throws IOException 读取失败
     */
    private static String read(InputStream stream) throws IOException {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * 等待读取任务完成
     *
     * @param output 读取任务
     * @return 读取结果
     * @throws IOException 读取失败
     * @throws InterruptedException 等待被中断
     */
    private static String await(Future<String> output) throws IOException, InterruptedException {
        try {
            return output.get();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Unable to read hook output", cause);
        }
    }

    /**
     * 强制终止进程并恢复中断状态
     *
     * @param process 待终止的进程
     */
    private static void destroyAndWaitUninterruptibly(Process process) {
        process.destroyForcibly();
        boolean interruptedAgain = false;
        while (process.isAlive()) {
            try {
                process.waitFor();
            } catch (InterruptedException ignored) {
                interruptedAgain = true;
            }
        }
        if (interruptedAgain) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 关闭进程输出流以释放读取任务
     *
     * @param process 已结束的进程
     */
    private static void closeStreams(Process process) {
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // 进程已经结束，此处关闭流只为释放阻塞的读取任务。
        }
        try {
            process.getErrorStream().close();
        } catch (IOException ignored) {
            // 进程已经结束，此处关闭流只为释放阻塞的读取任务。
        }
    }
}
