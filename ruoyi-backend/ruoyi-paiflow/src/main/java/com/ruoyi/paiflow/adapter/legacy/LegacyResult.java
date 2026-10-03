package com.ruoyi.paiflow.adapter.legacy;

/** 原React http消费者使用code=0信封，标准若依API继续用AjaxResult。 */
public record LegacyResult<T>(int code, String message, String desc, T data) {
    public static <T> LegacyResult<T> success(T data) { return new LegacyResult<>(0, "成功", "成功", data); }
}
