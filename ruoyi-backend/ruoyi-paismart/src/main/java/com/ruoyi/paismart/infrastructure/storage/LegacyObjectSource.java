package com.ruoyi.paismart.infrastructure.storage;

import java.io.InputStream;

/** 已批准旧PaiSmart存储的只读来源；不提供写入或删除入口。 */
public interface LegacyObjectSource {
    InputStream open(String key);
}
