package com.miniclaudecode.mcp.jsonrpc;

/**
 * 携带 JSON-RPC error code 和 data 的协议异常
 */
public class JsonRpcException extends RuntimeException {
    private final int code;

    public JsonRpcException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
