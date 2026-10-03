package com.ruoyi.paiflow.mapper;

import com.ruoyi.paiflow.domain.UserAgreement;
import org.apache.ibatis.annotations.Param;

public interface IdentityMapper {
    Long findPlatformUserId(@Param("source") String source, @Param("oldUid") String oldUid);
    UserAgreement findAgreement(@Param("userId") long userId);
    int agree(@Param("userId") long userId, @Param("actor") String actor);
}
