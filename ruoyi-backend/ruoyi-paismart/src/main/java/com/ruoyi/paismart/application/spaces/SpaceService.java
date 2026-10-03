package com.ruoyi.paismart.application.spaces;

import com.ruoyi.common.exception.ServiceException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 知识空间独立于平台部门；所有资源入口使用同一范围裁决。 */
@Service
@ConditionalOnProperty(prefix="paismart", name="enabled", havingValue="true")
public class SpaceService {
    public record Scope(long userId, boolean administrator) {
        public Scope { if (userId <= 0) throw new ServiceException("登录身份无效", 401); }
    }
    public record Space(String id, String parentId, String name, String type, Long deptId,
                        int uploadLimit, long rowVersion,Long ownerId,boolean canManage,Long uploadMaxSizeBytes,String description) {}
    public record Member(long userId, String spaceId, boolean primary, boolean uploadAllowed,boolean managerAllowed) {}
    public record Assignment(String spaceId,boolean uploadAllowed,boolean managerAllowed) {}
    private final JdbcTemplate jdbc;
    public SpaceService(@Qualifier("paismartJdbc") JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static ServiceException denied() { return new ServiceException("没有知识空间访问权限",403); }
    public List<Space> list(Scope scope) {
        Set<String> ids=visibleSpaceIds(scope);
        return all().stream().filter(s -> scope.administrator() || ids.contains(s.id()))
            .map(s->new Space(s.id(),s.parentId(),s.name(),s.type(),s.deptId(),s.uploadLimit(),s.rowVersion(),s.ownerId(),canManageSpace(scope,s.id()),s.uploadMaxSizeBytes(),s.description())).toList();
    }
    private List<Space> all() {
        return jdbc.query("SELECT * FROM ps_space ORDER BY name,id", (r,n)->new Space(r.getString("id"),
          r.getString("parent_id"),r.getString("name"),r.getString("type"),
          r.getObject("dept_id")==null?null:r.getLong("dept_id"),r.getInt("upload_limit"),r.getLong("row_version"),r.getObject("owner_id")==null?null:r.getLong("owner_id"),false,r.getObject("upload_max_size_bytes")==null?null:r.getLong("upload_max_size_bytes"),r.getString("description")));
    }
    public Space get(String id) {
        return all().stream().filter(s->s.id().equals(id)).findFirst()
            .orElseThrow(()->new ServiceException("知识空间不存在",404));
    }
    /** 成员可读直接组织及祖先，不由父组织身份自动获得子组织权限。 */
    public Set<String> visibleSpaceIds(Scope scope) {
        Map<String,Space> spaces=new HashMap<>(); all().forEach(s->spaces.put(s.id(),s));
        if(scope.administrator()) return new LinkedHashSet<>(spaces.keySet());
        Set<String> result=new LinkedHashSet<>();
        spaces.values().stream().filter(s->Objects.equals(s.ownerId(),scope.userId())).map(Space::id).forEach(result::add);
        List<String> direct=jdbc.queryForList("SELECT space_id FROM ps_member_space WHERE user_id=?",String.class,scope.userId());
        for(String start:direct) {
            Set<String> visited=new HashSet<>(); String id=start;
            while(id!=null && visited.add(id)) {
                Space space=spaces.get(id); if(space==null) break;
                result.add(id); id=space.parentId();
            }
        }
        result.addAll(managedSpaceIds(scope));
        return result;
    }
    /** 只有显式管理关系扩展到子组织；按钮权限不构成数据范围授权。 */
    public boolean canManageSpace(Scope actor,String spaceId) {
        return spaceId!=null && managedSpaceIds(actor).contains(spaceId);
    }
    public Set<String> manageableSpaceIds(Scope actor) {return Set.copyOf(managedSpaceIds(actor));}
    private Set<String> managedSpaceIds(Scope actor) {
        Map<String,Space> spaces=new HashMap<>();all().forEach(s->spaces.put(s.id(),s));
        if(actor.administrator()) return spaces.keySet();
        Set<String> managers=new HashSet<>(jdbc.queryForList("SELECT space_id FROM ps_member_space WHERE user_id=? AND manager_allowed=TRUE",String.class,actor.userId()));
        Set<String> result=new HashSet<>();
        for(Space target:spaces.values()) {
            if("PERSONAL".equals(target.type())) {if(Objects.equals(target.ownerId(),actor.userId())) result.add(target.id());continue;}
            Set<String> visited=new HashSet<>();String current=target.id();
            while(current!=null && visited.add(current)) {
                if(managers.contains(current)) {result.add(target.id());break;}
                Space space=spaces.get(current);if(space==null) break;current=space.parentId();
            }
        }
        return result;
    }
    public boolean canManageDocument(Scope actor,long owner,String spaceId,String visibility) {
        if(actor.administrator() || actor.userId()==owner) return true;
        return !"PRIVATE".equals(visibility) && canManageSpace(actor,spaceId);
    }
    public boolean canRead(Scope scope,long owner,String space,String visibility) {
        if(scope.administrator() || owner==scope.userId()) return true;
        if("PUBLIC".equals(visibility)) return true;
        return "ORGANIZATION".equals(visibility) && space!=null && visibleSpaceIds(scope).contains(space);
    }
    public void requireReadable(Scope scope,long owner,String space,String visibility) {
        if(!canRead(scope,owner,space,visibility)) throw denied();
    }
    public void requireUpload(Scope scope,String spaceId) {
        requireUploadPermission(scope,spaceId);
        if(spaceId==null||scope.administrator()||canManageSpace(scope,spaceId)||"PERSONAL".equals(get(spaceId).type()))return;
        Space space=get(spaceId);
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM ps_document WHERE user_id=? AND space_id=? AND deleted_at IS NULL",Long.class,scope.userId(),spaceId);
        if(space.uploadLimit()>=0 && count!=null && count>=space.uploadLimit()) throw new ServiceException("已达到该空间上传上限",409);
    }
    /** 复用已完成上传不会新增文档，只复核即时权限，不把已有文档算作新增额度。 */
    public void requireUploadPermission(Scope scope,String spaceId) {
        if(spaceId==null) return;
        Space space=get(spaceId);
        if(scope.administrator()) return;
        if("PERSONAL".equals(space.type())) {
            if(!Objects.equals(space.ownerId(),scope.userId())) throw denied();return;
        }
        if(canManageSpace(scope,spaceId)) return;
        Integer allowed=jdbc.queryForObject("SELECT COUNT(*) FROM ps_member_space WHERE user_id=? AND space_id=? AND upload_allowed=TRUE",Integer.class,scope.userId(),spaceId);
        if(allowed==null || allowed==0) throw denied();
    }
    public void requireUpload(Scope scope,String spaceId,long byteSize) {
        requireUpload(scope,spaceId);
        if(spaceId==null || scope.administrator() || canManageSpace(scope,spaceId)) return;
        Long max=get(spaceId).uploadMaxSizeBytes();
        if(max!=null && max>0 && byteSize>max) throw new ServiceException("文件超过该组织非管理人员单文件大小上限",409);
    }
    public void validateVisibility(String spaceId,String visibility) {
        if(visibility==null || !Set.of("PRIVATE","ORGANIZATION","PUBLIC").contains(visibility)) throw new ServiceException("可见范围无效",400);
        if("ORGANIZATION".equals(visibility) && spaceId==null) throw new ServiceException("组织文档必须选择知识空间",400);
        if(spaceId!=null && "PERSONAL".equals(get(spaceId).type()) && !"PRIVATE".equals(visibility)) throw new ServiceException("个人空间只允许仅本人文档",400);
    }
    /** 用户显式申请个人入口；GET列表和服务启动均不写入。 */
    @Transactional(transactionManager="paismartTransactionManager")
    public Space personal(Scope actor) {
        List<Long> users=jdbc.queryForList("SELECT user_id FROM sys_user WHERE user_id=? AND del_flag='0' FOR UPDATE",Long.class,actor.userId());
        if(users.isEmpty()) throw new ServiceException("平台用户不存在",404);
        List<Space> old=all().stream().filter(s->Objects.equals(s.ownerId(),actor.userId())).toList();
        String id=old.isEmpty()?UUID.randomUUID().toString():old.get(0).id();
        if(old.isEmpty()) jdbc.update("INSERT INTO ps_space(id,name,type,owner_id,upload_limit) VALUES(?,'个人知识空间','PERSONAL',?,-1)",id,actor.userId());
        Integer relation=jdbc.queryForObject("SELECT COUNT(*) FROM ps_member_space WHERE user_id=? AND space_id=?",Integer.class,actor.userId(),id);
        if(relation==null || relation==0) {
            advanceMembershipVersion(actor.userId(),null);
            Integer primary=jdbc.queryForObject("SELECT COUNT(*) FROM ps_member_space WHERE user_id=? AND primary_flag=TRUE",Integer.class,actor.userId());
            jdbc.update("INSERT INTO ps_member_space(user_id,space_id,primary_flag,upload_allowed,manager_allowed) VALUES(?,?,?,TRUE,FALSE)",actor.userId(),id,primary==null||primary==0);
        }
        return get(id);
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public Space save(Scope actor,String id,String parentId,String name,Long deptId,int limit,Long expectedVersion) {
        return save(actor,id,parentId,name,deptId,limit,expectedVersion,id==null?null:get(id).uploadMaxSizeBytes());
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public Space save(Scope actor,String id,String parentId,String name,Long deptId,int limit,Long expectedVersion,Long maxSizeBytes) {
        return save(actor,id,parentId,name,deptId,limit,expectedVersion,maxSizeBytes,null);
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public Space save(Scope actor,String id,String parentId,String name,Long deptId,int limit,Long expectedVersion,Long maxSizeBytes,String description) {
        if(description!=null && description.length()>5000) throw new ServiceException("空间简介不能超过5000字符",400);
        if(maxSizeBytes!=null && maxSizeBytes<0) throw new ServiceException("上传大小上限不能为负数",400);
        if(name==null || name.isBlank() || name.length()>120 || limit < -1) throw new ServiceException("空间名称或上传上限无效",400);
        // 锁住整棵树已有行，避免并发互换父节点形成环。
        jdbc.queryForList("SELECT id FROM ps_space ORDER BY id FOR UPDATE");
        if(id!=null && !canManageSpace(actor,id)) throw denied();
        if(parentId!=null && !canManageSpace(actor,parentId)) throw denied();
        if(id!=null && "PERSONAL".equals(get(id).type())) throw new ServiceException("个人空间不能改为组织空间",409);
        if(parentId!=null) {
            String current=parentId; Set<String> visited=new HashSet<>();
            while(current!=null) {
                if(current.equals(id) || !visited.add(current)) throw new ServiceException("知识空间不能形成父子循环",409);
                Space parent=get(current);
                if("PERSONAL".equals(parent.type())) throw new ServiceException("个人空间不能作为组织父空间",409);
                current=parent.parentId();
            }
        }
        if(id==null) {
            List<Long> users=jdbc.queryForList("SELECT user_id FROM sys_user WHERE user_id=? AND del_flag='0' FOR UPDATE",Long.class,actor.userId());
            if(users.isEmpty()) throw new ServiceException("平台用户不存在",404);
            advanceMembershipVersion(actor.userId(),null);
            id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO ps_space(id,parent_id,name,type,dept_id,upload_limit,upload_max_size_bytes,description) VALUES(?,?,?,'ORGANIZATION',?,?,?,?)",id,parentId,name.trim(),deptId,limit,maxSizeBytes,description);
            jdbc.update("INSERT INTO ps_member_space(user_id,space_id,upload_allowed,manager_allowed) VALUES(?,?,TRUE,TRUE)",actor.userId(),id);
        } else {
            if(expectedVersion==null || jdbc.update("UPDATE ps_space SET parent_id=?,name=?,dept_id=?,upload_limit=?,upload_max_size_bytes=?,description=COALESCE(?,description),row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND row_version=?",parentId,name.trim(),deptId,limit,maxSizeBytes,description,id,expectedVersion)!=1)
                throw new ServiceException("空间已被修改，请刷新",409);
        }
        return get(id);
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public void delete(Scope actor,String id) {
        if(!canManageSpace(actor,id)) throw denied();
        jdbc.queryForList("SELECT id FROM ps_space WHERE id=? FOR UPDATE",id); get(id);
        if(jdbc.queryForObject("SELECT system_key FROM ps_space WHERE id=?",String.class,id)!=null) throw new ServiceException("系统默认组织不能删除",409);
        Long refs=jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM ps_space WHERE parent_id=?) + (SELECT COUNT(*) FROM ps_member_space WHERE space_id=?) + (SELECT COUNT(*) FROM ps_document WHERE space_id=?) + (SELECT COUNT(*) FROM ps_upload WHERE space_id=? AND status='UPLOADING')",Long.class,id,id,id,id);
        if(refs!=null && refs>0) throw new ServiceException("空间仍有子空间、成员、文档或上传任务，不能删除",409);
        jdbc.update("DELETE FROM ps_space WHERE id=?",id);
    }
    public List<Member> members(Scope actor,String id) {
        if(!canManageSpace(actor,id)) throw denied(); get(id);
        return jdbc.query("SELECT * FROM ps_member_space WHERE space_id=? ORDER BY user_id",(r,n)->new Member(r.getLong("user_id"),r.getString("space_id"),r.getBoolean("primary_flag"),r.getBoolean("upload_allowed"),r.getBoolean("manager_allowed")),id);
    }
    public List<Member> memberships(long userId) {
        return jdbc.query("SELECT * FROM ps_member_space WHERE user_id=? ORDER BY space_id",(r,n)->new Member(r.getLong("user_id"),r.getString("space_id"),r.getBoolean("primary_flag"),r.getBoolean("upload_allowed"),r.getBoolean("manager_allowed")),userId);
    }
    public long membershipVersion(long userId) {
        List<Long> versions=jdbc.queryForList("SELECT membership_version FROM ps_member WHERE user_id=?",Long.class,userId);
        return versions.isEmpty()?0:versions.get(0);
    }
    /** 调用方先锁住平台目标用户行；版本记录不授予业务资格。 */
    private void advanceMembershipVersion(long userId,Long expected) {
        List<Long> versions=jdbc.queryForList("SELECT membership_version FROM ps_member WHERE user_id=?",Long.class,userId);
        long current=versions.isEmpty()?0:versions.get(0);
        if(expected!=null && (expected<0 || expected!=current)) throw new ServiceException("成员空间已被修改，请刷新",409);
        if(versions.isEmpty()) jdbc.update("INSERT INTO ps_member(user_id,status,membership_version) VALUES(?,'INELIGIBLE',0)",userId);
        if(jdbc.update("UPDATE ps_member SET membership_version=membership_version+1,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND membership_version=?",userId,current)!=1) throw new ServiceException("成员空间已被修改，请刷新",409);
    }
    /** 管理列表只显示当前actor可管理的组织；本人me另取完整关系。 */
    public List<Member> managementMemberships(Scope actor,long userId) {
        return memberships(userId).stream().filter(member -> actor.administrator() ||
            ("ORGANIZATION".equals(get(member.spaceId()).type()) && canManageSpace(actor,member.spaceId()))).toList();
    }
    /** 仅由邀请码原子消费流程调用；追加邀请指定空间，不重置既有成员关系。 */
    @Transactional(transactionManager="paismartTransactionManager")
    public void grantInvitedMembership(long userId,String spaceId) {
        if(spaceId==null) return;
        if(!"ORGANIZATION".equals(get(spaceId).type())) throw new ServiceException("邀请码只能关联组织空间",400);
        List<Long> users=jdbc.queryForList("SELECT user_id FROM sys_user WHERE user_id=? AND del_flag='0' FOR UPDATE",Long.class,userId);
        if(users.isEmpty()) throw new ServiceException("平台用户不存在",404);
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM ps_member_space WHERE user_id=? AND space_id=?",Integer.class,userId,spaceId);
        if(count!=null && count>0) return;
        advanceMembershipVersion(userId,null);
        Integer primary=jdbc.queryForObject("SELECT COUNT(*) FROM ps_member_space WHERE user_id=? AND primary_flag=TRUE",Integer.class,userId);
        jdbc.update("INSERT INTO ps_member_space(user_id,space_id,primary_flag,upload_allowed) VALUES(?,?,?,TRUE)",userId,spaceId,primary==null||primary==0);
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public void setMemberSpaces(Scope actor,long userId,List<String> spaceIds,String primarySpaceId) {
        if(!actor.administrator()) throw denied();
        if(spaceIds==null || spaceIds.size()>100) throw new ServiceException("成员空间数量无效",400);
        Set<String> unique=new LinkedHashSet<>(spaceIds);
        if(unique.size()!=spaceIds.size() || (primarySpaceId!=null&&!unique.contains(primarySpaceId))) throw new ServiceException("主空间必须属于成员，空间不能重复",400);
        List<Long> users=jdbc.queryForList("SELECT user_id FROM sys_user WHERE user_id=? AND del_flag='0' FOR UPDATE",Long.class,userId);
        if(users.isEmpty()) throw new ServiceException("平台用户不存在",404);
        for(String spaceId:unique) if(!"ORGANIZATION".equals(get(spaceId).type())) throw new ServiceException("成员分配只能选择组织空间",400);
        advanceMembershipVersion(userId,null);
        for(Member existing:memberships(userId)) if("ORGANIZATION".equals(get(existing.spaceId()).type())) jdbc.update("DELETE FROM ps_member_space WHERE user_id=? AND space_id=?",userId,existing.spaceId());
        for(String id:unique) jdbc.update("INSERT INTO ps_member_space(user_id,space_id,primary_flag,upload_allowed,manager_allowed) VALUES(?,?,?,TRUE,FALSE)",userId,id,id.equals(primarySpaceId));
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public void setMemberAssignments(Scope actor,long userId,List<Assignment> assignments,String primarySpaceId) {
        setMemberAssignments(actor,userId,assignments,primarySpaceId,null);
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public void setMemberAssignments(Scope actor,long userId,List<Assignment> assignments,String primarySpaceId,Long expectedVersion) {
        if(assignments==null || assignments.size()>100) throw new ServiceException("成员空间数量无效",400);
        Set<String> ids=new LinkedHashSet<>();
        for(Assignment assignment:assignments) {
            if(assignment==null || assignment.spaceId()==null || assignment.spaceId().isBlank()) throw new ServiceException("成员空间标识无效",400);
            if(!ids.add(assignment.spaceId())) throw new ServiceException("成员空间不能重复",400);
            if(!"ORGANIZATION".equals(get(assignment.spaceId()).type()) || !canManageSpace(actor,assignment.spaceId())) throw denied();
        }
        List<Long> users=jdbc.queryForList("SELECT user_id FROM sys_user WHERE user_id=? AND del_flag='0' FOR UPDATE",Long.class,userId);
        if(users.isEmpty()) throw new ServiceException("平台用户不存在",404);
        List<Member> old=memberships(userId);
        // 批量组织分配绝不改个人空间，平台root也不能借此删除个人关系。
        Set<String> manageable=new HashSet<>();for(Member member:old)
            if("ORGANIZATION".equals(get(member.spaceId()).type()) && canManageSpace(actor,member.spaceId())) manageable.add(member.spaceId());
        if(!actor.administrator() && manageable.isEmpty() && assignments.isEmpty()) throw denied();
        if(primarySpaceId!=null) {
            if(!ids.contains(primarySpaceId)) throw new ServiceException("主空间必须在本次成员分配中",400);
            if(!actor.administrator() && old.stream().anyMatch(m->m.primary()&&!manageable.contains(m.spaceId()))) throw new ServiceException("主空间位于当前管理范围外，不能覆盖",403);
        }
        advanceMembershipVersion(userId,expectedVersion);
        if(primarySpaceId!=null) jdbc.update("UPDATE ps_member_space SET primary_flag=FALSE WHERE user_id=?",userId);
        for(String oldId:manageable) if(!ids.contains(oldId)) jdbc.update("DELETE FROM ps_member_space WHERE user_id=? AND space_id=?",userId,oldId);
        for(Assignment a:assignments) {
            int count=jdbc.update("UPDATE ps_member_space SET upload_allowed=?,manager_allowed=? WHERE user_id=? AND space_id=?",a.uploadAllowed(),a.managerAllowed(),userId,a.spaceId());
            if(count==0) jdbc.update("INSERT INTO ps_member_space(user_id,space_id,upload_allowed,manager_allowed) VALUES(?,?,?,?)",userId,a.spaceId(),a.uploadAllowed(),a.managerAllowed());
        }
        if(primarySpaceId!=null) jdbc.update("UPDATE ps_member_space SET primary_flag=TRUE WHERE user_id=? AND space_id=?",userId,primarySpaceId);
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public void setMember(Scope actor,String id,long userId,boolean uploadAllowed,boolean remove) {
        setMember(actor,id,userId,uploadAllowed,false,remove);
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public void setMember(Scope actor,String id,long userId,boolean uploadAllowed,boolean managerAllowed,boolean remove) {
        if(!canManageSpace(actor,id)) throw denied();
        if(!"ORGANIZATION".equals(get(id).type())) throw new ServiceException("成员分配只能选择组织空间",400);
        List<Long> users=jdbc.queryForList("SELECT user_id FROM sys_user WHERE user_id=? AND del_flag='0' FOR UPDATE",Long.class,userId);
        if(users.isEmpty()) throw new ServiceException("平台用户不存在",404);
        advanceMembershipVersion(userId,null);
        if(remove) { jdbc.update("DELETE FROM ps_member_space WHERE user_id=? AND space_id=?",userId,id); return; }
        int changed=jdbc.update("UPDATE ps_member_space SET upload_allowed=?,manager_allowed=? WHERE user_id=? AND space_id=?",uploadAllowed,managerAllowed,userId,id);
        if(changed==0) jdbc.update("INSERT INTO ps_member_space(user_id,space_id,upload_allowed,manager_allowed) VALUES(?,?,?,?)",userId,id,uploadAllowed,managerAllowed);
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public void primary(Scope actor,String id) {
        // 所有主空间切换均锁平台用户行，保证一个用户最多一个主空间。
        jdbc.queryForList("SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE",actor.userId());
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM ps_member_space WHERE user_id=? AND space_id=?",Integer.class,actor.userId(),id);
        if(count==null || count==0) throw denied();
        advanceMembershipVersion(actor.userId(),null);
        jdbc.update("UPDATE ps_member_space SET primary_flag=FALSE WHERE user_id=?",actor.userId());
        jdbc.update("UPDATE ps_member_space SET primary_flag=TRUE WHERE user_id=? AND space_id=?",actor.userId(),id);
    }
}
