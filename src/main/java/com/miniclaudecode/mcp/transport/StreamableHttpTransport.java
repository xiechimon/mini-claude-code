package com.miniclaudecode.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaudecode.mcp.protocol.McpInitializeRequest;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 通过 MCP Streamable HTTP 端点发送 JSON-RPC 消息
 * 同时接受普通 JSON 响应与 SSE 事件流，并维护服务端会话标识
 */
public class StreamableHttpTransport implements McpTransport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build();
    private final String url;
    private final Map<String, String> headers;
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private volatile String sessionId;

    public StreamableHttpTransport(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    private static List<JsonNode> parseSse(String raw) throws IOException {
        List<JsonNode> messages = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : raw.split("\\R")) {
            if (line.isBlank()) {
                if (!data.isEmpty()) {
                    messages.add(MAPPER.readTree(data.toString()));
                    data.setLength(0);
                }
                continue;
            }
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring("data:".length()).trim());
            }
        }
        if (!data.isEmpty()) {
            messages.add(MAPPER.readTree(data.toString()));
        }
        return messages;
    }

    @Override
    public void send(JsonNode message) throws IOException {
        RequestBody body = RequestBody.create(MAPPER.writeValueAsString(message), JSON);
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", McpInitializeRequest.PROTOCOL_VERSION)
                .post(body);
        headers.forEach(builder::header);
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            String newSession = response.header("Mcp-Session-Id");
            if (newSession != null && !newSession.isBlank()) {
                sessionId = newSession;
            }
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                return;
            }
            String contentType = response.header("Content-Type", "");
            String raw = responseBody.string();
            // notification 路径下 server 可以返回 202 + 空 body 或 200 + 空 body
            // 这里 swallow 空响应，避免 Jackson 对空字符串抛 MismatchedInputException
            if (raw == null || raw.isBlank()) {
                return;
            }
            List<JsonNode> messages = contentType.contains("text/event-stream")
                    ? parseSse(raw)
                    : List.of(MAPPER.readTree(raw));
            for (JsonNode node : messages) {
                for (Consumer<JsonNode> listener : listeners) {
                    listener.accept(node);
                }
            }
        }
    }

    @Override
    public void onReceive(Consumer<JsonNode> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public String transportName() {
        return "http";
    }

    @Override
    public void close() {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("MCP-Protocol-Version", McpInitializeRequest.PROTOCOL_VERSION)
                .header("Mcp-Session-Id", sessionId)
                .delete();
        headers.forEach(builder::header);
        // close 采用 best-effort，server 或网络异常不能阻塞退出
        // 主 client 的 callTimeout 是 60s，这里用 5s 短超时单独发请求
        OkHttpClient closeClient = client.newBuilder()
                .callTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .connectTimeout(2, TimeUnit.SECONDS)
                .build();
        try (Response ignored = closeClient.newCall(builder.build()).execute()) {
        } catch (IOException ignored) {
            // 退出流程不等待关闭失败
        }
    }
}
