package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.DefaultPersonalSpaceService;
import com.ruoyi.paiflow.service.ResourceAccessService;
import com.ruoyi.paiflow.service.WorkflowService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 原空space-id的协议适配；资源、画布、权限与revision仍由唯一中心Service处理。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyWorkflowScopeFacade {
    private static final String QUERY="paiflow:workflow:query",CREATE="paiflow:workflow:create",EDIT="paiflow:workflow:edit",REMOVE="paiflow:workflow:remove";
    private final WorkflowService workflows;
    private final DefaultPersonalSpaceService defaults;
    private final ResourceAccessService access;
    public LegacyWorkflowScopeFacade(WorkflowService workflows,DefaultPersonalSpaceService defaults,ResourceAccessService access) {
        this.workflows=workflows;this.defaults=defaults;this.access=access;
    }
    /** 原list/detail/update的实际公式是显式参数优先；不是原注释声称的Header优先。 */
    private DefaultPersonalSpaceService.Selection selected(String header,Object explicit,String permission) {
        Long head=DefaultPersonalSpaceService.parse(header),value=spaceValue(explicit);
        return defaults.select(value==null?(head==null?null:head.toString()):value.toString(),null,permission);
    }
    private static Long spaceValue(Object value) {
        if(value==null || "".equals(value))return null;
        if(!(value instanceof String) && !(value instanceof Number))throw new ServiceException("空间标识无效",400);
        return DefaultPersonalSpaceService.parse(String.valueOf(value));
    }
    private long existing(DefaultPersonalSpaceService.Selection selection,String permission) {
        Long id=defaults.existing(selection,permission);
        if(id==null)throw new ServiceException("默认个人工作流不存在或不可访问",404);
        return id;
    }
    private JSONObject wire(JSONObject body,long spaceId,String permission) {
        // 标准接口保留内部正ID；原React默认入口仍接收null，不泄漏隐藏映射为具名空间。
        var space=access.requireSpace(spaceId,permission,3,false);var result=new JSONObject(body);
        result.put("spaceId",DefaultPersonalSpaceService.legacySpaceId(space));return result;
    }
    public WorkflowService.Page list(String header,Long explicit,int current,int size,String search,String flowId,int order,Integer status) {
        var selection=selected(header,explicit,QUERY);Long spaceId=defaults.existing(selection,QUERY);
        if(spaceId==null) {
            // 空页仍执行当前中心的分页/发布能力边界；不能因新用户尚无行而吞掉错误。
            if(current<1 || size<1 || size>200)throw new ServiceException("分页参数无效，每页最多200条",400);
            if(status!=null && status!=-1 && status!=0)throw new ServiceException("发布状态查询依赖尚未接入的发布事实",503);
            return new WorkflowService.Page(List.of(),0,current,size,0);
        }
        var page=workflows.list(spaceId,current,size,search,flowId,order,status);
        return new WorkflowService.Page(page.pageData().stream().map(row->wire(row,spaceId,QUERY)).toList(),page.totalCount(),page.page(),page.pageSize(),page.totalPages());
    }
    public JSONObject detail(long id,String header,Long explicit) {
        long space=existing(selected(header,explicit,QUERY),QUERY);return wire(workflows.detail(id,space),space,QUERY);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject create(JSONObject body,String header) {
        if(body==null)throw new ServiceException("工作流请求不能为空",400);
        var selection=selected(header,body.get("spaceId"),CREATE);
        // REQUIRED加入本事务；原数据校验或Mapper失败会同时回滚首次隐藏空间/owner行。
        long space=defaults.materialize(selection,CREATE);return wire(workflows.create(space,body),space,CREATE);
    }
    public JSONObject save(JSONObject body,String header,Long revision) {
        if(body==null)throw new ServiceException("工作流请求不能为空",400);
        long space=existing(selected(header,body.get("spaceId"),EDIT),EDIT);
        return wire(workflows.save(LegacyWorkflowController.id(body),space,revision,body),space,EDIT);
    }
    /** 原build用header覆盖body.spaceId；已知流程不新建隐藏默认域，也不选最新版本。 */
    public JSONObject build(JSONObject body,String header,Long revision,String buildKey) {
        if(body==null)throw new ServiceException("构建请求不能为空",400);
        long space=existing(defaults.select(header,null,EDIT),EDIT);
        return wire(workflows.debugBuild(LegacyWorkflowController.id(body),space,revision,body,"ordinary",null,buildKey),space,EDIT);
    }
    /** 原比较体仅有flowId/name/data/version，version是本次比较UUID，不是普通业务版本ID。 */
    public JSONObject comparisons(JSONObject body,String header,Long revision,String buildKey) {
        if(body==null)throw new ServiceException("比较构建请求不能为空",400);
        long space=existing(defaults.select(header,null,EDIT),EDIT);var row=workflows.requireFlow(body.getString("flowId"),space,EDIT,3,false);
        if(!(body.get("version") instanceof String comparisonId))throw new ServiceException("比较构建需要UUID version",400);
        return wire(workflows.debugBuild(row.getId(),space,revision,body,"comparison",comparisonId,buildKey),space,EDIT);
    }
    public void delete(long id,String header,Long explicit,Long revision) {
        long space=existing(selected(header,explicit,REMOVE),REMOVE);workflows.delete(id,space,revision);
    }
    public JSONObject clone(long id,String header) {
        // source必已有域。禁止预先materialize/锁Space，Agent回调将先拿专用owner guard。
        long space=existing(defaults.select(header,null,QUERY),QUERY);
        return wire(workflows.legacyClone(id,space),space,CREATE);
    }
    public WorkflowService.VersionPage versions(String flowId,String header,int current,int size) {
        long space=existing(defaults.select(header,null,QUERY),QUERY);var row=workflows.requireFlow(flowId,space,QUERY,3,false);
        var page=workflows.versions(row.getId(),space,current,size);
        return new WorkflowService.VersionPage(page.records().stream().map(version->wire(version,space,QUERY)).toList(),page.total(),page.current(),page.size(),page.pages());
    }
    private long versionSpace(JSONObject body,String header,String permission) {
        if(body==null)throw new ServiceException("工作流请求不能为空",400);
        // 原VersionService只读取header；body中的spaceId只能echo，不能改变选择。
        return existing(defaults.select(header,spaceValue(body.get("spaceId")),permission),permission);
    }
    public JSONObject createVersion(JSONObject body,String header,Long revision) {
        long space=versionSpace(body,header,EDIT);var row=workflows.requireFlow(body.getString("flowId"),space,EDIT,3,false);
        return wire(workflows.createVersion(row.getId(),space,revision,body),space,EDIT);
    }
    public JSONObject restore(JSONObject body,String header,Long revision) {
        long space=versionSpace(body,header,EDIT);var row=workflows.requireFlow(body.getString("flowId"),space,EDIT,3,false);
        return wire(workflows.restore(row.getId(),LegacyWorkflowController.id(body),space,revision),space,EDIT);
    }
    public JSONObject suggest(JSONObject body,String header) {
        long space=versionSpace(body,header,QUERY);var row=workflows.requireFlow(body.getString("flowId"),space,QUERY,3,false);
        var result=new JSONObject();result.put("workflowVersionName",workflows.suggestVersionName(row.getId()));return result;
    }
}
