package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.PromptRecord;
import com.ruoyi.paiflow.mapper.PromptMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 原未挂载提示管理端点的中心补齐；不保存API密钥、不宣告模型或渠道发布成功。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name={"enabled","prompt-library.enabled"},havingValue="true")
public class PromptService {
    private static final String QUERY="paiflow:prompt:query",CREATE="paiflow:prompt:create",EDIT="paiflow:prompt:edit",REMOVE="paiflow:prompt:remove",CATALOG="paiflow:prompt:catalog";
    private static final Set<String> PAYLOAD=Set.of("modelConfig","promptText","promptInput");
    private final PromptMapper mapper;private final ResourceAccessService access;private final DefaultPersonalSpaceService defaults;private final IdentityMappingService identities;
    public PromptService(PromptMapper mapper,ResourceAccessService access,DefaultPersonalSpaceService defaults,IdentityMappingService identities){this.mapper=mapper;this.access=access;this.defaults=defaults;this.identities=identities;}
    public record Page(List<JSONObject> pageData,long totalCount,int page,int pageSize,long totalPages){}
    public Page list(String header,JSONObject body){
        body=request(body);int page=body.getIntValue("page",body.getIntValue("pageIndex",1)),size=body.getIntValue("pageSize",20);if(page<1||size<1||size>200)throw new ServiceException("提示词分页参数无效",400);
        var selection=defaults.select(header,body.get("spaceId"),QUERY);Long space=defaults.existing(selection,QUERY);
        if(space==null)return new Page(List.of(),0,page,size,0);
        String search=text(body.getString("search"),200,false);Long group=body.getLong("groupId");if(group!=null)group(space,group,false);
        long total=mapper.count(space,false,search,group);return new Page(mapper.list(space,false,search,group,(long)(page-1)*size,size).stream().map(this::view).toList(),total,page,size,(total+size-1)/size);
    }
    public List<JSONObject> official(){access.requirePermission(QUERY);return mapper.list(null,true,null,null,0,10000).stream().filter(row->row.kind==1).map(this::view).toList();}
    public JSONObject detail(String header,long id){return view(require(header,id,QUERY,false));}
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject create(String header,JSONObject body,boolean grouping,boolean official){
        body=request(body);validate(body);String permission=official?CATALOG:CREATE;long actor=access.requirePermission(permission);
        Long space=official?null:defaults.materialize(defaults.select(header,body.get("spaceId"),permission),permission);
        var row=new PromptRecord();row.spaceId=space;row.ownerUserId=actor;row.official=official?1:0;row.kind=grouping?2:1;row.revision=0L;row.deleted=0;
        row.name=text(body.getString("name"),200,true);row.groupId=grouping?null:body.getLong("groupId");group(space,row.groupId,official);unique(row);
        row.promptKey=UUID.randomUUID().toString();row.payloadJson=grouping?"{}":payload(new JSONObject(),body).toJSONString(JSONWriter.Feature.WriteMapNullValue);
        row.createBy=identities.current().getUsername();row.updateBy=row.createBy;
        if(mapper.insert(row)!=1||row.id==null)throw new ServiceException("提示词创建失败",500);return view(mapper.find(row.id));
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject save(String header,long id,JSONObject body){
        body=request(body);validate(body);var row=require(header,id,EDIT,true);revision(row,body);if(body.containsKey("name"))row.name=text(body.getString("name"),200,true);
        if(body.containsKey("groupId")){if(row.kind==2)throw new ServiceException("分组不支持嵌套分组",400);row.groupId=body.getLong("groupId");group(row.spaceId,row.groupId,row.official==1);}
        if(row.kind==1)row.payloadJson=payload(JSON.parseObject(row.payloadJson),body).toJSONString(JSONWriter.Feature.WriteMapNullValue);
        unique(row);row.updateBy=identities.current().getUsername();if(mapper.update(row)!=1)throw new ServiceException("提示词已变化，请刷新后重试",409);return view(mapper.find(id));
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public void delete(String header,long id,Long expected){var row=require(header,id,REMOVE,true);if(expected!=null&&!expected.equals(row.revision))throw new ServiceException("提示词revision不匹配",409);if(row.kind==2&&mapper.children(id)>0)throw new ServiceException("分组仍有提示词，请先移动或删除内容",409);row.updateBy=identities.current().getUsername();if(mapper.remove(row)!=1)throw new ServiceException("提示词已变化",409);}
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject commit(String header,long id,JSONObject body){
        body=request(body);validate(body);var row=require(header,id,EDIT,true);revision(row,body);if(row.kind!=1)throw new ServiceException("分组不能创建提示版本",400);
        row.payloadJson=payload(JSON.parseObject(row.payloadJson),body).toJSONString(JSONWriter.Feature.WriteMapNullValue);row.updateBy=identities.current().getUsername();
        long version=mapper.latest(id)+1;if(mapper.insertVersion(id,version,row.payloadJson,text(body.getString("versionDesc"),2000,false),access.requirePermission(row.official==1?CATALOG:EDIT))!=1||mapper.update(row)!=1)throw new ServiceException("提示版本保存失败",409);
        var result=view(mapper.find(id));result.put("version",version);result.put("publicationStatus","template_version_saved");return result;
    }
    public List<Map<String,Object>> versions(String header,long id){require(header,id,QUERY,false);return mapper.versions(id);}
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject revert(String header,long id,long version,Long expected){
        var row=require(header,id,EDIT,true);if(expected!=null&&!expected.equals(row.revision))throw new ServiceException("提示词revision不匹配",409);var old=mapper.version(id,version);if(old==null)throw new ServiceException("提示版本不存在",404);
        row.payloadJson=String.valueOf(old.get("payloadJson"));WorkflowJsonBoundary.check(JSON.parse(row.payloadJson),"prompt",1024*1024);row.updateBy=identities.current().getUsername();
        // 还原产生新的不可变版本，原历史不覆盖。
        if(mapper.insertVersion(id,mapper.latest(id)+1,row.payloadJson,"还原版本 "+version,access.requirePermission(row.official==1?CATALOG:EDIT))!=1||mapper.update(row)!=1)throw new ServiceException("提示词还原失败",409);return view(mapper.find(id));
    }
    private PromptRecord require(String header,long id,String permission,boolean lock){
        var row=mapper.find(id);if(row==null)throw new ServiceException("提示词不存在或不可访问",404);
        if(row.official==1){access.requirePermission(lock?CATALOG:QUERY);}else{
            Long selected=defaults.existing(defaults.select(header,null,permission),permission);if(!Objects.equals(selected,row.spaceId))throw new ServiceException("提示词不存在或不可访问",404);access.requireSpace(row.spaceId,permission,3,lock);
        }
        if(lock){var locked=mapper.lock(id);if(locked==null||!Objects.equals(row.spaceId,locked.spaceId)||!Objects.equals(row.official,locked.official))throw new ServiceException("提示词已变化",409);row=locked;access.requirePermission(row.official==1?CATALOG:permission);}return row;
    }
    private void group(Long space,Long id,boolean official){if(id==null)return;if(id<1)throw new ServiceException("分组标识无效",400);var group=mapper.find(id);if(group==null||group.kind!=2||!Objects.equals(space,group.spaceId)||group.official!=(official?1:0))throw new ServiceException("分组不存在或不属于当前空间",404);}
    private void unique(PromptRecord row){if(mapper.sameName(row.spaceId,row.official==1,row.groupId,row.name,row.id)>0)throw new ServiceException("当前分组已存在同名提示词",409);}
    private JSONObject view(PromptRecord row){var result=JSON.parseObject(row.payloadJson);result.put("id",row.id);result.put("name",row.name);result.put("promptKey",row.promptKey);result.put("type",row.kind);result.put("groupId",row.groupId);result.put("revision",row.revision);result.put("official",row.official==1);result.put("createTime",row.createTime);result.put("updateTime",row.updateTime);long latest=mapper.latest(row.id);result.put("latestVersion",latest);result.put("currentVersion",latest);if(latest>0){var versions=mapper.versions(row.id);result.put("commitTime",versions.get(0).get("commitTime"));}return result;}
    private static JSONObject payload(JSONObject existing,JSONObject body){for(String key:PAYLOAD)if(body.containsKey(key)){Object value=body.get(key);if(value instanceof String text){try{value=JSON.parse(text);}catch(RuntimeException ex){throw new ServiceException("提示字段必须为JSON对象："+key,400);}}if(!(value instanceof Map<?,?>))throw new ServiceException("提示字段必须为JSON对象："+key,400);existing.put(key,value);}existing.putIfAbsent("promptText",JSONObject.of("messageList",List.of()));existing.putIfAbsent("promptInput",JSONObject.of("variableList",List.of()));existing.putIfAbsent("modelConfig",new JSONObject());WorkflowJsonBoundary.check(existing,"prompt",1024*1024);return existing;}
    private static void validate(JSONObject body){WorkflowJsonBoundary.check(body,"prompt/request",1024*1024);for(String key:Set.of("uid","ownerUserId","promptKey","official","deleted","createBy","updateBy","latestVersion","currentVersion"))if(body.containsKey(key))throw new ServiceException("提示词受控字段不能由请求写入："+key,400);}
    private static JSONObject request(JSONObject body){return body==null?new JSONObject():JSON.parseObject(body.toJSONString(JSONWriter.Feature.WriteMapNullValue));}
    private static void revision(PromptRecord row,JSONObject body){Long expected=body.getLong("revision");if(expected!=null&&!expected.equals(row.revision))throw new ServiceException("提示词revision不匹配",409);}
    private static String text(String value,int max,boolean required){if(value==null||value.isBlank()){if(required)throw new ServiceException("提示词名称不能为空",400);return null;}if(value.length()>max)throw new ServiceException("提示词字段超长",400);return value.trim();}
}
