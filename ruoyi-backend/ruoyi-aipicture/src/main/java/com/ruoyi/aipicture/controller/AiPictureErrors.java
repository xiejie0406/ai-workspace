package com.ruoyi.aipicture.controller;

import com.ruoyi.aipicture.config.AiPictureEnabled;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.common.core.domain.AjaxResult;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackages="com.ruoyi.aipicture.controller") @Order(Ordered.HIGHEST_PRECEDENCE) @AiPictureEnabled
public class AiPictureErrors {
    @ExceptionHandler(AiPictureException.class)
    public ResponseEntity<AjaxResult> error(AiPictureException e) {
        var body=AjaxResult.error(e.status(),e.getMessage());body.put("errorCode",e.errorCode());body.put("retryable",e.retryable());
        if(e.currentVersion()!=null) body.put("currentVersion",e.currentVersion());return ResponseEntity.status(e.status()).body(body);
    }
}
