package com.ruoyi.paiflow.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 收件人从平台主体导出；广播已读/删除也按本人独立保存。 */
public interface NotificationMapper {
    List<Map<String,Object>> page(@Param("userId") long userId,@Param("type") String type,
        @Param("unreadOnly") boolean unreadOnly,@Param("offset") long offset,@Param("limit") int limit);
    long count(@Param("userId") long userId,@Param("type") String type,@Param("unreadOnly") boolean unreadOnly);
    List<Long> unreadIds(@Param("userId") long userId,@Param("limit") int limit);
    int markRead(@Param("userId") long userId,@Param("ids") List<Long> ids);
    Long visible(@Param("userId") long userId,@Param("id") long id);
    int remove(@Param("userId") long userId,@Param("id") long id);
    int insert(Map<String,Object> value);
    int recipient(@Param("id") long id,@Param("userId") long userId);
}
