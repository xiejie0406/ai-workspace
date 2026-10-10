package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.paiflow.domain.DataRecord;
import com.ruoyi.paiflow.mapper.*;
import com.ruoyi.system.service.ISysUserService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.mybatis.spring.SqlSessionTemplate;

/** 固定数据资源只暴露逻辑表列，不暴露物理库、凭据或SQL连接。 */
@Service @ConditionalOnProperty(prefix="paiflow",name={"enabled","data.enabled"},havingValue="true")
public class DataRuntimeCatalog {
    public record FixedData(String resourceId,JSONObject descriptor,DataRecord database,List<DataRecord> tables){}
    private final DataMapper data;private final SpaceMapper spaces;private final ResourceAccessService access;private final ISysUserService users;private final SysPermissionService permissions;
    private SqlSessionTemplate session;
    public DataRuntimeCatalog(DataMapper data,SpaceMapper spaces,ResourceAccessService access,ISysUserService users,SysPermissionService permissions){this.data=data;this.spaces=spaces;this.access=access;this.users=users;this.permissions=permissions;}
    @Autowired public void useCurrentSession(SqlSessionTemplate session){this.session=session;}
    private void fresh(){if(session!=null)session.clearCache();}
    public FixedData bindCurrent(long space,long id){access.requireSpace(space,DataResourceService.QUERY,3,false);var fixed=current(space,id);access.requireSpace(space,DataResourceService.QUERY,3,false);return fixed;}
    public FixedData resolvePinned(long space,long owner,String resource,JSONObject descriptor){requireOwner(space,owner,false);var ids=ids(resource);var fixed=current(space,ids[0]);if(descriptor==null || !resource.equals(fixed.resourceId()) || !RunJsonBoundary.canonicalFull(fixed.descriptor()).equals(RunJsonBoundary.canonicalFull(descriptor)))throw DataResourceService.fail("固定数据资源表列版本已变化",409);requireOwner(space,owner,false);return fixed;}
    public void requireOwner(long space,long owner,boolean edit){fresh();var user=users.selectUserById(owner);String permission=edit?DataResourceService.EDIT:DataResourceService.QUERY;
        if(user==null || !Objects.equals(user.getUserId(),owner) || !"0".equals(user.getStatus()) || !"0".equals(user.getDelFlag()))throw DataResourceService.fail("数据运行主体已失效",403);
        var grants=permissions.getMenuPermission(user);if(grants==null || (!grants.contains("*:*:*") && !grants.contains(permission)))throw DataResourceService.fail("数据运行平台权限已撤销",403);
        var s=spaces.find(space);var m=spaces.member(space,owner);if(s==null||s.getDeleted()!=0||m==null||!"active".equals(m.getStatus())||m.getRole()==null||m.getRole()<1||m.getRole()>(edit?2:3)||s.isDefaultPersonal()&&(!Objects.equals(s.getOwnerUserId(),owner)||s.getTeamId()!=null||m.getRole()!=1))throw DataResourceService.fail("数据运行空间权限已失效",403);
    }
    private FixedData current(long space,long id){fresh();var db=data.database(id);if(db==null||db.getDeleted()!=0||!Objects.equals(db.getSpaceId(),space))throw DataResourceService.fail("数据库不存在或不可访问",404);var tables=data.tables(id);var snapshot=new JSONObject();for(var table:tables){var columns=new JSONArray();for(Object f:JSON.parseArray(table.getFieldsJson()))columns.add(((JSONObject)f).getString("name"));snapshot.put(table.getName(),JSONObject.of("tableId",table.getId(),"revision",table.getRevision(),"schemaHash",sha(table.getFieldsJson()),"columns",columns,"allowedModes",new JSONArray(List.of(0,1,2,3,4)),"execEnvs",new JSONArray(List.of(1,2))));}
        var spaceRecord=spaces.find(space);if(spaceRecord==null)throw DataResourceService.fail("数据资源空间不存在",404);
        String resource="data:"+id+":revision:"+db.getRevision();var descriptor=JSONObject.of("kind","database","provider","center-data","enabled",true,"version",String.valueOf(db.getRevision()),"legacyDbId",id,"legacySpaceId",DefaultPersonalSpaceService.legacySpaceId(spaceRecord),"tables",snapshot);return new FixedData(resource,descriptor,db,List.copyOf(tables));
    }
    public static long[] ids(String resource){try{if(resource==null||!resource.matches("data:[1-9][0-9]*:revision:[0-9]+"))throw new IllegalArgumentException();var parts=resource.split(":");return new long[]{Long.parseLong(parts[1]),Long.parseLong(parts[3])};}catch(RuntimeException ex){throw DataResourceService.fail("数据资源标识无效",409);}}
    public static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
}
