package com.ruoyi.paiflow.mapper;

import java.util.*;
import org.apache.ibatis.annotations.Param;

/** 资源及不可变版本只由中心MySQL持有。 */
public interface ToolMapper {
 Map<String,Object> find(@Param("id") long id);
 Map<String,Object> lock(@Param("id") long id);
 Map<String,Object> byPlugin(@Param("plugin") String plugin);
 List<Map<String,Object>> list(@Param("space") long space,@Param("offset") long offset,@Param("limit") int limit,@Param("search") String search,@Param("temporary") Integer temporary);
 long count(@Param("space") long space,@Param("search") String search,@Param("temporary") Integer temporary);
 int insert(Map<String,Object> row);
 int update(Map<String,Object> row);
 int version(Map<String,Object> row);
 Map<String,Object> fixed(@Param("id") long id);
 Map<String,Object> byVersion(@Param("tool") long tool,@Param("version") String version);
 List<Map<String,Object>> versions(@Param("tool") long tool);
 Map<String,Object> operation(@Param("id") String id);
 Map<String,Object> request(@Param("run") String run,@Param("lease") String lease,@Param("attempt") int attempt,@Param("hash") String hash);
 int insertOperation(Map<String,Object> row);
 long countOperations(@Param("run") String run,@Param("node") String node);
 long countResourceOperations(@Param("run") String run,@Param("resource") String resource);
 List<Map<String,Object>> nodeOperations(@Param("run") String run,@Param("lease") String lease,@Param("attempt") int attempt,@Param("node") String node);
 int updateOperation(Map<String,Object> row);
}
