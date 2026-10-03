package com.ruoyi.paiflow.adapter.legacy;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.WorkflowExportService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 原页面直接下载当前工作流YAML；权限和完整材料在提交附件响应前由同一中心核对。 */
@RestController
@RequestMapping("/paiflow/legacy/workflow")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyWorkflowExportController {
    private final WorkflowExportService workflows;
    public LegacyWorkflowExportController(WorkflowExportService workflows) { this.workflows=workflows; }

    @GetMapping("/export/{id}")
    public void download(@PathVariable long id,@RequestHeader(value="space-id",required=false) String space,
            HttpServletResponse response) throws IOException {
        // 导出前的验证/授权错误交既有异常处理，不提前提交200或半份YAML。
        byte[] bytes=workflows.export(id,space).bytes();
        response.setContentType("application/octet-stream");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition","attachment; filename*=UTF-8''workflow-"+id+".yaml");
        response.setHeader("Access-Control-Expose-Headers","Content-Disposition");
        response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Content-Type-Options","nosniff");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.flushBuffer();
    }

    // 原XHR附件消费者只看HTTP状态；局部映射仍使用若依AjaxResult，避免错误JSON被当YAML下载。
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<AjaxResult> rejected(ServiceException error) {
        int status=error.getCode()==null?500:error.getCode();
        if(status<400 || status>599)status=500;
        return ResponseEntity.status(status).body(AjaxResult.error(status,error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<AjaxResult> invalidId(MethodArgumentTypeMismatchException error) {
        return ResponseEntity.badRequest().body(AjaxResult.error(400,"工作流ID须为内部正整数"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<AjaxResult> denied(AccessDeniedException error) {
        return ResponseEntity.status(403).body(AjaxResult.error(403,"没有工作流导出权限"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<AjaxResult> unavailable(RuntimeException error) {
        return ResponseEntity.internalServerError().body(AjaxResult.error(500,"工作流YAML导出失败"));
    }
}
