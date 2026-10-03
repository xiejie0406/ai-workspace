package com.ruoyi.aippt.controller;
import com.ruoyi.aippt.domain.AipptException;
import com.ruoyi.common.core.domain.AjaxResult;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice(basePackages="com.ruoyi.aippt.controller") public class AipptExceptionHandler  {
    @ExceptionHandler(AipptException.class) public ResponseEntity<AjaxResult> business(AipptException e)  {
        AjaxResult body=new AjaxResult(e.status(),e.getMessage());
        body.put("errorCode",e.errorCode());
        body.put("correlationId",UUID.randomUUID().toString());
        body.put("currentRevision",e.currentRevision());
        body.put("retryable",e.status()==503);
        return ResponseEntity.status(e.status()).body(body);
    }
}
