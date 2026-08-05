package com.miniclaudecode.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * 把远端 MCP {@code inputSchema} 降级为 LLM tool 可接受的 JSON Schema 子集
 *
 * <p>转换会移除 {@code $schema/$id/$ref}，把 {@code anyOf/oneOf} 改为文字说明并截断长描述
 * 输入节点保持不变，返回值始终是带 {@code type=object} 和 {@code properties} 的独立对象
 */
public final class McpSchemaSanitizer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_DESCRIPTION_CHARS = 1000;

    private McpSchemaSanitizer() {
    }

    /** 清理 schema 副本，空值或非对象输入降级为 object schema */
    public static JsonNode sanitize(JsonNode schema) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            ObjectNode fallback = MAPPER.createObjectNode();
            fallback.put("type", "object");
            fallback.putObject("properties");
            return fallback;
        }
        JsonNode copy = schema.deepCopy();
        JsonNode cleaned = clean(copy);
        if (!cleaned.isObject()) {
            ObjectNode fallback = MAPPER.createObjectNode();
            fallback.put("type", "object");
            fallback.set("description", cleaned);
            return fallback;
        }
        ObjectNode obj = (ObjectNode) cleaned;
        if (!obj.has("type")) {
            obj.put("type", "object");
        }
        if (!obj.has("properties")) {
            obj.putObject("properties");
        }
        return obj;
    }

    private static JsonNode clean(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.remove("$schema");
            object.remove("$id");
            object.remove("$ref");

            StringBuilder alternatives = new StringBuilder();
            for (String keyword : new String[]{"anyOf", "oneOf"}) {
                JsonNode union = object.remove(keyword);
                if (union != null && union.isArray()) {
                    if (!alternatives.isEmpty()) {
                        alternatives.append("; ");
                    }
                    alternatives.append(keyword).append(" options: ");
                    for (int i = 0; i < union.size(); i++) {
                        if (i > 0) alternatives.append(", ");
                        JsonNode option = union.get(i);
                        alternatives.append(option.path("type").asText(option.toString()));
                    }
                }
            }
            if (!alternatives.isEmpty()) {
                object.put("type", "object");
                String existing = object.path("description").asText("");
                object.put("description", truncateDescription(
                        existing.isBlank() ? alternatives.toString() : existing + " (" + alternatives + ")"));
            }

            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode child = field.getValue();
                if ("description".equals(field.getKey()) && child.isTextual()) {
                    object.put("description", truncateDescription(child.asText()));
                } else {
                    clean(child);
                }
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (JsonNode child : array) {
                clean(child);
            }
        }
        return node;
    }

    private static String truncateDescription(String description) {
        if (description == null || description.length() <= MAX_DESCRIPTION_CHARS) {
            return description;
        }
        return description.substring(0, MAX_DESCRIPTION_CHARS) + "...";
    }
}
