package com.miniclaudecode.pty;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAI-compatible SSE 桩服务
 * 监听 /v1/chat/completions，按请求序号返回脚本化的 SSE 响应
 *
 * <p>每次 POST 请求独立计数；超出脚本 turn 数则复用最后一轮
 */
public final class SseStubServer implements AutoCloseable {

    private final HttpServer server;
    private final int port;
    private final StubScript script;
    private final AtomicInteger requestIndex = new AtomicInteger(0);

    public SseStubServer(StubScript script) throws IOException {
        this.script = script;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/v1/chat/completions", new ChatHandler());
        this.server.setExecutor(null);
        this.server.start();
        this.port = server.getAddress().getPort();
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port + "/v1";
    }

    public int requestCount() {
        return requestIndex.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private class ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 读取请求体（消费即可）
            byte[] body = exchange.getRequestBody().readAllBytes();

            int idx = requestIndex.getAndIncrement();
            StubScript.Turn turn = script.turn(idx);

            String response = turn.toSseText();
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, responseBytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }
}