package com.ruoyi.paiflow.mapper;

import com.ruoyi.paiflow.domain.PublicConfigRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 只读正式公开表；不查询原包含秘密的config_info，无写方法。 */
public interface PublicConfigMapper {
    List<PublicConfigRecord> byCategory(@Param("category") String category);
    List<PublicConfigRecord> byCategoryAndCode(@Param("category") String category,@Param("code") String code);
    PublicConfigRecord first(@Param("category") String category,@Param("code") String code);
    List<PublicConfigRecord> tags(@Param("code") String code);
}
