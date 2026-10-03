package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 固定历史字节，不用当前编译器的输出反向生成旧幂等预期。 */
class RunJsonBoundaryCompatibilityTest {
    private void historical(String input,String expected,String hash) {
        JSONObject value=JSON.parseObject(input);
        assertEquals(expected,RunJsonBoundary.canonical(value));
        assertEquals(hash,RunJsonBoundary.hash(value));
    }
    @Test void oldRequestNullsKeepTheOriginalHash() {
        historical("{\"request\":{\"input\":{\"value\":null,\"present\":7},\"note\":null}}",
            "{\"request\":{\"input\":{\"present\":7}}}",
            "4950fdfddabe86ff2d27775b390080599e976854a2d74dc8a84d1ce4ca3935c8");
    }
    @Test void oldEventNullsKeepTheOriginalReplayBytes() {
        historical("{\"payload\":{\"legacyFrame\":{\"choices\":[{\"finish_reason\":null}],\"workflow_step\":{\"node\":{\"finish_reason\":null,\"outputs\":{\"answer\":null,\"value\":7}}}}}}",
            "{\"payload\":{\"legacyFrame\":{\"choices\":[{}],\"workflow_step\":{\"node\":{\"outputs\":{\"value\":7}}}}}}",
            "0946e09472824f24fface9e2caf81c64de5d5603a33e91a87aa1c817f167f378");
    }
    @Test void oldResultKeepsNullArrayElementsAndOriginalHash() {
        historical("{\"state\":\"COMPLETED\",\"output\":{\"answer\":null,\"items\":[null,7]}}",
            "{\"output\":{\"items\":[null,7]},\"state\":\"COMPLETED\"}",
            "a9b18b146cb4fce42a119d3e1f6d6d4b00104e756197b798088e4725dc8ecaea");
    }
    @Test void nonRunModelCandidateDoesNotFollowWorkflowCompilerUpgrade() {
        historical("{\"modelId\":null,\"candidate\":{\"name\":\"fixture\",\"nested\":{\"unused\":null,\"enabled\":false}}}",
            "{\"candidate\":{\"name\":\"fixture\",\"nested\":{\"enabled\":false}}}",
            "178d90c689000fad10850cbdea5a30ae4d00cdd5755ed752e89ccfd4831f9511");
    }
    @Test void newEncodingDistinguishesMissingNullAndRetainsUnknownNestedOutput() {
        JSONObject value=JSON.parseObject("{\"z\":null,\"output\":{\"unknown\":null,\"items\":[null,0,false,\"\"]},\"a\":7}");
        assertEquals("{\"a\":7,\"output\":{\"items\":[null,0,false,\"\"],\"unknown\":null},\"z\":null}",RunJsonBoundary.canonicalFull(value));
        assertNotEquals(RunJsonBoundary.hashFull(value),RunJsonBoundary.hashFull(JSON.parseObject(RunJsonBoundary.canonical(value))));
        assertTrue(value.containsKey("z"));
        assertTrue(value.getJSONObject("output").containsKey("unknown"));
    }
    @Test void byteBudgetIncludesExplicitNulls() {
        JSONObject value=JSON.parseObject("{\"opaque\":null}");
        assertDoesNotThrow(()->RunJsonBoundary.check(value,15));
        assertEquals(413,assertThrows(ServiceException.class,()->RunJsonBoundary.check(value,14)).getCode());
    }
    @Test void nullOrEmptyCredentialsStayAllowedButRealNestedCredentialIsRejected() {
        assertDoesNotThrow(()->RunJsonBoundary.check(JSON.parseObject("{\"token\":null,\"nested\":{\"api_key\":\"\"}}"),128));
        assertEquals(400,assertThrows(ServiceException.class,()->RunJsonBoundary.check(JSON.parseObject("{\"nested\":[{\"access-token\":\"fixture\"}]}"),128)).getCode());
    }
}
