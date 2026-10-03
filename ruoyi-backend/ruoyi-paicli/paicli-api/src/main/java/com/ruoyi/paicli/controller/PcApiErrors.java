package com.ruoyi.paicli.controller;

import com.ruoyi.paicli.domain.PcException;
import com.ruoyi.common.core.domain.AjaxResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataAccessException;

@RestControllerAdvice(basePackages="com.ruoyi.paicli.controller")
public class PcApiErrors {
    @ExceptionHandler(PcException.class) public ResponseEntity<AjaxResult> business(PcException e){return ResponseEntity.status(e.status()).body(AjaxResult.error(e.status(),e.getMessage()).put("errorCode",e.code()));}
    @ExceptionHandler({org.springframework.web.bind.MissingRequestHeaderException.class,org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,IllegalArgumentException.class})
    public ResponseEntity<AjaxResult> invalid(Exception e){return ResponseEntity.badRequest().body(AjaxResult.error(400,"输入字段缺失或格式非法").put("errorCode","PC_INVALID_INPUT"));}
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<AjaxResult> database(DataAccessException e){return ResponseEntity.status(503).body(AjaxResult.error(503,"中心账本暂不可用，停止新副作用并以相同幂等键核对重试").put("errorCode","PC_LEDGER_UNAVAILABLE"));}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<AjaxResult> unavailable(IllegalStateException e){return ResponseEntity.status(503).body(AjaxResult.error(503,"中心安全配置或所需能力未就绪，请核对受控配置").put("errorCode","PC_CONFIGURATION_UNAVAILABLE"));}
}
