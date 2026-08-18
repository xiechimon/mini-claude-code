package com.miniclaudecode.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * JSON-RPC 消息与具体 MCP 传输方式之间的边界
 *
 * <p>{@link #send(JsonNode)} 可同步分发响应，也可由后台线程稍后分发
 * listener 的执行线程由实现决定，多次注册为累加关系
 */
public interface McpTransport extends AutoCloseable {

    /**
     * 发送一条完整 JSON-RPC 消息
     *
     * @throws IOException 消息无法序列化、写入或完成同步传输
     */
    void send(JsonNode message) throws IOException;

    /**
     * 注册接收 listener，{@code null} 会被忽略
     */
    void onReceive(Consumer<JsonNode> listener);

    default List<String> stderrLines() {
        return List.of();
    }

    default Long processId() {
        return null;
    }

    default String transportName() {
        return "unknown";
    }

    /**
     * 关闭底层资源，远端关闭可为 best-effort，本地资源必须释放
     */
    @Override
    void close();
}
