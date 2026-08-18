package com.miniclaudecode.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniclaudecode.mcp.transport.McpTransport;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 关联 JSON-RPC 请求、响应和通知
 *
 * <p>pending 请求可由传输线程并发完成，超时后先移除再结束等待，因此迟到响应会被丢弃
 * 调用线程额外保留一秒缓冲，避免超时调度延迟造成永久阻塞
 */
public class JsonRpcClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;

    private final McpTransport transport;
    private final AtomicLong ids = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "mini-claude-code-mcp-jsonrpc-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Consumer<JsonNode>> notificationListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public JsonRpcClient(McpTransport transport) {
        this.transport = transport;
        this.transport.onReceive(this::handleMessage);
    }

    public JsonNode request(String method, JsonNode params) throws IOException {
        return request(method, params, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 发起请求并阻塞等待对应 ID 的响应
     *
     * @param params         可为 {@code null}，此时请求不包含 {@code params}
     * @param timeoutSeconds pending 请求的超时秒数
     * @throws JsonRpcException server 返回 JSON-RPC error
     * @throws IOException      消息发送失败、等待中断或请求超时
     */
    public JsonNode request(String method, JsonNode params, long timeoutSeconds) throws IOException {
        long id = ids.getAndIncrement();
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        scheduler.schedule(() -> {
            CompletableFuture<JsonNode> removed = pending.remove(id);
            if (removed != null) {
                removed.completeExceptionally(new TimeoutException("JSON-RPC request timed out: " + method));
            }
        }, timeoutSeconds, TimeUnit.SECONDS);

        try {
            transport.send(request);
            return future.get(timeoutSeconds + 1, TimeUnit.SECONDS);
        } catch (JsonRpcException e) {
            throw e;
        } catch (Exception e) {
            pending.remove(id);
            if (e.getCause() instanceof JsonRpcException jsonRpcException) {
                throw jsonRpcException;
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    public void sendNotification(String method, JsonNode params) throws IOException {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.set("params", params);
        }
        transport.send(notification);
    }

    /**
     * 累加注册 server notification listener，回调线程与 transport 接收线程一致
     */
    public void onNotification(Consumer<JsonNode> listener) {
        if (listener != null) {
            notificationListeners.add(listener);
        }
    }

    private void handleMessage(JsonNode message) {
        JsonNode idNode = message.get("id");
        if (idNode == null || idNode.isNull()) {
            for (Consumer<JsonNode> listener : notificationListeners) {
                listener.accept(message);
            }
            return;
        }
        long id = idNode.asLong();
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future == null) {
            return;
        }
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            future.completeExceptionally(new JsonRpcException(
                    error.path("code").asInt(-32603),
                    error.path("message").asText("JSON-RPC error")));
            return;
        }
        future.complete(message.get("result"));
    }

    @Override
    public void close() {
        // pending 调用仍由各自的 future.get 超时退出，关闭过程不额外等待
        scheduler.shutdownNow();
        transport.close();
    }
}
