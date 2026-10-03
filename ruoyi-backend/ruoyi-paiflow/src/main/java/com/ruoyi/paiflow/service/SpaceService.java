package com.ruoyi.paiflow.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import com.ruoyi.system.service.ISysUserService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "paiflow", name = "enabled", havingValue = "true")
public class SpaceService {
    public record Summary(Long id, String name, String description, String avatarUrl, String uid,
                          Long enterpriseId,
                          @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") LocalDateTime createTime,
                          @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") LocalDateTime updateTime,
                          String ownerName, int memberCount, int userRole, int applyStatus,
                          @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") LocalDateTime lastVisitTime, long revision) {}
    private final SpaceMapper spaces;
    private final ResourceAccessService access;
    private final IdentityMappingService identities;
    private final ISysUserService users;
    private DefaultPersonalSpaceService defaults;
    @org.springframework.beans.factory.annotation.Autowired
    public void useDefaultSpaces(DefaultPersonalSpaceService defaults) { this.defaults=defaults; }
    public SpaceService(SpaceMapper spaces, ResourceAccessService access, IdentityMappingService identities, ISysUserService users) {
        this.spaces = spaces; this.access = access; this.identities = identities; this.users = users;
    }
    private static String name(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 200) throw new ServiceException("空间名称须为1至200个字符", 400);
        return value.trim();
    }
    private static void checkOptional(String description, String avatar) {
        if ((description != null && description.length() > 2000) || (avatar != null && avatar.length() > 2048)) {
            throw new ServiceException("空间描述或头像地址过长", 400);
        }
    }
    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public long createPersonal(String value, String description, String avatarUrl) {
        long actor = access.requirePermission("paiflow:space:create");
        String name = name(value); checkOptional(description, avatarUrl);
        var login = identities.current();
        if (spaces.countName(actor, name, null) > 0) throw new ServiceException("空间名称已存在", 409);
        var space = new SpaceRecord();space.setName(name);space.setDescription(description);space.setAvatarUrl(avatarUrl);
        space.setOwnerUserId(actor);space.setDeptId(login.getDeptId());space.setKind("personal");space.setRevision(0L);space.setDeleted(0);
        space.setCreateBy(login.getUsername());space.setUpdateBy(login.getUsername());
        try {
            if (spaces.insert(space) != 1 || space.getId() == null) throw new ServiceException("空间保存失败", 500);
            if (spaces.insertOwner(space.getId(), actor, login.getUsername()) != 1) throw new ServiceException("空间所有者保存失败", 500);
        } catch (DuplicateKeyException ex) { throw new ServiceException("空间名称已存在，请刷新后重试", 409); }
        return space.getId();
    }
    public List<Summary> listPersonal(String filter, boolean owned) {
        long actor = access.requirePermission("paiflow:space:query");
        if (filter != null && filter.length() > 200) throw new ServiceException("查询名称过长", 400);
        return spaces.list(actor, "personal", filter, owned).stream().map(space -> summary(space, actor, null)).toList();
    }
    public Summary detail(long id) {
        var space = ResourceAccessService.requireCollaborative(access.requireSpace(id, "paiflow:space:query", 3, false));
        return summary(space, identities.current().getUserId(), null);
    }
    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Summary visit(Long id) {
        long actor = access.requirePermission("paiflow:space:query");
        if (id == null || id <= 0) {
            if(defaults==null)throw new ServiceException("默认个人域事务入口尚未装配",503);
            id=defaults.materialize(defaults.defaultSelection("paiflow:space:query"),"paiflow:space:query");
            if(spaces.visit(id,actor)<1)throw new ServiceException("默认个人访问记录保存失败",500);
            spaces.clearTeamVisit(actor);return null;
        }
        var space = access.requireSpace(id, "paiflow:space:query", 3, false);
        if (spaces.visit(id, actor) < 1) throw new ServiceException("空间访问记录保存失败", 500);
        if(space.isDefaultPersonal()){spaces.clearTeamVisit(actor);return null;}
        return summary(space, actor, null);
    }
    public List<Summary> recent() {
        long actor = access.requirePermission("paiflow:space:query");
        return spaces.recent(actor).stream().map(item -> summary(spaces.find(item.getSpaceId()), actor, item.getLastVisitTime())).toList();
    }
    /** 原default访问时间严格晚于最新具名访问才返回null；与原isAfter并列语义一致。 */
    public Summary last() {
        long actor=access.requirePermission("paiflow:space:query");var recent=spaces.recent(actor);
        if(recent.isEmpty())return null;
        var first=recent.get(0);var personal=spaces.defaultPersonalVisit(actor);
        if(personal!=null && personal.getLastVisitTime().isAfter(first.getLastVisitTime()))return null;
        return summary(spaces.find(first.getSpaceId()),actor,first.getLastVisitTime());
    }
    public boolean checkName(String value, Long excludeId) {
        long actor = access.requirePermission("paiflow:space:query");
        if (excludeId != null) ResourceAccessService.requireCollaborative(access.requireSpace(excludeId, "paiflow:space:query", 3, false));
        return spaces.countName(actor, name(value), excludeId) > 0;
    }
    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void updatePersonal(long id, String value, String description, String avatarUrl, Long expectedRevision) {
        var current = access.requireSpace(id, "paiflow:space:edit", 2, true);
        if (!"personal".equals(current.getKind())) throw new ServiceException("请使用团队空间更新入口", 400);
        if (expectedRevision != null && !Objects.equals(expectedRevision, current.getRevision())) throw new ServiceException("空间版本已变化，请刷新", 409);
        current.setName(name(value)); checkOptional(description, avatarUrl);current.setDescription(description);current.setAvatarUrl(avatarUrl);
        current.setUpdateBy(identities.current().getUsername());
        try { if (spaces.update(current) != 1) throw new ServiceException("空间版本已变化，请刷新", 409); }
        catch (DuplicateKeyException ex) { throw new ServiceException("空间名称已存在", 409); }
    }

