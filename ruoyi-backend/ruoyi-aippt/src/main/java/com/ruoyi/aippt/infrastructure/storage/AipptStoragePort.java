package com.ruoyi.aippt.infrastructure.storage;
import java.io.IOException;
public interface AipptStoragePort  {
    void put(String key,byte[] data,String mime)throws IOException;
    byte[] get(String key)throws IOException;
    void delete(String key)throws IOException;
}
