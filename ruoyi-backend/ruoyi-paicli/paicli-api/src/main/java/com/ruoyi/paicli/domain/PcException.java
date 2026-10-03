package com.ruoyi.paicli.domain;

/** 对外稳定错误；不携带原始异常、密钥或数据库信息。 */
public class PcException extends RuntimeException {
    private final int status;
    private final String code;
    public PcException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    public int status() { return status; } public String code() { return code; }
    public static PcException conflict(String reason) { return new PcException(409,"PC_VERSION_CONFLICT",reason); }
    public static PcException deny(String reason) { return new PcException(403,"PC_ACCESS_DENIED",reason); }
    public static PcException invalid(String reason) { return new PcException(400,"PC_INVALID_INPUT",reason); }
}
