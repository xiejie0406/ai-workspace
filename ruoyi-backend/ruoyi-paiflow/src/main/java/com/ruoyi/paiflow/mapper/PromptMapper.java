package com.ruoyi.paiflow.mapper;

import com.ruoyi.paiflow.domain.PromptRecord;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface PromptMapper {
    PromptRecord find(@Param("id") long id);
    PromptRecord lock(@Param("id") long id);
    List<PromptRecord> list(@Param("space") Long space,@Param("official") boolean official,@Param("search") String search,@Param("group") Long group,@Param("offset") long offset,@Param("size") int size);
    long count(@Param("space") Long space,@Param("official") boolean official,@Param("search") String search,@Param("group") Long group);
    long children(@Param("id") long id);
    long sameName(@Param("space") Long space,@Param("official") boolean official,@Param("group") Long group,@Param("name") String name,@Param("exclude") Long exclude);
    int insert(PromptRecord row);
    int update(PromptRecord row);
    int remove(PromptRecord row);
    int insertVersion(@Param("prompt") long prompt,@Param("version") long version,@Param("payload") String payload,@Param("description") String description,@Param("actor") long actor);
    long latest(@Param("prompt") long prompt);
    List<Map<String,Object>> versions(@Param("prompt") long prompt);
    Map<String,Object> version(@Param("prompt") long prompt,@Param("version") long version);
}
