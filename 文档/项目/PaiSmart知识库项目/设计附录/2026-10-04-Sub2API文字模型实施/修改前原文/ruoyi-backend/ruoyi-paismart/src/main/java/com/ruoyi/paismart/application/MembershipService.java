package com.ruoyi.paismart.application;

import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.paismart.configuration.PaismartEnabled;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** 业务资格不能赋予平台角色，平台账户仍是唯一身份源。 */
@Service
@PaismartEnabled
public class MembershipService {
    private final JdbcTemplate jdbc;
    public MembershipService(@Qualifier("paismartJdbc") JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public boolean isEligible(long userId) {
        if(SecurityUtils.isAdmin(userId)) return true;
        return jdbc.queryForObject("SELECT COUNT(*) FROM ps_member WHERE user_id=? AND status='ACTIVE'",Integer.class,userId)>0;
    }
    public void requireEligible(long userId) { if(!isEligible(userId)) throw new AccessDeniedException("缺少知识库业务资格"); }
}
