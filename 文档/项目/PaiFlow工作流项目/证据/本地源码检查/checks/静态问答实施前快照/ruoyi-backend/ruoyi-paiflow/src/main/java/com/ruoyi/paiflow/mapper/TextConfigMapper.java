package com.ruoyi.paiflow.mapper;

import com.ruoyi.paiflow.domain.TextConfigRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface TextConfigMapper {
    TextConfigRecord find(@Param("id") long id);
    TextConfigRecord lockOwned(@Param("id") long id,@Param("owner") long owner);
    List<TextConfigRecord> list(@Param("owner") long owner);
    long countSeparator(@Param("owner") long owner,@Param("separator") String separator,@Param("excludeId") Long excludeId);
    int insert(TextConfigRecord row);
    int update(TextConfigRecord row);
    int delete(@Param("id") long id,@Param("owner") long owner,@Param("revision") long revision);
}
