package com.miniclaudecode.pty;

import com.pty4j.PtyProcess;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * pty4j 可用性冒烟测试
 * 确认 macOS 上 PtyProcess.exec() 能起子进程、读写工作
 */
class Pty4jSmokeTest {

    @Test
    void ptyEchoSmoke() throws Exception {
        PtyProcess proc = PtyProcess.exec(
                new String[]{"/bin/bash", "-c", "echo hello-pty; echo world-from-bash"},
                Map.of("TERM", "xterm-256color"),
                System.getProperty("user.dir")
        );

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));

        String line1 = reader.readLine();
        assertNotNull(line1, "pty 应产生输出");
        assertTrue(line1.contains("hello-pty"), "应包含 hello-pty，实际: " + line1);

        String line2 = reader.readLine();
        assertNotNull(line2, "pty 应产生第二行输出");
        assertTrue(line2.contains("world-from-bash"), "应包含 world-from-bash，实际: " + line2);

        reader.close();
        int exitCode = proc.waitFor();
        assertEquals(0, exitCode, "bash 进程应正常退出");
    }

    @Test
    void ptyExitCode() throws Exception {
        PtyProcess proc = PtyProcess.exec(
                new String[]{"/bin/bash", "-c", "exit 42"},
                Map.of("TERM", "xterm-256color"),
                System.getProperty("user.dir")
        );

        int exitCode = proc.waitFor();
        assertEquals(42, exitCode, "应返回 exit 42");
    }

    @Test
    void ptyWritesToProcess() throws Exception {
        PtyProcess proc = PtyProcess.exec(
                new String[]{"/bin/bash", "-c", "cat"},
                Map.of("TERM", "xterm-256color"),
                System.getProperty("user.dir")
        );

        proc.getOutputStream().write("hello-pty-write\n".getBytes(StandardCharsets.UTF_8));
        proc.getOutputStream().flush();

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));

        // cat 在 PTY 中会回显输入，所以读到的内容可能包含 echo 或直接输出
        String line = reader.readLine();
        assertNotNull(line, "pty 应产生输出");
        assertTrue(line.contains("hello-pty-write"), "应包含 hello-pty-write，实际: " + line);

        reader.close();
        proc.destroyForcibly();
    }
}