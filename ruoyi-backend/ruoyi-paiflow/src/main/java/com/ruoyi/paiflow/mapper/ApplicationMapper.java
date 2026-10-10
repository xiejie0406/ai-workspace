package com.ruoyi.paiflow.mapper;

import com.ruoyi.paiflow.domain.ApplicationRecord;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 私有应用由中心保存；调用关联和幂等请求与Run在同一事务。 */
public interface ApplicationMapper {
    ApplicationRecord find(@Param("id") String id);
    ApplicationRecord lock(@Param("id") String id);
    List<ApplicationRecord> list(@Param("spaceId") long spaceId,@Param("owner") long owner,@Param("offset") long offset,@Param("limit") int limit);
    long count(@Param("spaceId") long spaceId,@Param("owner") long owner);
    int insert(ApplicationRecord row);
    int update(ApplicationRecord row);
    int delete(@Param("id") String id,@Param("revision") long revision,@Param("actor") String actor);
    Map<String,Object> request(@Param("id") String id,@Param("owner") long owner,@Param("key") String key);
    int link(@Param("id") String id,@Param("owner") long owner,@Param("runId") String runId,@Param("version") long version,
             @Param("revision") long revision,@Param("key") String key,@Param("hash") String hash);
}
