package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.WorkflowService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 保留原React字符串画布、PageData分页和创建/保存字段；业务全部委托同一Service。 */
@RestController
@RequestMapping("/paiflow/legacy/workflow")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyWorkflowController {
    private final LegacyWorkflowScopeFacade workflows;
    public LegacyWorkflowController(LegacyWorkflowScopeFacade workflows) { this.workflows=workflows; }
    @GetMapping("/list")
    public LegacyResult<WorkflowService.Page> list(@RequestHeader(value="space-id",required=false) String header,@RequestParam(required=false) Long spaceId,@RequestParam(defaultValue="1") int current,@RequestParam(defaultValue="10") int pageSize,@RequestParam(required=false) String search,@RequestParam(required=false) String flowId,@RequestParam(defaultValue="2") int order,@RequestParam(required=false) Integer status) {
        return LegacyResult.success(workflows.list(header,spaceId,current,pageSize,search,flowId,order,status));
    }
    @GetMapping
    public LegacyResult<JSONObject> detail(@RequestParam long id,@RequestHeader(value="space-id",required=false) String header,@RequestParam(required=false) Long spaceId,HttpServletResponse response) {
        return result(workflows.detail(id,header,spaceId),response);
    }
    @PostMapping
    @Log(title="PaiFlow工作流",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<JSONObject> create(@RequestBody JSONObject body,@RequestHeader(value="space-id",required=false) String header,HttpServletResponse response) {
        return result(workflows.create(body,header),response);
    }
    @PutMapping
    @Log(title="PaiFlow工作流草稿",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<JSONObject> save(@RequestBody JSONObject body,@RequestHeader(value="space-id",required=false) String header,@RequestHeader(value="If-Match",required=false) String ifMatch,HttpServletResponse response) {
        return result(workflows.save(body,header,revision(body.getLong("revision"),ifMatch)),response);
    }
    @PostMapping("/build")
    @Log(title="PaiFlow调试构建",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<JSONObject> build(@RequestBody JSONObject body,@RequestHeader(value="space-id",required=false) String header,@RequestHeader(value="If-Match",required=false) String ifMatch,@RequestHeader(value="X-PaiFlow-Build-Key",required=false) String buildKey,HttpServletResponse response) {
        response.setHeader("Access-Control-Expose-Headers","ETag");
        return result(workflows.build(body,header,revision(body.getLong("revision"),ifMatch),buildKey),response);
    }
    @PostMapping("/add-comparisons")
    @Log(title="PaiFlow比较构建",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<JSONObject> comparisons(@RequestBody JSONObject body,@RequestHeader(value="space-id",required=false) String header,@RequestHeader(value="If-Match",required=false) String ifMatch,@RequestHeader(value="X-PaiFlow-Build-Key",required=false) String buildKey,HttpServletResponse response) {
        response.setHeader("Access-Control-Expose-Headers","ETag");
        return result(workflows.comparisons(body,header,revision(body.getLong("revision"),ifMatch),buildKey),response);
    }
    @DeleteMapping
    @Log(title="PaiFlow工作流",businessType=BusinessType.DELETE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<Void> delete(@RequestParam long id,@RequestParam(required=false) Long revision,@RequestParam(required=false) Long spaceId,@RequestHeader(value="space-id",required=false) String header,@RequestHeader(value="If-Match",required=false) String ifMatch) {
        workflows.delete(id,header,spaceId,revision(revision,ifMatch));return LegacyResult.success(null);
    }
    @GetMapping("/clone")
    @Log(title="PaiFlow工作流复制",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<JSONObject> copy(@RequestParam long id,@RequestHeader(value="space-id",required=false) String target,HttpServletResponse response) { return result(workflows.clone(id,target),response); }
    public static long id(JSONObject body) { Long id=body.getLong("id");if(id==null || id<1)throw new ServiceException("工作流id不能为空",400);return id; }
    public static Long optionalScope(Long header,Long parameter) { if(header!=null && parameter!=null && !header.equals(parameter))throw new ServiceException("请求中的空间标识不一致",400);Long value=header==null?parameter:header;if(value!=null && value<1)throw new ServiceException("空间标识无效",400);return value; }
    public static long scope(Long header,Long parameter) { Long value=optionalScope(header,parameter);if(value==null)throw new ServiceException("请选择工作流所属空间",400);return value; }
    public static Long revision(Long body,String header) {
        if(header==null)return body;
        if(!header.matches("\"[0-9]{1,19}\""))throw new ServiceException("If-Match必须是引号包裹的revision",400);
        Long parsed;try{parsed=Long.valueOf(header.substring(1,header.length()-1));}catch(NumberFormatException ex){throw new ServiceException("revision无效",400);}
        if(body!=null && !body.equals(parsed))throw new ServiceException("body revision与If-Match不一致",400);return parsed;
    }
    public static LegacyResult<JSONObject> result(JSONObject body,HttpServletResponse response) { response.setHeader("ETag","\""+body.getLong("revision")+"\"");return LegacyResult.success(body); }
}
