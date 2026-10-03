package com.ruoyi.aipicture.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ruoyi.aipicture.domain.AiPictureException;
import com.ruoyi.common.core.domain.AjaxResult;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class AiPictureErrorPriorityTest {
    MockMvc mvc;
    @BeforeEach void setup() {
        // 注册顺序刻意先放全局 handler，证明由明确的 Advice 优先级选择业务错误。
        mvc=MockMvcBuilders.standaloneSetup(new BoundaryController()).setControllerAdvice(new SharedFallback(),new AiPictureErrors()).build();
    }
    @Test void missingWorkerInputReturnsReal404AndTypedError() throws Exception {
        mvc.perform(get("/probe/missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404)).andExpect(jsonPath("$.errorCode").value("NOT_FOUND")).andExpect(jsonPath("$.retryable").value(false));
    }
    @Test void stalePublicVersionReturns409AndCurrentVersion() throws Exception {
        mvc.perform(get("/probe/conflict")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409)).andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT")).andExpect(jsonPath("$.currentVersion").value(17)).andExpect(jsonPath("$.retryable").value(true));
    }
    @Test void unrelatedRuntimeErrorContinuesToUseSharedFallback() throws Exception {
        mvc.perform(get("/probe/generic")).andExpect(status().isOk()).andExpect(jsonPath("$.msg").value("shared-fallback")).andExpect(jsonPath("$.errorCode").doesNotExist());
    }
    @RestController static class BoundaryController {
        @GetMapping("/probe/missing") Object missing() {throw AiPictureException.missing();}
        @GetMapping("/probe/conflict") Object conflict() {throw AiPictureException.conflict(17);}
        @GetMapping("/probe/generic") Object generic() {throw new IllegalStateException("ordinary");}
    }
    /** 共享若依实际 handler 同时拦截 RuntimeException/Exception，且未设置 Order。 */
    @RestControllerAdvice static class SharedFallback {
        @ExceptionHandler(RuntimeException.class) AjaxResult runtime(RuntimeException error) {return AjaxResult.error("shared-fallback");}
        @ExceptionHandler(Exception.class) AjaxResult generic(Exception error) {return AjaxResult.error("shared-fallback");}
    }
}
