package com.ruoyi.paismart.infrastructure.intelligence;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Sub2ApiReasoningEffortTest {
    private Map<String,Object> config(){return new LinkedHashMap<>(Map.of("apiProtocol","responses","maxOutputTokens",4096));}
    private Sub2ApiTextAdapter.Prepared prepare(Map<String,Object> config,boolean stream){return Sub2ApiTextAdapter.prepare(config,"gpt-6.1-sol",List.of(Map.of("role","user","content","合成测试")),List.of(),128,stream);}
    @Test void lowIsSentForBothResponseModesAndDoesNotExpandOutputBudget(){
        var config=config();config.put("reasoningEffort","low");
        for(boolean stream:List.of(false,true)){var body=prepare(config,stream).body();assertEquals(Map.of("effort","low"),body.get("reasoning"));assertEquals(128,body.get("max_output_tokens"));assertEquals(false,body.get("store"));}
    }
    @Test void configuredBudgetStillCapsMethodBudget(){var config=config();config.put("maxOutputTokens",64);config.put("reasoningEffort","low");assertEquals(64,prepare(config,true).body().get("max_output_tokens"));}
    @Test void omittedEffortKeepsLegacyRequest(){assertFalse(prepare(config(),false).body().containsKey("reasoning"));}
    @Test void unapprovedEffortAndNonStringValuesAreRejected(){for(Object effort:Arrays.asList("medium","high","LOW"," low ","",128,true,Map.of("effort","low"),null)){var config=config();config.put("reasoningEffort",effort);assertThrows(IllegalArgumentException.class,()->prepare(config,true));}}
    @Test void effortCannotLeakIntoChatProtocol(){var config=config();config.put("apiProtocol","chat_completions");config.put("outputTokenParameter","max_tokens");config.put("reasoningEffort","low");assertThrows(IllegalArgumentException.class,()->prepare(config,true));}
    @Test void chatWithoutEffortRetainsChosenOutputParameter(){for(String parameter:List.of("max_tokens","max_completion_tokens")){var config=config();config.put("apiProtocol","chat_completions");config.put("outputTokenParameter",parameter);var prepared=prepare(config,true);assertEquals("/chat/completions",prepared.path());assertEquals(128,prepared.body().get(parameter));assertFalse(prepared.body().containsKey("reasoning"));assertFalse(prepared.body().containsKey("max_output_tokens"));}}
}
