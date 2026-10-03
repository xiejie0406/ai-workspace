package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.RunRecord;

/** 运行级不可变JSON编码标记；旧NULL记录与V1同义，不跟随DSL编译器升级。 */
public final class RunJsonCodec {
    public static final String V1="paiflow.run-json.v1",V2="paiflow.run-json.v2";
    private RunJsonCodec() {}
    public static String require(RunRecord run) { return require(run.getRunJsonCodec()); }
    public static String require(String value) {
        if(value==null || V1.equals(value))return V1;
        if(V2.equals(value))return V2;
        throw new ServiceException("运行JSON编码版本不受支持",409);
    }
    public static String canonical(RunRecord run,JSONObject value) {
        return V2.equals(require(run))?RunJsonBoundary.canonicalFull(value):RunJsonBoundary.canonical(value);
    }
    public static String hash(RunRecord run,JSONObject value) {
        return V2.equals(require(run))?RunJsonBoundary.hashFull(value):RunJsonBoundary.hash(value);
    }
}
