package com.paicli.mcp.jsonrpc;

public class JsonRpcException extends RuntimeException {
    private final int code;
    private final com.fasterxml.jackson.databind.JsonNode data;

    public JsonRpcException(int code, String message) {
        this(code, message, null);
    }
    public JsonRpcException(int code, String message, com.fasterxml.jackson.databind.JsonNode data) {
        super(message);
        this.code = code;
        this.data = data == null ? null : data.deepCopy();
    }

    public int code() {
        return code;
    }
    public com.fasterxml.jackson.databind.JsonNode data() { return data == null ? null : data.deepCopy(); }
}
