package com.ruoyi.paiflow.mapper;

import com.ruoyi.paiflow.domain.DataRecord;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface DataMapper {
    DataRecord database(@Param("id") long id);
    DataRecord lockDatabase(@Param("id") long id);
    List<DataRecord> databases(@Param("space") long space);
    int insertDatabase(DataRecord row);
    int updateDatabase(DataRecord row);
    DataRecord table(@Param("id") long id);
    DataRecord lockTable(@Param("id") long id);
    List<DataRecord> tables(@Param("db") long db);
    int insertTable(DataRecord row);
    int updateTable(DataRecord row);
    List<Map<String,Object>> rows(@Param("table") long table,@Param("env") int env,@Param("limit") int limit);
    long countRows(@Param("table") long table,@Param("env") int env);
    int insertRow(Map<String,Object> row);
    int updateRow(@Param("table") long table,@Param("env") int env,@Param("id") long id,@Param("json") String json);
    int deleteRow(@Param("table") long table,@Param("env") int env,@Param("id") long id);
    int referencedDatabase(@Param("id") long id);
    int referencedTable(@Param("id") long id);
    Map<String,Object> request(@Param("run") String run,@Param("attempt") int attempt,@Param("key") String key);
    int record(@Param("run") String run,@Param("attempt") int attempt,@Param("key") String key,@Param("hash") String hash,@Param("response") String response);
}
