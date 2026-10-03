package com.ruoyi.aipicture.domain;

public final class AiPictureException extends RuntimeException {
    private final int status;
    private final String errorCode;
    private final boolean retryable;
    private final Long currentVersion;
    public AiPictureException(int status,String code,String message) { this(status,code,message,false,null); }
    public AiPictureException(int status,String code,String message,boolean retryable,Long version) {
        super(message); this.status=status; this.errorCode=code; this.retryable=retryable; this.currentVersion=version;
    }
    public int status() { return status; }
    public String errorCode() { return errorCode; }
    public boolean retryable() { return retryable; }
    public Long currentVersion() { return currentVersion; }
    public static AiPictureException invalid(String message) { return new AiPictureException(422,"INVALID_INPUT",message); }
    public static AiPictureException missing() { return new AiPictureException(404,"NOT_FOUND","资源不存在或无权访问"); }
    public static AiPictureException conflict(long version) { return new AiPictureException(409,"VERSION_CONFLICT","画布已更新，请刷新后重试",true,version); }
}
