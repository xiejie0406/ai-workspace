package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.paiflow.service.WorkflowDialogService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
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
 * 原调试显示历史的传输合同检查：实际Jackson3/MVC/Controller，Service明确mock。
 * 来源为冻结b948原DTO/Jackson及消费者金样；不证明本人权限、Run归属、SQL或页面通过。
 * 特别保留question/answer的JSON字符串与clear受影响行数，不把字段透传当ownership授权。
 */
class LegacyWorkflowDialogContractTest {
    private static final String BASE="/paiflow/legacy/workflow";
    private static final long WORKFLOW_ID=5_000_000_001L;
    private static final Set<String> VIEW_FIELDS=Set.of("id","uid","workflowId","question","answer","data",
            "sid","questionItem","answerItem","createTime","deleted","type","chatId");
    private WorkflowDialogService dialogs;
    private MockMvc mvc;

    @BeforeEach void setup() {
        dialogs=mock(WorkflowDialogService.class);
        var converter=new JacksonJsonHttpMessageConverter(JsonMapper.builder().findAndAddModules().build());
        mvc=MockMvcBuilders.standaloneSetup(new LegacyWorkflowDialogController(dialogs))
                .setMessageConverters(converter).build();
    }

    private static JSONObject response(MockHttpServletResponse response) throws Exception {
        return JSON.parseObject(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private static JSONObject assertEnvelope(MockHttpServletResponse response) throws Exception {
        JSONObject envelope=response(response);
        assertEquals(Set.of("code","message","desc","data"),envelope.keySet(),"沿现LegacyResult包装，不扩为分页或Run回执");
        assertEquals(0,envelope.getIntValue("code"));
        assertInstanceOf(String.class,envelope.get("message"));
        assertInstanceOf(String.class,envelope.get("desc"));
        return envelope;
    }

    @Test void savePreservesOriginalEightFieldsAndEscapedInnerJsonWithoutUnescaping() throws Exception {
        String question="[{\"name\":\"AGENT_USER_INPUT\",\"content\":\"中文\\n行\",\"required\":false},{\"name\":\"files\",\"content\":[\"a\",\"b\"]}]";
        String answer="{\"messageContent\":\"\",\"reasoningContent\":\"推理\",\"content\":\"回复\\n下一行\",\"option\":null,\"zero\":0,\"flag\":false}";
        String answerItem="\"a\\nb\"";
        var original=JSONObject.of("chatId","original-chat-group","type",1,"workflowId",Long.toString(WORKFLOW_ID),
                "sid","00000000-0000-0000-0000-000000000001","questionItem",question,
                "answerItem",answerItem,"question",question,"answer",answer);
        when(dialogs.save(any(JSONObject.class),eq("7"))).thenReturn("original-chat-group");
        var result=mvc.perform(post(BASE+"/dialog").header("space-id","7").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(original,JSONWriter.Feature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();
        var data=assertEnvelope(result).get("data");
        assertInstanceOf(String.class,data);
        assertEquals("original-chat-group",data);
        var capture=ArgumentCaptor.forClass(JSONObject.class);
        verify(dialogs).save(capture.capture(),eq("7"));
        assertEquals(original,capture.getValue());
        for(String key:List.of("question","questionItem","answer","answerItem")) {
            assertInstanceOf(String.class,capture.getValue().get(key));
            assertEquals(original.getString(key),capture.getValue().getString(key),"Controller只能解码HTTP一层JSON");
        }
        assertEquals(answerItem,capture.getValue().getString("answerItem"),"去最外quote由Service实现，Controller不提前变形");
    }

    @Test void nullableChatIdAndAbsentSpaceKeepExplicitNullDataAndOriginalJsonNullStrings() throws Exception {
        when(dialogs.save(any(JSONObject.class),isNull())).thenReturn(null);
        String body="""
                {"workflowId":5000000001,"type":1,"sid":null,"chatId":null,
                 "question":"null","questionItem":"null","answer":"null","answerItem":null}
                """;
        var result=mvc.perform(post(BASE+"/dialog").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse();
        var envelope=assertEnvelope(result);
        assertTrue(envelope.containsKey("data"),"nullable返回不能省略data字段");
        assertNull(envelope.get("data"));
        var capture=ArgumentCaptor.forClass(JSONObject.class);
        verify(dialogs).save(capture.capture(),isNull());
        assertEquals(WORKFLOW_ID,capture.getValue().getLongValue("workflowId"));
        assertEquals("null",capture.getValue().get("question"));
        assertEquals("null",capture.getValue().get("answer"));
        for(String key:List.of("sid","chatId","answerItem")) {
            assertTrue(capture.getValue().containsKey(key));
            assertNull(capture.getValue().get(key));
        }
    }

    @Test void unknownClientOwnershipFieldsReachServiceWithoutBecomingControllerAuthorization() throws Exception {
        when(dialogs.save(any(JSONObject.class),eq(""))).thenReturn("group");
        String body="""
                {"workflowId":5000000001,"type":1,"chatId":"group","question":"[]","answer":"{}",
                 "uid":"untrusted-client","ownerUserId":999,"id":123,"deleted":true,
                 "runId":"untrusted-run","future":{"nullable":null,"zero":0,"flag":false}}
                """;
        mvc.perform(post(BASE+"/dialog").header("space-id","").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        var capture=ArgumentCaptor.forClass(JSONObject.class);
        verify(dialogs).save(capture.capture(),eq(""));
        assertEquals("untrusted-client",capture.getValue().get("uid"));
        assertEquals(999,capture.getValue().getIntValue("ownerUserId"));
        assertEquals("untrusted-run",capture.getValue().get("runId"));
        assertEquals(Boolean.TRUE,capture.getValue().get("deleted"));
        assertInstanceOf(Map.class,capture.getValue().get("future"));
        Map<?,?> future=(Map<?,?>)capture.getValue().get("future");
        assertTrue(future.containsKey("nullable"));
        assertNull(future.get("nullable"));
        assertEquals(0,((Number)future.get("zero")).intValue());
        assertEquals(Boolean.FALSE,future.get("flag"));
        // 本测试的成功来自mock，不能据此声称这些客户端字段已获授权或正式写入。
    }

    @Test void listReturnsExactlyThirteenNullableFieldsNumericLongsAndDateWithoutRunBindingLeak() throws Exception {
        Date created=Date.from(Instant.parse("2026-10-03T01:02:03Z"));
        var filled=new WorkflowDialogService.View(6_000_000_002L,"fixture-owner",WORKFLOW_ID,
                "[{\"name\":\"text\",\"content\":\"提问\"}]","{\"content\":\"回复\",\"option\":null}",
                null,"00000000-0000-0000-0000-000000000001","[]","a\\nb",created,false,1,"group");
        var nullable=new WorkflowDialogService.View(null,null,null,null,null,null,null,null,null,null,null,null,null);
        when(dialogs.list(WORKFLOW_ID,1,"7")).thenReturn(List.of(filled,nullable));
        var result=mvc.perform(get(BASE+"/dialog/list").param("workflowId",Long.toString(WORKFLOW_ID))
                .param("type","1").header("space-id","7"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();
        var envelope=assertEnvelope(result);
        assertInstanceOf(JSONArray.class,envelope.get("data"));
        JSONArray values=envelope.getJSONArray("data");
        assertEquals(2,values.size());
        for(int index=0;index<2;index++)assertEquals(VIEW_FIELDS,values.getJSONObject(index).keySet());
        JSONObject value=values.getJSONObject(0);
        assertInstanceOf(Number.class,value.get("id"));
        assertInstanceOf(Number.class,value.get("workflowId"));
        assertEquals(6_000_000_002L,value.getLongValue("id"));
        assertEquals(WORKFLOW_ID,value.getLongValue("workflowId"));
        assertEquals(filled.question(),value.getString("question"));
        assertEquals(filled.answer(),value.getString("answer"));
        assertEquals("a\\nb",value.getString("answerItem"));
        assertNotNull(value.get("createTime"));
        assertTrue(value.get("createTime") instanceof String || value.get("createTime") instanceof Number,"Date为JSON标量，不暴露Date内部属性");
        assertEquals(created.getTime(),value.getDate("createTime").getTime());
        assertEquals(Boolean.FALSE,value.get("deleted"));
        assertEquals(1,value.getIntValue("type"));
        assertTrue(value.containsKey("data"));
        assertNull(value.get("data"));
        for(String key:VIEW_FIELDS)assertNull(values.getJSONObject(1).get(key),"原13字段的nullable投影必须保留");
        assertFalse(value.containsKey("runId"));
        assertFalse(value.containsKey("workflowVersionId"));
        assertFalse(value.containsKey("ownerUserId"));
        verify(dialogs).list(WORKFLOW_ID,1,"7");
    }

    @Test void listOmitsTypeAndSpaceWithoutInventingPaginationOrAChosenSpace() throws Exception {
        when(dialogs.list(WORKFLOW_ID,null,null)).thenReturn(List.of());
        var result=mvc.perform(get(BASE+"/dialog/list").param("workflowId",Long.toString(WORKFLOW_ID))
                .param("pageNum","99").param("pageSize","1"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var envelope=assertEnvelope(result);
        assertInstanceOf(JSONArray.class,envelope.get("data"));
        assertTrue(envelope.getJSONArray("data").isEmpty());
        assertFalse(envelope.containsKey("total"));
        assertFalse(envelope.containsKey("rows"));
        verify(dialogs).list(WORKFLOW_ID,null,null);
    }

    @Test void originalClearGetReturnsIntegerZeroAndTwoNotBoolean() throws Exception {
        when(dialogs.clear(WORKFLOW_ID,1,"7")).thenReturn(0,2);
        for(int expected:new int[]{0,2}) {
            var result=mvc.perform(get(BASE+"/dialog/clear").param("workflowId",Long.toString(WORKFLOW_ID))
                    .param("type","1").header("space-id","7"))
                    .andExpect(status().isOk()).andReturn().getResponse();
            var data=assertEnvelope(result).get("data");
            assertInstanceOf(Integer.class,data);
            assertEquals(expected,((Integer)data).intValue());
            assertFalse(data instanceof Boolean);
        }
        verify(dialogs,times(2)).clear(WORKFLOW_ID,1,"7");
    }

    @Test void clearOptionalTypeAndExplicitEmptySpaceArePassedUnchanged() throws Exception {
        when(dialogs.clear(WORKFLOW_ID,null,"")).thenReturn(0);
        var result=mvc.perform(get(BASE+"/dialog/clear").param("workflowId",Long.toString(WORKFLOW_ID))
                .header("space-id",""))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(0,assertEnvelope(result).getIntValue("data"));
        verify(dialogs).clear(WORKFLOW_ID,null,"");
    }

    @Test void listAndClearRequireLongQueryAndRejectMalformedTypeBeforeService() throws Exception {
        for(String route:List.of("/dialog/list","/dialog/clear")) {
            mvc.perform(get(BASE+route)).andExpect(status().isBadRequest());
            mvc.perform(get(BASE+route).param("workflowId","550e8400-e29b-41d4-a716-446655440000"))
                    .andExpect(status().isBadRequest());
            mvc.perform(get(BASE+route).param("workflowId","9223372036854775808"))
                    .andExpect(status().isBadRequest());
            mvc.perform(get(BASE+route).param("workflowId",Long.toString(WORKFLOW_ID)).param("type","true"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(dialogs);
    }

    @Test void originalHttpMethodsDoNotBecomeAlternateWriteOrReadEntrypoints() throws Exception {
        mvc.perform(get(BASE+"/dialog")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post(BASE+"/dialog/list").param("workflowId",Long.toString(WORKFLOW_ID)))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post(BASE+"/dialog/clear").param("workflowId",Long.toString(WORKFLOW_ID)))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(dialogs);
    }
}
