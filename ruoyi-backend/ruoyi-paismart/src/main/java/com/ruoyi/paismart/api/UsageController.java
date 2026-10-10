package com.ruoyi.paismart.api;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.paismart.application.*;
import com.ruoyi.paismart.application.billing.QuotaService;
import com.ruoyi.paismart.configuration.PaismartEnabled;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paismart/usage")
@PaismartEnabled
public class UsageController {
    private final JdbcTemplate jdbc;private final QuotaService service;private final MembershipService membership;
    public UsageController(@Qualifier("paismartJdbc")JdbcTemplate jdbc,QuotaService service,MembershipService membership){this.jdbc=jdbc;this.service=service;this.membership=membership;}
    @GetMapping("/me") @PreAuthorize("@ss.hasPermi('paismart:usage:list')")
    public AjaxResult me(){long uid=PaismartIdentity.userId();membership.requireEligible(uid);return AjaxResult.success(view(uid));}
    @GetMapping("/overview") @PreAuthorize("@ss.hasPermi('paismart:usage:audit')")
    public AjaxResult overview(@RequestParam(required=false)Long userId,@RequestParam(defaultValue="7")int days){if(userId!=null)return AjaxResult.success(view(userId));return AjaxResult.success(service.overview(days));}
    @GetMapping("/records") @PreAuthorize("@ss.hasPermi('paismart:usage:list')")
    public TableDataInfo records(@RequestParam(required=false)Long userId,@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize,@RequestParam(defaultValue="")String kind,@RequestParam(defaultValue="")String startTime,@RequestParam(defaultValue="")String endTime){
        long me=PaismartIdentity.userId(),uid=userId==null?me:userId;
        if(uid!=me && !com.ruoyi.common.utils.SecurityUtils.hasPermi("paismart:usage:audit"))throw new org.springframework.security.access.AccessDeniedException("额度审计未授权");
        StringBuilder where=new StringBuilder(" WHERE l.user_id=?");List<Object> args=new ArrayList<>();args.add(uid);
        if(!kind.isBlank()){if(Set.of("LLM","EMBEDDING").contains(kind))where.append(" AND l.scope=?");else if(Set.of("CREDIT","RESERVE","SETTLE","RELEASE").contains(kind))where.append(" AND l.action=?");else throw new IllegalArgumentException("流水类型无效");args.add(kind);}
        try{if(!startTime.isBlank()){where.append(" AND l.created_at>=?");args.add(java.sql.Timestamp.valueOf(java.time.LocalDate.parse(startTime).atStartOfDay()));}if(!endTime.isBlank()){where.append(" AND l.created_at<?");args.add(java.sql.Timestamp.valueOf(java.time.LocalDate.parse(endTime).plusDays(1).atStartOfDay()));}}catch(java.time.DateTimeException ex){throw new IllegalArgumentException("流水日期格式无效");}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM ps_token_ledger l"+where,Long.class,args.toArray());int size=Math.max(1,Math.min(100,pageSize));args.add(size);args.add(Math.max(0,pageNum-1)*size);
        var rows=jdbc.queryForList("SELECT l.id,l.user_id AS userId,u.user_name AS userName,l.scope,l.action,l.tokens,l.available_after AS availableAfter,l.frozen_after AS frozenAfter,l.debt_after AS debtAfter,l.reason,l.operation_key AS correlationId,l.created_at AS createdAt FROM ps_token_ledger l LEFT JOIN sys_user u ON u.user_id=l.user_id"+where+" ORDER BY l.created_at DESC,l.id DESC LIMIT ? OFFSET ?",args.toArray());return PaismartTables.page(rows,total);
    }
    private Map<String,Object>view(long uid){return service.snapshot(uid);}
}
