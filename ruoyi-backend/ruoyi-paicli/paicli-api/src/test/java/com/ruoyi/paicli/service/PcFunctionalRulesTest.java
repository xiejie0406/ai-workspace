package com.ruoyi.paicli.service;

import com.ruoyi.paicli.configuration.PcProperties;
import com.ruoyi.paicli.domain.PcException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PcFunctionalRulesTest {
    @Test void existingDatabaseHyphenIsAllowedWithoutAllowingSqlOrUriText(){PcProperties p=new PcProperties();p.setExpectedDatabase("ry-vue");assertDoesNotThrow(p::validate);for(String value:List.of("ry-vue;DROP TABLE","jdbc:mysql://fixture","ry vue","ry`vue")){p.setExpectedDatabase(value);assertThrows(IllegalStateException.class,p::validate);}}
    @Test void modelProfilePreservesTypedOriginalFlagsAndRejectsIncompatibleModes(){Map<String,Object> profile=new LinkedHashMap<>(Map.of("contextWindow",128000,"imageInput",true,"thinking","enabled","reasoningEffort","high","reasoningHistory",true,"dsml",false,"maxOutputTokens",8192));PcConnectionService.validateModelProfile(profile);assertEquals(true,profile.get("supportsImages"));profile.put("thinking","disabled");assertThrows(PcException.class,()->PcConnectionService.validateModelProfile(profile));profile.remove("reasoningEffort");profile.put("reasoningHistory","on");assertThrows(PcException.class,()->PcConnectionService.validateModelProfile(profile));profile.put("reasoningHistory",true);profile.put("maxOutputTokens",128001);assertThrows(PcException.class,()->PcConnectionService.validateModelProfile(profile));}
    @Test void omittedModelParametersRetainProviderDefaultsAndPromptPathCannotEscape(){Map<String,Object> profile=new LinkedHashMap<>();profile.put("contextWindow",128000);profile.put("thinking",null);profile.put("dsml",null);PcConnectionService.validateModelProfile(profile);assertNull(profile.get("thinking"));assertFalse(profile.containsKey("reasoningEffort"));assertEquals("agent/system.md",PcPromptService.normalize("agent\\system.md"));for(String value:List.of("../x","/x","C:/x","a//b","a/./b","a/../b"))assertThrows(PcException.class,()->PcPromptService.normalize(value));}
}
