package com.ruoyi.paiflow.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 成员业务规则由中心一次维护，所有变更先锁空间行以保护最后所有者。 */
@Service
@ConditionalOnProperty(prefix = "paiflow", name = "enabled", havingValue = "true")
public class MembershipService {
    private final ResourceAccessService access;
    private final IdentityMappingService identities;
    private final SpaceMapper spaces;
    public MembershipService(ResourceAccessService access, IdentityMappingService identities, SpaceMapper spaces) {
        this.access=access;this.identities=identities;this.spaces=spaces;
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void updateRole(long id,String uid,int role) {
        // 原 SpaceUserController_updateRole_POST 权限种子允许 owner/admin；原服务仅保护 owner。
        var space=ResourceAccessService.requireCollaborative(access.requireSpace(id,"paiflow:space:members",2,true));
        if (role!=2 && role!=3) throw new ServiceException("所有者只能通过转让操作变更",400);
        long target=identities.resolveReference(uid);
        if (Objects.equals(space.getOwnerUserId(),target)) throw new ServiceException("不能直接修改所有者角色",409);
        if (spaces.setRole(id,target,role,identities.current().getUsername())!=1) throw new ServiceException("目标用户不是有效空间成员",404);
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void remove(long id,String uid) {
        var space=ResourceAccessService.requireCollaborative(access.requireSpace(id,"paiflow:space:members",2,true));
        long target=identities.resolveReference(uid);
        if (Objects.equals(space.getOwnerUserId(),target)) throw new ServiceException("请先转让所有者，不能移除最后所有者",409);
        var targetMember=spaces.member(id,target);
        if (targetMember==null || !"active".equals(targetMember.getStatus())) throw new ServiceException("目标用户不是有效空间成员",404);
        // 原 SpaceUserBizServiceImpl.remove 允许管理员移除同级，所有者始终受保护。
        if (spaces.removeMember(id,target,identities.current().getUsername())!=1) throw new ServiceException("成员状态已变化，请刷新",409);
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void leave(long id) {
        var space=ResourceAccessService.requireCollaborative(access.requireSpace(id,"paiflow:space:query",3,true));
        var actor=identities.current();
        if (Objects.equals(space.getOwnerUserId(),actor.getUserId())) throw new ServiceException("所有者不能直接退出，请先转让或按归档流程处理",409);
        if (spaces.removeMember(id,actor.getUserId(),actor.getUsername())!=1) throw new ServiceException("成员状态已变化，请刷新",409);
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void transfer(long id,String uid) {
        var space=ResourceAccessService.requireCollaborative(access.requireSpace(id,"paiflow:space:members",1,true));
        var actor=identities.current();
        if (space.getTeamId()==null) throw new ServiceException("个人空间不能转让",400);
        if (!Objects.equals(space.getOwnerUserId(),actor.getUserId())) throw new ServiceException("空间所有者记录不一致，请联系管理员核对",409);
        long target=identities.resolveReference(uid);
        if (target==actor.getUserId()) throw new ServiceException("请选择其他有效成员",400);
        var member=spaces.member(id,target);
        if (member==null || !"active".equals(member.getStatus())) throw new ServiceException("目标用户不是有效空间成员",404);
        if (spaces.transfer(id,target,space.getRevision(),actor.getUsername())!=1 ||
            spaces.setRole(id,target,1,actor.getUsername())!=1 ||
            spaces.setRole(id,actor.getUserId(),2,actor.getUsername())!=1) throw new ServiceException("空间状态已变化，转让未完成",409);
    }

    private SpaceService spaceService;
    private com.ruoyi.system.service.ISysUserService users;
    private NotificationService notifications;
    @org.springframework.beans.factory.annotation.Autowired
    public void useNotifications(NotificationService service) { this.notifications=service; }
    @org.springframework.beans.factory.annotation.Autowired
    public void usePlatformServices(SpaceService service,com.ruoyi.system.service.ISysUserService users) { this.spaceService=service;this.users=users; }
    public record PageQuery(Integer pageNum,Integer pageSize,String nickname,Integer role,Integer status) {}
    public record Page<T>(List<T> records,long total,int current,int size,long pages) {}
    public record InviteAdd(String uid,Integer role) {}
    public record UserLimit(int total,long used,long remain) {}
    private static int page(PageQuery q) {
        if(q==null || q.pageNum()==null || q.pageSize()==null || q.pageNum()<1 || q.pageSize()<1 || q.pageSize()>100 ||
            (long)(q.pageNum()-1)*q.pageSize()>Integer.MAX_VALUE)throw new ServiceException("分页须提供合法页码和1至100条每页",400);
        if(q.nickname()!=null && q.nickname().length()>200)throw new ServiceException("昵称查询过长",400);
        if(q.role()!=null && (q.role()<0 || q.role()>3))throw new ServiceException("角色查询无效",400);
        return (q.pageNum()-1)*q.pageSize();
    }
    private static <T> Page<T> pageResult(List<T> values,long count,PageQuery q) { return new Page<>(values,count,q.pageNum(),q.pageSize(),(count+q.pageSize()-1)/q.pageSize()); }
    private static java.time.LocalDateTime now() { return java.time.LocalDateTime.now(java.time.Clock.systemUTC()); }
    private static Object time(java.time.LocalDateTime value) { return value==null?null:value.atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); }
    private static String uid(Long value) { return value==null?null:IdentityMappingService.legacyUid(value); }
    private com.ruoyi.common.core.domain.entity.SysUser user(long id) { return users.selectUserById(id); }
    private String nickname(long id) { var value=user(id);return value==null?null:value.getNickName(); }
    private com.alibaba.fastjson2.JSONObject memberView(com.ruoyi.paiflow.domain.SpaceMember m) {
        var out=new com.alibaba.fastjson2.JSONObject();out.put("id",m.getId());out.put("spaceId",m.getSpaceId());out.put("uid",uid(m.getUserId()));out.put("nickname",nickname(m.getUserId()));
        out.put("role",m.getRole());out.put("createTime",time(m.getCreateTime()));out.put("updateTime",time(m.getUpdateTime()));out.put("lastVisitTime",time(m.getLastVisitTime()));out.put("revision",m.getRevision());return out;
    }
    public Page<com.alibaba.fastjson2.JSONObject> memberPage(long id,PageQuery q) {
        int offset=page(q);ResourceAccessService.requireCollaborative(access.requireSpace(id,"paiflow:space:query",3,false));
        return pageResult(spaces.memberPage(id,q.nickname(),q.role(),offset,q.pageSize()).stream().map(this::memberView).toList(),spaces.countMembers(id,q.nickname(),q.role()),q);
    }
    public List<com.alibaba.fastjson2.JSONObject> memberList(long id) {
        ResourceAccessService.requireCollaborative(access.requireSpace(id,"paiflow:space:query",3,false));
        // 原接口没有分页。超过有限响应预算明确失败，不能静默截断并声称完整。
        if(spaces.countMembers(id,null,0)>10000)throw new ServiceException("成员数量超过列表响应预算，请使用分页入口",413);
        return spaces.memberPage(id,null,0,0,10000).stream().filter(m->m.getRole()!=1).map(this::memberView).toList();
    }
    public Page<com.alibaba.fastjson2.JSONObject> teamMemberPage(long id,PageQuery q) {
        int offset=page(q);spaceService.requireTeam(id,"paiflow:space:query",3,false);
        var values=spaces.teamMemberPage(id,q.nickname(),q.role(),offset,q.pageSize()).stream().map(m->{
            var out=new com.alibaba.fastjson2.JSONObject();var platform=user(m.getUserId());out.put("id",m.getId());out.put("enterpriseId",id);out.put("uid",uid(m.getUserId()));
            out.put("username",platform==null?null:platform.getUserName());out.put("nickname",platform==null?null:platform.getNickName());out.put("role",m.getRole());
            out.put("createTime",time(m.getCreateTime()));out.put("updateTime",time(m.getUpdateTime()));out.put("revision",m.getRevision());return out;
        }).toList();
        return pageResult(values,spaces.countTeamMembers(id,q.nickname(),q.role()),q);
    }
    private static boolean active(com.ruoyi.paiflow.domain.SpaceMember m){return m!=null && "active".equals(m.getStatus());}
    private static boolean active(com.ruoyi.paiflow.domain.TeamMember m){return m!=null && "active".equals(m.getStatus());}
    private static void role(int role) { if(role!=2 && role!=3)throw new ServiceException("仅允许管理员或成员角色，所有者需通过转让处理",400); }
    private void addSpaceMember(long id,long target,int role,String actor) {
        var existing=spaces.member(id,target);
        if(active(existing))throw new ServiceException("用户已经是空间成员",409);
        int result=existing==null?spaces.insertMember(id,target,role,actor):spaces.reactivateMember(id,target,role,actor);
        if(result!=1)throw new ServiceException("空间成员状态已变化",409);
    }
    private void addTeamMember(long id,long target,int role,String actor) {
        var existing=spaces.teamMember(id,target);
        if(active(existing))throw new ServiceException("用户已经是团队成员",409);
        int result=existing==null?spaces.insertTeamMember(id,target,role,actor):spaces.reactivateTeamMember(id,target,role,actor);
        if(result!=1)throw new ServiceException("团队成员状态已变化",409);
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void enterpriseAdd(long id,String reference,int role) {
        role(role);var initial=spaces.find(id);
        if(initial==null || initial.getTeamId()==null)throw new ServiceException("目标不是团队空间",400);
        spaceService.requireTeam(initial.getTeamId(),"paiflow:space:members",3,true);
        var space=ResourceAccessService.requireCollaborative(access.requireSpace(id,"paiflow:space:members",2,true));long target=identities.resolveReference(reference);
        if(!Objects.equals(space.getTeamId(),initial.getTeamId()) || !active(spaces.teamMember(space.getTeamId(),target)))throw new ServiceException("用户不属于该空间所在团队",404);
        addSpaceMember(id,target,role,identities.current().getUsername());
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void updateTeamRole(long id,String reference,int role) {
        role(role);var team=spaceService.requireTeam(id,"paiflow:space:members",2,true);long target=identities.resolveReference(reference);
        if(target==team.getOwnerUserId())throw new ServiceException("团队所有者角色不能直接修改",409);
        if(spaces.setTeamRole(id,target,role,identities.current().getUsername())!=1)throw new ServiceException("目标用户不是有效团队成员",404);
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void removeTeam(long id,String reference,boolean self) {
        var team=spaceService.requireTeam(id,self?"paiflow:space:query":"paiflow:space:members",self?3:2,true);
        long target=self?identities.current().getUserId():identities.resolveReference(reference);String actor=identities.current().getUsername();
        if(target==team.getOwnerUserId())throw new ServiceException("团队所有者不能移除或退出",409);
        if(!active(spaces.teamMember(id,target)))throw new ServiceException("目标用户不是有效团队成员",404);
        for(var item:spaces.teamSpaces(id)) {
            var space=spaces.lock(item.getId());var member=spaces.member(item.getId(),target);
            if(space==null || !Objects.equals(space.getTeamId(),id))throw new ServiceException("团队空间归属已变化",409);
            if(!active(member))continue;
            if(member.getRole()==1) {
                long owner=team.getOwnerUserId();var ownerMember=spaces.member(space.getId(),owner);
                if(ownerMember==null) { if(spaces.insertMember(space.getId(),owner,1,actor)!=1)throw new ServiceException("接管空间所有者失败",409); }
                else if(!active(ownerMember)) {
                    if(spaces.reactivateMember(space.getId(),owner,1,actor)!=1)throw new ServiceException("接管空间所有者失败",409);
                } else if(spaces.setRole(space.getId(),owner,1,actor)!=1)throw new ServiceException("接管空间所有者失败",409);
                if(spaces.transfer(space.getId(),owner,space.getRevision(),actor)!=1 || spaces.setRole(space.getId(),target,3,actor)!=1)throw new ServiceException("空间所有者接管失败",409);
            }
            if(spaces.removeMember(space.getId(),target,actor)!=1)throw new ServiceException("空间成员移除失败",409);
        }
        if(spaces.removeTeamMember(id,target,actor)!=1)throw new ServiceException("团队成员状态已变化",409);
    }
    private record Scope(int type,long id,Long teamId,long ownerUserId,com.ruoyi.paiflow.domain.SpaceRecord space) {}
    private Scope scope(int type,long id,int maxRole,boolean lock) {
        if(type==2) { var team=spaceService.requireTeam(id,"paiflow:space:members",maxRole,lock);return new Scope(2,id,id,team.getOwnerUserId(),null); }
        if(type!=1)throw new ServiceException("邀请类型无效",400);
        var initial=spaces.find(id);if(initial==null)throw new ServiceException("空间不存在",404);
        if(lock) { if(initial.getTeamId()==null)spaces.lockOwnerScope(initial.getOwnerUserId());else spaceService.requireTeam(initial.getTeamId(),"paiflow:space:members",3,true); }
        var space=ResourceAccessService.requireCollaborative(access.requireSpace(id,"paiflow:space:members",maxRole,lock));
        if(!Objects.equals(initial.getTeamId(),space.getTeamId()) || !Objects.equals(initial.getOwnerUserId(),space.getOwnerUserId()))throw new ServiceException("空间归属已变化",409);
        var team=space.getTeamId()==null?null:spaces.team(space.getTeamId());
        if(space.getTeamId()!=null && team==null)throw new ServiceException("空间所属团队已失效",404);
        long owner=team==null?space.getOwnerUserId():team.getOwnerUserId();
        return new Scope(1,id,space.getTeamId(),owner,space);
    }
    private UserLimit limit(Scope scope) {
        int total=spaceService.maximumMembers(scope.ownerUserId(),scope.teamId());long used=spaces.countCapacityMembers(scope.ownerUserId(),scope.teamId())+spaces.countCapacityInvites(scope.ownerUserId(),scope.teamId());
        return new UserLimit(total,used,Math.max(0,total-used));
    }
    public UserLimit userLimit(int type,long id) {
        var scope=scope(type,id,3,false);
        // 原团队空间页面只显示该空间成员+该空间待加入人数；实际邀请仍检查团队总体容量。
        if(type==1 && scope.teamId()!=null) {
            int total=spaceService.maximumMembers(scope.ownerUserId(),scope.teamId());
            long used=spaces.countMembers(id,null,0)+spaces.countPendingSpaceInvites(id);return new UserLimit(total,used,Math.max(0,total-used));
        }
        return limit(scope);
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public List<Long> invite(int type,long id,List<InviteAdd> requests) {
        if(requests==null || requests.isEmpty() || requests.size()>100)throw new ServiceException("邀请须提供1至100名用户",400);
        Scope scope=scope(type,id,2,true);var quota=limit(scope);
        if(quota.used()+requests.size()>quota.total())throw new ServiceException("成员及待加入邀请超过正式容量",409);
        spaces.expireInvites(scope.ownerUserId(),scope.teamId());var actor=identities.current();var targets=new java.util.HashSet<Long>();var records=new java.util.ArrayList<com.ruoyi.paiflow.domain.MembershipInvitation>();
        for(var request:requests) {
            if(request==null || request.role()==null)throw new ServiceException("邀请用户或角色缺失",400);role(request.role());long target=identities.resolveReference(request.uid());
            if(!targets.add(target) || (type==1?active(spaces.member(id,target)):active(spaces.teamMember(id,target))) || spaces.pendingInvitation(type,id,target)>0)throw new ServiceException("邀请中存在重复、已加入或待加入用户",409);
            var record=new com.ruoyi.paiflow.domain.MembershipInvitation();record.setType(type);record.setSpaceId(type==1?id:null);record.setTeamId(scope.teamId());record.setOwnerUserId(scope.ownerUserId());
            record.setInviteeUserId(target);record.setRole(request.role());record.setInviterUserId(actor.getUserId());record.setExpireTime(now().plusDays(7));records.add(record);
        }
        var ids=new java.util.ArrayList<Long>();
        if(notifications==null)throw new ServiceException("站内通知持久化尚未装配，邀请未保存",503);
        try { for(var record:records) {
            record.setNotificationStatus("stored_in_center");
            if(spaces.insertInvitation(record)!=1 || record.getId()==null)throw new ServiceException("邀请记录保存失败",500);
            notifications.recordPersonal(record.getInviteeUserId(),type==1?"空间邀请":"团队邀请",
                actor.getUser().getNickName()+"邀请你加入"+(type==1?"空间":"团队")+"，请在邀请列表处理。","paiflow.membership.invite",
                com.alibaba.fastjson2.JSONObject.of("inviteId",record.getId(),"type",type,"spaceId",record.getSpaceId(),"teamId",record.getTeamId()),record.getExpireTime());
            ids.add(record.getId());
        } }
        catch(org.springframework.dao.DuplicateKeyException ex) {throw new ServiceException("存在并发重复邀请，请刷新",409);}
        // 站内消息与邀请同事务保存；stored_in_center只表示入库，不表示已读或外部送达。
        return ids;
    }
    private com.ruoyi.paiflow.domain.MembershipInvitation checkedInvitation(long id) {
        var record=spaces.invitation(id);if(record==null)throw new ServiceException("邀请记录不存在",404);
        if(record.getStatus()!=1 || !record.getExpireTime().isAfter(now()))throw new ServiceException("邀请已处理或已过期",409);return record;
    }
    private Scope invitationScope(com.ruoyi.paiflow.domain.MembershipInvitation record) {
        if(record.getType()==2) {
            var team=spaces.lockTeam(record.getTeamId());if(team==null)throw new ServiceException("邀请团队已失效",404);
            return new Scope(2,team.getId(),team.getId(),team.getOwnerUserId(),null);
        }
        if(record.getTeamId()!=null) { if(spaces.lockTeam(record.getTeamId())==null)throw new ServiceException("邀请团队已失效",404); }
        else spaces.lockOwnerScope(record.getOwnerUserId());
        var space=spaces.lock(record.getSpaceId());
        if(space==null || !Objects.equals(space.getTeamId(),record.getTeamId()) || (space.getTeamId()==null && !Objects.equals(space.getOwnerUserId(),record.getOwnerUserId())))throw new ServiceException("邀请空间已失效或归属变化",404);
        ResourceAccessService.requireCollaborative(space);
        long owner=space.getTeamId()==null?space.getOwnerUserId():spaces.team(space.getTeamId()).getOwnerUserId();
        return new Scope(1,space.getId(),space.getTeamId(),owner,space);
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void respondInvitation(long id,boolean accept,Long expectedRevision) {
        long actor=access.requirePermission("paiflow:space:query");var initial=spaces.invitation(id);
        if(initial==null || !Objects.equals(initial.getInviteeUserId(),actor))throw new ServiceException("邀请不存在或当前用户不是被邀请人",404);
        var scope=invitationScope(initial);var record=checkedInvitation(id);
        if(expectedRevision!=null && !Objects.equals(record.getRevision(),expectedRevision))throw new ServiceException("邀请版本已变化",409);
        if(accept) { var quota=limit(scope);if(quota.used()>quota.total())throw new ServiceException("正式成员容量已不足",409); }
        if(spaces.invitationTransition(id,record.getRevision(),accept?3:2)!=1)throw new ServiceException("邀请已由其他请求处理",409);
        if(accept) {
            String username=identities.current().getUsername();
            if(record.getType()==2)addTeamMember(record.getTeamId(),actor,record.getRole(),username);
            else { addSpaceMember(record.getSpaceId(),actor,record.getRole(),username);
                if(record.getTeamId()!=null && !active(spaces.teamMember(record.getTeamId(),actor)))addTeamMember(record.getTeamId(),actor,record.getRole(),username); }
        }
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void revokeInvitation(int type,long scopeId,long id,Long expectedRevision) {
        var scope=scope(type,scopeId,2,true);var record=checkedInvitation(id);
        if((type==1 && (!Objects.equals(record.getSpaceId(),scope.id()) || record.getType()!=1)) || (type==2 && !Objects.equals(record.getTeamId(),scope.id())))throw new ServiceException("邀请不属于当前空间或团队",404);
        if(expectedRevision!=null && !Objects.equals(expectedRevision,record.getRevision()))throw new ServiceException("邀请版本已变化",409);
        if(spaces.invitationTransition(id,record.getRevision(),4)!=1)throw new ServiceException("邀请已由其他请求处理",409);
    }
    private com.alibaba.fastjson2.JSONObject invitationView(com.ruoyi.paiflow.domain.MembershipInvitation r) {
        var out=new com.alibaba.fastjson2.JSONObject();out.put("id",r.getId());out.put("type",r.getType());out.put("spaceId",r.getSpaceId());out.put("enterpriseId",r.getTeamId());out.put("inviteeUid",uid(r.getInviteeUserId()));out.put("role",r.getRole());
        out.put("inviteeNickname",nickname(r.getInviteeUserId()));out.put("inviterUid",uid(r.getInviterUserId()));out.put("expireTime",time(r.getExpireTime()));out.put("status",r.getStatus()==1 && !r.getExpireTime().isAfter(now())?5:r.getStatus());
        out.put("createTime",time(r.getCreateTime()));out.put("updateTime",time(r.getUpdateTime()));out.put("revision",r.getRevision());out.put("notificationStatus",r.getNotificationStatus());
        var inviter=user(r.getInviterUserId());var invitee=user(r.getInviteeUserId());var owner=user(r.getOwnerUserId());out.put("inviterName",inviter==null?null:inviter.getNickName());out.put("inviterAvatar",inviter==null?null:inviter.getAvatar());out.put("inviteeAvatar",invitee==null?null:invitee.getAvatar());
        out.put("ownerName",owner==null?null:owner.getNickName());out.put("ownerAvatar",owner==null?null:owner.getAvatar());var space=r.getSpaceId()==null?null:spaces.find(r.getSpaceId());var team=r.getTeamId()==null?null:spaces.team(r.getTeamId());
        out.put("spaceName",space==null?null:space.getName());out.put("spaceDescription",space==null?null:space.getDescription());out.put("spaceAvatar",space==null?null:space.getAvatarUrl());out.put("enterpriseName",team==null?null:team.getName());out.put("enterpriseAvatar",team==null?null:team.getAvatarUrl());
        out.put("isBelong",r.getType()==1?active(spaces.member(r.getSpaceId(),identities.current().getUserId())):active(spaces.teamMember(r.getTeamId(),identities.current().getUserId())));return out;
    }
    public Page<com.alibaba.fastjson2.JSONObject> invitations(int type,long id,PageQuery q) {
        int offset=page(q);if(q.status()!=null && (q.status()<0 || q.status()>5))throw new ServiceException("邀请状态无效",400);scope(type,id,2,false);
        return pageResult(spaces.invitationPage(type,id,q.nickname(),q.status(),offset,q.pageSize()).stream().map(this::invitationView).toList(),spaces.countInvitations(type,id,q.nickname(),q.status()),q);
    }
    public List<com.alibaba.fastjson2.JSONObject> searchUsers(int type,long id,String value,boolean mobile) {
        scope(type,id,type==1?3:2,false);
        if(value==null || value.isBlank() || value.length()>100)throw new ServiceException("用户查询不能为空或过长",400);
        var query=new com.ruoyi.common.core.domain.entity.SysUser();if(mobile)query.setPhonenumber(value);else query.setUserName(value);query.setStatus("0");query.setDelFlag("0");
        return users.selectUserList(query).stream().filter(u->"0".equals(u.getStatus()) && "0".equals(u.getDelFlag()) && value.equals(mobile?u.getPhonenumber():u.getUserName())).limit(100).map(u->{
            var out=new com.alibaba.fastjson2.JSONObject();out.put("mobile",u.getPhonenumber());out.put("username",u.getUserName());out.put("nickname",u.getNickName());out.put("uid",uid(u.getUserId()));out.put("avatar",u.getAvatar());
            out.put("status",(type==1?active(spaces.member(id,u.getUserId())):active(spaces.teamMember(id,u.getUserId())))?1:spaces.pendingInvitation(type,id,u.getUserId())>0?2:0);return out;
        }).toList();
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void apply(long teamId,long spaceId) {
        long actor=access.requirePermission("paiflow:space:query");spaceService.requireTeam(teamId,"paiflow:space:query",3,true);
        var space=spaces.lock(spaceId);
        if(space!=null)ResourceAccessService.requireCollaborative(space);
        if(space==null || !Objects.equals(space.getTeamId(),teamId))throw new ServiceException("目标空间不属于当前团队",404);
        if(active(spaces.member(spaceId,actor)) || spaces.pendingApplication(spaceId,actor)>0)throw new ServiceException("已经是成员或存在待处理申请",409);
        if(spaces.teamMember(teamId,actor).getRole()==1) { addSpaceMember(spaceId,actor,2,identities.current().getUsername());return; }
        var record=new com.ruoyi.paiflow.domain.MembershipApplication();record.setSpaceId(spaceId);record.setTeamId(teamId);record.setApplicantUserId(actor);
        try { if(spaces.insertApplication(record)!=1)throw new ServiceException("申请保存失败",500); }
        catch(org.springframework.dao.DuplicateKeyException ex){throw new ServiceException("存在并发重复申请",409);}
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void auditApplication(long spaceId,long id,boolean accept,Long expectedRevision) {
        var initial=spaces.application(id);if(initial==null || !Objects.equals(initial.getSpaceId(),spaceId))throw new ServiceException("申请不属于当前空间",404);
        spaceService.requireTeam(initial.getTeamId(),"paiflow:space:members",3,true);var space=ResourceAccessService.requireCollaborative(access.requireSpace(spaceId,"paiflow:space:members",2,true));
        if(!Objects.equals(space.getTeamId(),initial.getTeamId()))throw new ServiceException("申请团队归属已变化",404);
        var record=spaces.application(id);
        if(record.getStatus()!=1 || (expectedRevision!=null && !Objects.equals(record.getRevision(),expectedRevision)))throw new ServiceException("申请状态或版本已变化",409);
        if(!active(spaces.teamMember(record.getTeamId(),record.getApplicantUserId())))throw new ServiceException("申请人已不属于该团队",409);
        long actor=identities.current().getUserId();
        if(spaces.applicationTransition(id,record.getRevision(),accept?2:3,actor)!=1)throw new ServiceException("申请已经处理",409);
        if(accept) {
            identities.resolveReference(uid(record.getApplicantUserId()));
            addSpaceMember(spaceId,record.getApplicantUserId(),3,identities.current().getUsername());
        }
    }
    private com.alibaba.fastjson2.JSONObject applicationView(com.ruoyi.paiflow.domain.MembershipApplication r) {
        var out=new com.alibaba.fastjson2.JSONObject();out.put("id",r.getId());out.put("enterpriseId",r.getTeamId());out.put("spaceId",r.getSpaceId());out.put("applyUid",uid(r.getApplicantUserId()));out.put("applyNickname",nickname(r.getApplicantUserId()));
        out.put("applyTime",time(r.getCreateTime()));out.put("status",r.getStatus());out.put("auditTime",time(r.getAuditTime()));out.put("auditUid",uid(r.getAuditUserId()));out.put("createTime",time(r.getCreateTime()));out.put("updateTime",time(r.getUpdateTime()));out.put("revision",r.getRevision());return out;
    }
    public Page<com.alibaba.fastjson2.JSONObject> applications(long spaceId,PageQuery q) {
        int offset=page(q);if(q.status()!=null && (q.status()<0 || q.status()>3))throw new ServiceException("申请状态无效",400);ResourceAccessService.requireCollaborative(access.requireSpace(spaceId,"paiflow:space:members",2,false));
        return pageResult(spaces.applicationPage(spaceId,q.nickname(),q.status(),offset,q.pageSize()).stream().map(this::applicationView).toList(),spaces.countApplications(spaceId,q.nickname(),q.status()),q);
    }
    public List<com.ruoyi.paiflow.domain.SpaceMember> members(long id) {
        ResourceAccessService.requireCollaborative(access.requireSpace(id,"paiflow:space:query",3,false));
        return spaces.members(id);
    }
}
