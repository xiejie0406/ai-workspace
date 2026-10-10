package com.ruoyi.paiflow.mapper;

import com.ruoyi.paiflow.domain.PublicationPreparationRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface PublicationPreparationMapper {
    PublicationPreparationRecord find(@Param("id") String id);
    PublicationPreparationRecord lock(@Param("id") String id);
    PublicationPreparationRecord request(@Param("applicationId") String applicationId,@Param("owner") long owner,@Param("key") String key);
    List<PublicationPreparationRecord> list(@Param("spaceId") long spaceId,@Param("owner") long owner,@Param("offset") long offset,@Param("limit") int limit);
    long count(@Param("spaceId") long spaceId,@Param("owner") long owner);
    int insert(PublicationPreparationRecord row);
    int withdraw(@Param("id") String id,@Param("revision") long revision);
}
