package com.paicli.wechat;

import java.util.concurrent.Future;

/** 微信只承载文字和受控命令；生产实现把任务发往中心。 */
public interface WechatTaskSession extends AutoCloseable {
    boolean isRunning(); boolean hasCompletedRun(); Future<String> submit(String prompt);
    default Future<String> submitMessage(WechatMessage message) { return submit(message.text()); }
    String awaitCurrent(); void cancel(); void clear(); String compact();
    default String command(String name, String arguments) { return null; }
    default boolean wasLastCommandAccepted() { return true; }
    @Override void close();
}
