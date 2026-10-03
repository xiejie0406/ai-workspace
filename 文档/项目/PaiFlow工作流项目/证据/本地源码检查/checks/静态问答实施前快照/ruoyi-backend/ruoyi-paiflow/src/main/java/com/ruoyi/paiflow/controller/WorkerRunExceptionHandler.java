package com.ruoyi.paiflow.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 内部协议用实际HTTP拒绝区分失租约/不可用，仍保留若依信封。 */
@RestControllerAdvice(assignableTypes={WorkerRunController.class,WorkerCheckpointController.class,WorkerModelController.class})
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class WorkerRunExceptionHandler {
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<AjaxResult> rejected(ServiceException error) {
        int status=error.getCode()==null?500:error.getCode();if(status<400 || status>599) status=500;
        return ResponseEntity.status(status).body(AjaxResult.error(status,error.getMessage()));
    }
}
