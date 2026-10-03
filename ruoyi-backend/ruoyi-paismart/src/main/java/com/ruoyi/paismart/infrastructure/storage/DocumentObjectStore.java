package com.ruoyi.paismart.infrastructure.storage;

import java.io.InputStream;
import java.util.Map;

/** 仅传输本项目对象；服务端调用，不能对浏览器暴露对象存储密钥。 */
public interface DocumentObjectStore {
    void put(String key,InputStream bytes,long length,String contentType);
    InputStream open(String key);
    long size(String key);
    /** 缺失返回false；存储配置、授权或连接故障仍须抛错，不伪装为缺失。 */
    default boolean exists(String key) {return size(key)>=0;}
    void remove(String key);
    /** 仅核对已配置存储；默认实现不访问网络，也不声称可达。 */
    default Map<String,Object> probe() {
        return Map.of("state","UNSUPPORTED","configured",false,"enabled",false,"probeEnabled",false,
            "tested",false,"scope","PaiSmart配置桶的只读HEAD","reason","当前对象存储没有外部探测入口");
    }
}
