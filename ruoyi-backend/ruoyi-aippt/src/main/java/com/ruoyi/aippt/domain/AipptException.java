package com.ruoyi.aippt.domain;
public class AipptException extends RuntimeException  {
    private final int status;
    private final String errorCode;
    private final Long currentRevision;
    public AipptException(int status,String code,String message)  {
        this(status,code,message,null);
    }
    public AipptException(int status,String code,String message,Long revision)  {
        super(message);
        this.status=status;
        errorCode=code;
        currentRevision=revision;
    }
    public int status()  {
        return status;
    }
    public String errorCode()  {
        return errorCode;
    }
    public Long currentRevision()  {
        return currentRevision;
    }
    public static AipptException invalid(String msg)  {
        return new AipptException(422,"INVALID_INPUT",msg);
    }
    public static AipptException missing()  {
        return new AipptException(404,"NOT_FOUND","资源不存在或无权访问");
    }
    public static AipptException conflict(String msg,long r)  {
        return new AipptException(409,"REVISION_CONFLICT",msg,r);
    }
}
