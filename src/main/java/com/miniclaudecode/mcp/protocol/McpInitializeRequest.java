package com.miniclaudecode.mcp.protocol;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 构造客户端能力与实现信息组成的 MCP initialize 参数
 */
public final class McpInitializeRequest {
    public static final String PROTOCOL_VERSION = "2025-03-26";

    private McpInitializeRequest() {
    }

    public static ObjectNode toJson() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = root.putObject("capabilities");
        capabilities.putObject("tools");
        ObjectNode clientInfo = root.putObject("clientInfo");
        clientInfo.put("name", "mini-claude-code");
        clientInfo.put("version", "11.0.0");
        return root;
    }
}
