package com.ruoyi.paiflow.worker.service;

public class WorkerException extends RuntimeException {
    private final String code;

    public WorkerException(String code, String message) {
        super(message);
        this.code = code;
    }

    public WorkerException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
