package com.miniclaudecode.pty;

import com.pty4j.PtyProcess;
import com.pty4j.WinSize;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * pty4j 封装的 CLI 会话
 * <p>
 * 启动 com.miniclaudecode.cli.Main 作为伪终端子进程，提供 send/sendRaw/expect 操作
 * <p>
 * classpath 取自测试 JVM 的 java.class.path（Maven Surefire 已配置完整依赖）
 */
public final class PtyCliSession implements AutoCloseable {

    private final PtyProcess proc;
    private final OutputStream writer;
    private final BufferedReader reader;
    private final StringBuilder buffer = new StringBuilder();
    private final Object bufferLock = new Object();
    private final Thread readerThread;

    private PtyCliSession(PtyProcess proc, OutputStream writer, BufferedReader reader) {
        this.proc = proc;
        this.writer = writer;
        this.reader = reader;
        this.readerThread = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (bufferLock) {
                        buffer.append(line).append('\n');
                        bufferLock.notifyAll();
                    }
                }
            } catch (IOException e) {
                // PTY 关闭时触发，忽略
            }
        }, "pty-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    /**
     * 启动 CLI 会话，使用 java.class.path 作为依赖来源
     *
     * <p>自动隔离：覆盖 user.home 到临时目录（避免真实 ~/.mini-claude-code 配置干扰），
     * 清空其他 provider 的 API key（只保留调用方传入的 FREELLMAPI_*）
     */
    public static PtyCliSession launch(Map<String, String> envOverrides, String workDir) throws IOException {
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + "/bin/java";
        String classpath = System.getProperty("java.class.path");        java.io.File isolatedHome = java.io.File.createTempFile("pty-test-home", "");
        // createTempFile 创建的是文件，删除后改成目录
        if (!isolatedHome.delete() || !isolatedHome.mkdirs()) {
            throw new IOException("无法创建隔离 home 目录: " + isolatedHome);
        }
        isolatedHome.deleteOnExit();

        // 预创 mcp.json 阻止 CLI 自动启用 chrome-devtools（npx 启动阻塞测试）
        java.io.File mcpConfigDir = new java.io.File(isolatedHome, ".mini-claude-code");
        mcpConfigDir.mkdirs();
        java.io.File mcpConfig = new java.io.File(mcpConfigDir, "mcp.json");
        try (java.io.FileWriter w = new java.io.FileWriter(mcpConfig)) {
            w.write("{\"mcpServers\":{}}");
        }
        mcpConfig.deleteOnExit();

        // 预创 .env 让所有 provider key 为空，强制走 stub
        java.io.File dotEnv = new java.io.File(isolatedHome, ".env");
        try (java.io.FileWriter w = new java.io.FileWriter(dotEnv)) {
            w.write("# pty-test isolated env\n");
            w.write("GLM_API_KEY=\n");
            w.write("DEEPSEEK_API_KEY=\n");
            w.write("KIMI_API_KEY=\n");
            w.write("MOONSHOT_API_KEY=\n");
            w.write("FREELLMAPI_API_KEY=stub-key\n");
            w.write("FREELLMAPI_BASE_URL=http://stub.invalid/v1\n");
            w.write("FREELLMAPI_MODEL=auto\n");
        }
        dotEnv.deleteOnExit();

        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.put("LANG", "en_US.UTF-8");
        env.put("HOME", isolatedHome.getAbsolutePath());
        env.putAll(envOverrides);
        // 显式屏蔽所有真实 provider key，让 factory 走 fallback 或调用方注入
        env.put("GLM_API_KEY", "");
        env.put("DEEPSEEK_API_KEY", "");
        env.put("KIMI_API_KEY", "");
        env.put("MOONSHOT_API_KEY", "");
        env.put("FREELLMAPI_API_KEY", env.getOrDefault("FREELLMAPI_API_KEY", "stub-key"));

        String[] cmd = {
                javaBin,
                "-Duser.home=" + isolatedHome.getAbsolutePath(),
                "-cp", classpath,
                "com.miniclaudecode.cli.Main"
        };

        PtyProcess proc = PtyProcess.exec(cmd, env, workDir);
        OutputStream out = proc.getOutputStream();
        BufferedReader in = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));

        return new PtyCliSession(proc, out, in);
    }

    /**
     * 发送文本 + Enter
     */
    public void send(String text) throws IOException {
        writer.write(text.getBytes(StandardCharsets.UTF_8));
        writer.write('\n');
        writer.flush();
    }

    /**
     * 发送原始字节（不附加 \n）
     */
    public void sendRaw(byte... bytes) throws IOException {
        writer.write(bytes);
        writer.flush();
    }

    /**
     * 发送 Ctrl 组合键（Ctrl+O = 0x0F）
     */
    public void sendCtrl(char letter) throws IOException {
        sendRaw((byte) (letter & 0x1F));
    }

    /**
     * 等待直到输出匹配正则，超时抛异常。
     * 匹配前去 ANSI，避免颜色码干扰正则
     */
    public String expect(Pattern pattern, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        synchronized (bufferLock) {
            while (true) {
                String text = stripAnsi(buffer.toString());
                java.util.regex.Matcher m = pattern.matcher(text);
                if (m.find()) {
                    return m.group();
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new AssertionError("expect 超时: " + pattern.pattern() + "\n实际输出:\n" + text);
                }
                try {
                    bufferLock.wait(Math.min(remaining, 200));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
        }
    }

    /**
     * 等待 JLine 准备好（banner + prompt * ）出现
     */
    public String awaitReady(Duration timeout) {
        // JLine 的 prompt >  不进 PTY 输出 buffer；改等 banner 完成 + 最后一条启动日志
        // 等待 "Tips for getting started" 后面跟任何收尾信息（"未配置 chrome-devtools" 或 "默认启用"）
        Pattern pattern = Pattern.compile(
                "(?s)Tips for getting started.*(?:chrome-devtools|启动完成|ready|Ready)");
        return expect(pattern, timeout);
    }

    /**
     * 当前累积输出
     */
    public String currentOutput() {
        synchronized (bufferLock) {
            return buffer.toString();
        }
    }

    /**
     * 去除 ANSI 转义序列（颜色、光标控制）
     */
    public static String stripAnsi(String s) {
        // CSI 序列：ESC [ ... 字母
        return s.replaceAll("\\x1B\\[[0-?]*[ -/]*[@-~]", "");
    }

    public void resize(int cols, int rows) {
        proc.setWinSize(new WinSize(cols, rows));
    }

    public int exitCode() {
        try {
            return proc.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException ignored) {
        }
        proc.destroyForcibly();
    }
}