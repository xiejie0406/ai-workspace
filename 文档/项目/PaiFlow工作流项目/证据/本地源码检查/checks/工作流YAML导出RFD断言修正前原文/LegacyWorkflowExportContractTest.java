package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import com.ruoyi.paiflow.service.WorkflowExportService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 原YAML下载传输合同：实际MVC/Jackson3/Controller及共享异常处理，Service明确mock。
 * 原8e7b金样已单独实测DTO投影与下载AST；此处不证明Mapper、成员权限、源包导入或页面通过。
 * 附件完全使用Service完成的UTF8字节，不二次解析、包装LegacyResult或把未知字段当授权。
 */
class LegacyWorkflowExportContractTest {
    private static final String BASE="/paiflow/legacy/workflow/export/";
    private static final long WORKFLOW_ID=5_000_000_001L;
    private static final byte[] YAML=("flowMeta:\n  name: 中文工作流😀\n  dslVersion: v1\n"
            +"flowData:\n  nodes: []\n  edges: []\n  future:\n    explicitNull: null\n    zero: 0\n    flag: false\n")
            .getBytes(StandardCharsets.UTF_8);
    private WorkflowExportService exports;
    private LegacyWorkflowExportController controller;
    private MockMvc mvc;

    @BeforeEach void setup() {
        exports=mock(WorkflowExportService.class);
        controller=new LegacyWorkflowExportController(exports);
        var json=new JacksonJsonHttpMessageConverter(JsonMapper.builder().findAndAddModules().build());
        mvc=MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new ByteArrayHttpMessageConverter(),json).build();
    }

    private static void assertAttachment(MockHttpServletResponse response,long id,byte[] expected) {
        assertArrayEquals(expected,response.getContentAsByteArray(),"只写已完成导出字节，中文/空值不得再次转换");
        assertEquals("UTF-8",response.getCharacterEncoding());
        assertEquals("attachment; filename*=UTF-8''workflow-"+id+".yaml",response.getHeader("Content-Disposition"));
        assertEquals("Content-Disposition",response.getHeader("Access-Control-Expose-Headers"));
        assertEquals("no-store",response.getHeader("Cache-Control"));
        assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));
        assertEquals(Integer.toString(expected.length),response.getHeader("Content-Length"),"是UTF8字节数而非String长度");
        assertTrue(response.isCommitted());
    }

    @Test void namedDomainReturnsExactUtf8BinaryAndOriginalNumericAttachmentName() throws Exception {
        when(exports.export(WORKFLOW_ID,"7")).thenReturn(new WorkflowExportService.Export(YAML));
        var response=mvc.perform(get(BASE+WORKFLOW_ID).header("space-id","7"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_OCTET_STREAM))
                .andReturn().getResponse();
        assertAttachment(response,WORKFLOW_ID,YAML);
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).startsWith("flowMeta:\n"),"成功不包code/data信封");
        verify(exports).export(WORKFLOW_ID,"7");
    }

    @Test void absentAndEmptyHeadersReachTheServiceWithoutInventingNamedScope() throws Exception {
        when(exports.export(eq(WORKFLOW_ID),isNull())).thenReturn(new WorkflowExportService.Export(YAML));
        when(exports.export(WORKFLOW_ID,"")).thenReturn(new WorkflowExportService.Export(YAML));
        var absent=mvc.perform(get(BASE+WORKFLOW_ID)).andExpect(status().isOk()).andReturn().getResponse();
        var empty=mvc.perform(get(BASE+WORKFLOW_ID).header("space-id","")).andExpect(status().isOk()).andReturn().getResponse();
        assertAttachment(absent,WORKFLOW_ID,YAML);assertAttachment(empty,WORKFLOW_ID,YAML);
        verify(exports).export(eq(WORKFLOW_ID),isNull());verify(exports).export(WORKFLOW_ID,"");
    }

    @Test void queryEchoAndTeamHeadersCannotReplaceBoundPathOrSpaceHeader() throws Exception {
        when(exports.export(WORKFLOW_ID,"7")).thenReturn(new WorkflowExportService.Export(YAML));
        mvc.perform(get(BASE+WORKFLOW_ID).header("space-id","7").header("enterprise-id","9")
                .header("Authorization","Bearer FIXTURE_NOT_A_TOKEN").param("id","12").param("spaceId","13")
                .param("ownerUserId","14").param("revision","15").param("appId","UNTRUSTED_FIXTURE"))
                .andExpect(status().isOk()).andExpect(content().bytes(YAML));
        verify(exports).export(WORKFLOW_ID,"7");verifyNoMoreInteractions(exports);
        // 这里仅证明Controller选择的参数；mock不执行平台认证/域权限，不能据200认为其他域被授权。
    }

    @Test void longPathIdDoesNotRoundOrUseFlowUuidAsNumericWorkflowId() throws Exception {
        when(exports.export(eq(Long.MAX_VALUE),isNull())).thenReturn(new WorkflowExportService.Export(YAML));
        var response=mvc.perform(get(BASE+Long.MAX_VALUE)).andExpect(status().isOk()).andReturn().getResponse();
        assertAttachment(response,Long.MAX_VALUE,YAML);verify(exports).export(eq(Long.MAX_VALUE),isNull());
    }

    @Test void serviceRejectionsUseActualNon200HttpAndNeverCreateDownloadHeaders() throws Exception {
        for(int code:List.of(400,403,404,503)) {
            long id=WORKFLOW_ID+code;
            when(exports.export(id,"7")).thenThrow(new ServiceException("导出fixture拒绝",code));
            var response=mvc.perform(get(BASE+id).header("space-id","7"))
                    .andExpect(status().is(code)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse();
            assertNull(response.getHeader("Content-Disposition"));
            assertNull(response.getHeader("Access-Control-Expose-Headers"));
            assertEquals(code,JSON.parseObject(response.getContentAsString(StandardCharsets.UTF_8)).getIntValue("code"));
            assertFalse(response.getContentAsString(StandardCharsets.UTF_8).contains("flowMeta:"));
            verify(exports).export(id,"7");
        }
        // 原下载函数只看HTTP200，不能仅用200+JSON code403证明它拒绝了错误附件。
    }

    @Test void malformedAndOverflowPathRejectBeforeServiceEvenWithGlobalAdvice() throws Exception {
        for(String id:List.of("flow-uuid","9223372036854775808","1.5")) {
            var response=mvc.perform(get(BASE+id)).andExpect(status().isBadRequest()).andReturn().getResponse();
            assertNull(response.getHeader("Content-Disposition"));
        }
        verifyNoInteractions(exports);
    }

    @Test void aRejectedServiceDoesNotCommitPartialAttachmentBeforeExceptionHandling() {
        when(exports.export(WORKFLOW_ID,"7")).thenThrow(new ServiceException("导出fixture拒绝",403));
        var response=new MockHttpServletResponse();
        ServiceException error=assertThrows(ServiceException.class,()->controller.download(WORKFLOW_ID,"7",response));
        assertEquals(403,error.getCode());assertFalse(response.isCommitted());
        assertEquals(0,response.getContentAsByteArray().length);assertNull(response.getHeader("Content-Disposition"));
        assertNull(response.getContentType());
    }

    @Test void completedExportBytesAreDefensiveAtConstructionAndAccess() {
        byte[] source=YAML.clone();var value=new WorkflowExportService.Export(source);
        source[0]='x';assertArrayEquals(YAML,value.bytes());
        byte[] exposed=value.bytes();exposed[0]='y';assertArrayEquals(YAML,value.bytes());
    }
}
