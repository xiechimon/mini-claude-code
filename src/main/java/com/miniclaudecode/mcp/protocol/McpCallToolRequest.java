package com.miniclaudecode.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 将工具名和参数编码为 MCP tools/call 请求参数
 */
public final class McpCallToolRequest {
    private McpCallToolRequest() {
    }

    public static ObjectNode toJson(String name, JsonNode arguments) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("name", name);
        root.set("arguments", arguments == null ? JsonNodeFactory.instance.objectNode() : arguments);
        return root;
    }
}
