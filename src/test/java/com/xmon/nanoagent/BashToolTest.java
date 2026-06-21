package com.xmon.nanoagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BashToolTest {

    @TempDir
    Path workingDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"})
    void dangerousSubstringIsBlocked(String deniedSubstring) throws InterruptedException {
        BashTool tool = new BashTool(workingDirectory, Map.of(), Duration.ofSeconds(1));

        assertEquals(
                "Error: Dangerous command blocked",
                tool.execute("printf before; " + deniedSubstring + "; printf after"));
    }

    @Test
    void commandUsesPosixShellFixedDirectoryAndEffectiveEnvironment() throws IOException, InterruptedException {
        BashTool tool = new BashTool(
                workingDirectory,
                Map.of("S01_VALUE", "from-effective-environment", "PATH", System.getenv("PATH")),
                Duration.ofSeconds(2));

        String output = tool.execute("printf '%s' \"$S01_VALUE\" | tr a-z A-Z; printf '|%s' \"$PWD\"");

        assertEquals("FROM-EFFECTIVE-ENVIRONMENT|" + workingDirectory.toRealPath(), output);
    }

    @Test
    void stdoutThenStderrAreJoinedAndStripped() throws InterruptedException {
        BashTool tool = new BashTool(workingDirectory, Map.of(), Duration.ofSeconds(2));

        assertEquals("out  err", tool.execute("printf ' out '; printf ' err ' >&2"));
    }

    @Test
    void eachCallUsesAnIndependentChildProcess() throws IOException, InterruptedException {
        BashTool tool = new BashTool(
                workingDirectory,
                Map.of("S01_VALUE", "original"),
                Duration.ofSeconds(2));

        tool.execute("export S01_VALUE=changed; cd /");

        assertEquals(
                "original|" + workingDirectory.toRealPath(),
                tool.execute("printf '%s|%s' \"$S01_VALUE\" \"$PWD\""));
    }

    @Test
    void emptyOutputAndNonZeroExitReturnNoOutput() throws InterruptedException {
        BashTool tool = new BashTool(workingDirectory, Map.of(), Duration.ofSeconds(2));

        assertEquals("(no output)", tool.execute("exit 7"));
    }

    @Test
    void resultIsTruncatedAtFiftyThousandUnicodeCodePoints() throws IOException, InterruptedException {
        Files.writeString(workingDirectory.resolve("large.txt"), "😀".repeat(50_001));
        BashTool tool = new BashTool(workingDirectory, Map.of(), Duration.ofSeconds(2));

        String output = tool.execute("cat large.txt");

        assertEquals(50_000, output.codePointCount(0, output.length()));
        assertTrue(output.endsWith("😀"));
    }

    @Test
    void timeoutKillsTheShellAndReturnsTheConfiguredTimeout() throws InterruptedException {
        BashTool tool = new BashTool(workingDirectory, Map.of(), Duration.ofMillis(100));

        assertEquals("Error: Timeout (0.1s)", tool.execute("exec sleep 5"));
    }

    @Test
    void operatingSystemFailureBecomesAnErrorResult() throws IOException, InterruptedException {
        Path deletedDirectory = Files.createDirectory(workingDirectory.resolve("deleted"));
        Files.delete(deletedDirectory);
        BashTool tool = new BashTool(deletedDirectory, Map.of(), Duration.ofSeconds(2));

        assertTrue(tool.execute("printf unreachable").startsWith("Error: "));
    }

    @Test
    void largeStdoutAndStderrAreConsumedWithoutDeadlock() throws InterruptedException {
        BashTool tool = new BashTool(workingDirectory, System.getenv(), Duration.ofSeconds(2));

        String output = tool.execute(
                "(yes o | head -c 70000) & (yes e | head -c 70000 >&2) & wait");

        assertEquals(50_000, output.length());
    }

    @Test
    void interruptKillsTheDirectShellBeforePropagating() throws Exception {
        BashTool tool = new BashTool(workingDirectory, Map.of(), Duration.ofSeconds(30));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                tool.execute("printf '%s' \"$$\" > shell.pid; exec sleep 30");
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        Path pidFile = workingDirectory.resolve("shell.pid");
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while ((!Files.exists(pidFile) || Files.size(pidFile) == 0) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        long pid = Long.parseLong(Files.readString(pidFile));

        worker.interrupt();
        worker.join(2_000);

        assertFalse(worker.isAlive());
        assertInstanceOf(InterruptedException.class, failure.get());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
}
