package com.miniclaudecode.pty;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 组合 PtyCliSession + SseStubServer，提供高级测试便利方法
 */
public final class PtyTestHarness implements AutoCloseable {

    public static final Duration CLI_STARTUP_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration LLM_RESPONSE_TIMEOUT = Duration.ofSeconds(45);

    private final SseStubServer stub;
    private final PtyCliSession session;
    private final String workDir;

    private PtyTestHarness(SseStubServer stub, PtyCliSession session, String workDir) {
        this.stub = stub;
        this.session = session;
        this.workDir = workDir;
    }

    public static PtyTestHarness start(StubScript script, String renderer) throws IOException {
        return start(script, renderer, Map.of());
    }

    public static PtyTestHarness start(StubScript script, String renderer, Map<String, String> extraEnv) throws IOException {
        String realProject = System.getProperty("user.dir");

        // 创建 temp workDir，里面放空 .env 覆盖项目根的 .env，
        // 同时 symlink 关键项目文件（README.md、AGENTS.md 等）供工具调用读取
        java.io.File tmpWork = java.io.File.createTempFile("pty-workdir", "");
        if (!tmpWork.delete() || !tmpWork.mkdirs()) {
            throw new IOException("无法创建临时 workDir: " + tmpWork);
        }
        tmpWork.deleteOnExit();
        try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(tmpWork, ".env"))) {
            w.write("# pty-test isolated env\n");
            w.write("GLM_API_KEY=\nDEEPSEEK_API_KEY=\nKIMI_API_KEY=\nMOONSHOT_API_KEY=\n");
            w.write("FREELLMAPI_API_KEY=stub-key\nFREELLMAPI_MODEL=auto\n");
        }
        // symlink 关键文件
        for (String name : new String[]{"README.md", "AGENTS.md", "ROADMAP.md", "pom.xml", "docs", "src"}) {
            java.io.File target = new java.io.File(realProject, name);
            if (target.exists()) {
                java.nio.file.Files.createSymbolicLink(
                        new java.io.File(tmpWork, name).toPath(),
                        target.toPath());
            }
        }

        SseStubServer stub = new SseStubServer(script);

        Map<String, String> env = new HashMap<>();
        env.put("MINI_CLAUDE_CODE_RENDERER", renderer);
        env.put("FREELLMAPI_BASE_URL", stub.baseUrl());
        env.putAll(extraEnv);

        PtyCliSession session = PtyCliSession.launch(env, tmpWork.getAbsolutePath());
        session.awaitReady(CLI_STARTUP_TIMEOUT);

        return new PtyTestHarness(stub, session, tmpWork.getAbsolutePath());
    }

    public PtyCliSession session() {
        return session;
    }

    public SseStubServer stub() {
        return stub;
    }

    public String workDir() {
        return workDir;
    }

    @Override
    public void close() {
        try {
            session.close();
        } finally {
            stub.close();
        }
    }
}