    /** 商业事实端口默认未接通；隔离测试注入fixture不代表付费或容量已核实。 */
    public interface CapacityPolicy {
        TeamEntitlement teamCreation(long platformUserId);
        int maximumMembers(long ownerUserId, Long teamId);
    }
    public record TeamEntitlement(Integer serviceType, LocalDateTime expireTime, int maximumMembers) {}
    private CapacityPolicy capacityPolicy;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void useCapacityPolicy(CapacityPolicy policy) { this.capacityPolicy=policy; }
    public record TeamSummary(Long id,String uid,String name,String avatarUrl,String logoUrl,Long orgId,
        Integer serviceType,@com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") LocalDateTime createTime,
        @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") LocalDateTime expireTime,
        @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") LocalDateTime updateTime,
        String officerName,int role,long revision,String entitlementStatus) {}
    public com.ruoyi.paiflow.domain.TeamRecord requireTeam(long id,String permission,int maxRole,boolean lock) {
        long actor=access.requirePermission(permission);
        var team=lock?spaces.lockTeam(id):spaces.team(id);var member=spaces.teamMember(id,actor);
        if(team==null || member==null || !"active".equals(member.getStatus())) throw new ServiceException("团队不存在或不可访问",404);
        if(member.getRole()==null || member.getRole()<1 || member.getRole()>maxRole) throw new ServiceException("当前团队角色不能执行此操作",403);
        return team;
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public long createTeam(String value,String avatarUrl) {
        long actor=access.requirePermission("paiflow:space:create");var login=identities.current();
        String teamName=name(value);checkOptional(null,avatarUrl);
        if(capacityPolicy==null) throw new ServiceException("真实团队套餐和容量依据尚未接通",503);
        var entitlement=capacityPolicy.teamCreation(actor);
        if(entitlement==null || entitlement.serviceType()==null || (entitlement.serviceType()!=1 && entitlement.serviceType()!=2) ||
            entitlement.expireTime()==null || !entitlement.expireTime().isAfter(LocalDateTime.now(java.time.Clock.systemUTC())) ||
            entitlement.maximumMembers()<1) throw new ServiceException("没有可核对的有效团队套餐和容量",503);
        spaces.lockOwnerScope(actor);
        if(spaces.countOwnedTeams(actor)>0 || spaces.countTeamName(teamName,null)>0) throw new ServiceException("已经创建团队或团队名称已存在",409);
        var team=new com.ruoyi.paiflow.domain.TeamRecord();team.setName(teamName);team.setAvatarUrl(avatarUrl);team.setOwnerUserId(actor);
        team.setServiceType(entitlement.serviceType());team.setExpireTime(entitlement.expireTime());team.setCreateBy(login.getUsername());team.setUpdateBy(login.getUsername());
        try {
            if(spaces.insertTeam(team)!=1 || team.getId()==null || spaces.insertTeamMember(team.getId(),actor,1,login.getUsername())!=1) throw new ServiceException("团队及所有者保存失败",500);
        } catch(DuplicateKeyException ex) { throw new ServiceException("团队名称或创建者已存在",409); }
        return team.getId();
    }
    public TeamSummary teamDetail(long id) { return teamSummary(requireTeam(id,"paiflow:space:query",3,false)); }
    public List<TeamSummary> joinedTeams() {
        long actor=access.requirePermission("paiflow:space:query");return spaces.joinedTeams(actor).stream().map(this::teamSummary).toList();
    }
    public boolean checkTeamName(String value,Long exclude) {
        access.requirePermission("paiflow:space:query");
        if(exclude!=null)requireTeam(exclude,"paiflow:space:query",3,false);
        return spaces.countTeamName(name(value),exclude)>0;
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public boolean visitTeam(Long id) {
        long actor=access.requirePermission("paiflow:space:query");
        if(id==null || id<=0) { spaces.clearTeamVisit(actor);return true; }
        requireTeam(id,"paiflow:space:query",3,false);
        if(spaces.visitTeam(id,actor)<1)throw new ServiceException("团队访问记录保存失败",500);return true;
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void updateTeam(long id,String field,String value) {
        var team=requireTeam(id,"paiflow:space:edit",2,true);checkOptional(null,value);
        switch(field) { case "name" -> team.setName(name(value));case "avatarUrl" -> team.setAvatarUrl(value);case "logoUrl" -> team.setLogoUrl(value);default -> throw new ServiceException("不支持的团队字段",400); }
        team.setUpdateBy(identities.current().getUsername());
        try { if(spaces.updateTeam(team)!=1)throw new ServiceException("团队记录已变化，请刷新",409); }
        catch(DuplicateKeyException ex){throw new ServiceException("团队名称已存在",409);}
    }
    public int checkNeedCreateTeam() {
        long actor=access.requirePermission("paiflow:space:query");
        if(spaces.countOwnedTeams(actor)>0)return 0;
        if(capacityPolicy==null)throw new ServiceException("真实团队套餐尚未接通",503);
        var data=capacityPolicy.teamCreation(actor);
        if(data==null)return 0;
        if(data.serviceType()==null || (data.serviceType()!=1 && data.serviceType()!=2) || data.expireTime()==null || data.maximumMembers()<1)
            throw new ServiceException("真实团队套餐依据不完整",503);
        return data.expireTime().isAfter(LocalDateTime.now(java.time.Clock.systemUTC()))?data.serviceType():0;
    }
    public int maximumMembers(long ownerUserId,Long teamId) {
        if(capacityPolicy==null)throw new ServiceException("真实成员容量策略尚未接通",503);
        int limit=capacityPolicy.maximumMembers(ownerUserId,teamId);
        if(limit<1)throw new ServiceException("没有可核对的有效成员容量",503);return limit;
    }
    private TeamSummary teamSummary(com.ruoyi.paiflow.domain.TeamRecord team) {
        var member=spaces.teamMember(team.getId(),identities.current().getUserId());var owner=users.selectUserById(team.getOwnerUserId());
        if(member==null || !"active".equals(member.getStatus()))throw new ServiceException("团队不存在或不可访问",404);
        return new TeamSummary(team.getId(),IdentityMappingService.legacyUid(team.getOwnerUserId()),team.getName(),team.getAvatarUrl(),team.getLogoUrl(),
            null,team.getServiceType(),displayTime(team.getCreateTime()),displayTime(team.getExpireTime()),displayTime(team.getUpdateTime()),
            owner==null?null:owner.getNickName(),member.getRole(),team.getRevision(),"not_verified");
    }
    private Summary summary(SpaceRecord space, long actor, LocalDateTime visited) {
        if (space == null || space.getDeleted() == null || space.getDeleted() != 0) throw new ServiceException("空间已失效，请刷新", 404);
        var member = spaces.member(space.getId(), actor);
        if (member == null || !"active".equals(member.getStatus())) throw new ServiceException("空间不存在或不可访问", 404);
        var owner = users.selectUserById(space.getOwnerUserId());
        return new Summary(space.getId(),space.getName(),space.getDescription(),space.getAvatarUrl(),
            IdentityMappingService.legacyUid(space.getOwnerUserId()),space.getTeamId(),displayTime(space.getCreateTime()),displayTime(space.getUpdateTime()),
            owner == null ? null : owner.getNickName(),spaces.members(space.getId()).size(),member.getRole(),1,displayTime(visited),space.getRevision());
    }
    private static LocalDateTime displayTime(LocalDateTime utc) {
        return utc == null ? null : utc.atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime();
    }
}
