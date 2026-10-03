package com.ruoyi.paiflow.service;

/** 同一协议的字节预算；原节点生命周期同时携带输入和输出，不能小于已接收的输入。 */
public final class RunProtocolLimits {
    private RunProtocolLimits() {}
    public static final int INPUT_BYTES=1024*1024;
    public static final int ASSIGNMENT_BYTES=1800*1024;
    public static final int EVENT_BYTES=4*1024*1024;
    public static final int RESULT_BYTES=2*1024*1024;
    public static final int CONTROL_BYTES=16*1024;
}
