package com.xmon.nanoagent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 执行 Bash 命令
 */
final class BashTool {

    private static final List<String> DENIED_SUBSTRINGS = List.of(
            "rm -rf /",
            "sudo",
            "shutdown",
            "reboot",
            "> /dev/");
    private static final Duration PRODUCTION_TIMEOUT = Duration.ofSeconds(120);
    private static final int MAX_RESULT_CODE_POINTS = 50_000;

    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final Duration timeout;

    /**
     * 创建生产环境使用的命令工具
     *
     * @param workingDirectory 命令工作目录
     * @param environment 子进程环境变量
     * @return 命令工具
     */
    static BashTool production(Path workingDirectory, Map<String, String> environment) {
        return new BashTool(workingDirectory, environment, PRODUCTION_TIMEOUT);
    }

    /**
     * 创建指定超时时间的命令工具
     *
     * @param workingDirectory 命令工作目录
     * @param environment 子进程环境变量
     * @param timeout 命令超时时间
     * @throws IllegalArgumentException 超时时间不是正数
     */
    BashTool(Path workingDirectory, Map<String, String> environment, Duration timeout) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory);
        this.environment = Map.copyOf(environment);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    /**
     * 执行命令并收集标准输出与错误输出
     *
     * @param command 命令文本
     * @return 命令输出或错误信息
     * @throws InterruptedException 当前线程被中断
     */
    String execute(String command) throws InterruptedException {
        if (DENIED_SUBSTRINGS.stream().anyMatch(command::contains)) {
            return "Error: Dangerous command blocked";
        }

        Process process = null;
        ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            process = start(command);
            Process runningProcess = process;
            // 同时消费两个输出流，避免任一缓冲区写满后阻塞子进程。
            Future<String> stdout = readers.submit(() -> read(runningProcess.getInputStream()));
            Future<String> stderr = readers.submit(() -> read(runningProcess.getErrorStream()));

            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                closeStreams(process);
                return "Error: Timeout (" + timeoutLabel() + ")";
            }

            String output = (await(stdout) + await(stderr)).strip();
            if (output.isEmpty()) {
                return "(no output)";
            }
            return truncateByCodePoint(output, MAX_RESULT_CODE_POINTS);
        } catch (IOException exception) {
            return "Error: " + exception.getMessage();
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
     * 启动命令子进程
     *
     * @param command 命令文本
     * @return 已启动的子进程
     * @throws IOException 子进程启动失败
     */
    private Process start(String command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", command)
                .directory(workingDirectory.toFile())
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectErrorStream(false);
        builder.environment().clear();
        builder.environment().putAll(environment);
        return builder.start();
    }

    /**
     * 读取输入流的全部内容
     *
     * @param stream 输入流
     * @return 使用系统默认字符集解码的文本
     * @throws IOException 读取失败
     */
    private static String read(InputStream stream) throws IOException {
        return new String(stream.readAllBytes(), Charset.defaultCharset());
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
            throw new IllegalStateException("Unable to read shell output", cause);
        }
    }

    /**
     * 按 Unicode 码点截断文本
     *
     * @param value 原始文本
     * @param maximumCodePoints 最大码点数
     * @return 截断后的文本
     */
    private static String truncateByCodePoint(String value, int maximumCodePoints) {
        if (value.codePointCount(0, value.length()) <= maximumCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }

    /**
     * 格式化超时时间
     *
     * @return 秒制超时文本
     */
    private String timeoutLabel() {
        long milliseconds = timeout.toMillis();
        if (milliseconds % 1_000 == 0) {
            return milliseconds / 1_000 + "s";
        }
        String fractionalSeconds = String.format(java.util.Locale.ROOT, "%.3f", milliseconds / 1_000.0)
                .replaceFirst("0+$", "")
                .replaceFirst("\\.$", "");
        return fractionalSeconds + "s";
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
