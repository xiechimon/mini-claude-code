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

    static BashTool production(Path workingDirectory, Map<String, String> environment) {
        return new BashTool(workingDirectory, environment, PRODUCTION_TIMEOUT);
    }

    BashTool(Path workingDirectory, Map<String, String> environment, Duration timeout) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory);
        this.environment = Map.copyOf(environment);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    String execute(String command) throws InterruptedException {
        if (DENIED_SUBSTRINGS.stream().anyMatch(command::contains)) {
            return "Error: Dangerous command blocked";
        }

        Process process = null;
        ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            process = start(command);
            Process runningProcess = process;
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

    private Process start(String command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", command)
                .directory(workingDirectory.toFile())
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectErrorStream(false);
        builder.environment().clear();
        builder.environment().putAll(environment);
        return builder.start();
    }

    private static String read(InputStream stream) throws IOException {
        return new String(stream.readAllBytes(), Charset.defaultCharset());
    }

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

    private static String truncateByCodePoint(String value, int maximumCodePoints) {
        if (value.codePointCount(0, value.length()) <= maximumCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }

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

    private static void closeStreams(Process process) {
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // The process has already ended; closing only releases blocked reader tasks.
        }
        try {
            process.getErrorStream().close();
        } catch (IOException ignored) {
            // The process has already ended; closing only releases blocked reader tasks.
        }
    }
}
