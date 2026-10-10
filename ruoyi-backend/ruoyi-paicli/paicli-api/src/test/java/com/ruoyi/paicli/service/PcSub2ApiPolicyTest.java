package com.ruoyi.paicli.service;
import com.ruoyi.paicli.domain.PcException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PcSub2ApiPolicyTest {
    private static Map<String,Object> provider(){return new LinkedHashMap<>(Map.of("provider","sub2api","wireApi","responses","baseUrl","https://sub2api.example/v1"));}
    @Test void protocolAndRootAreExplicitAndNotFullEndpoint(){
        PcSub2ApiPolicy.provider(provider());
        for(String root:List.of("http://sub2api.example/v1","https://sub2api.example/v1/responses","https://sub2api.example/v1?key=opaque","https://user:pass@sub2api.example/v1")){
            var p=provider();p.put("baseUrl",root);assertThrows(PcException.class,()->PcSub2ApiPolicy.provider(p));
        }
        var p=provider();p.remove("wireApi");assertThrows(PcException.class,()->PcSub2ApiPolicy.provider(p));
    }
    @Test void ordinaryModelSaveCannotManufactureProofAndVendorOverridesAreRejected(){
        var model=new LinkedHashMap<String,Object>(Map.of("capabilityKnown",true,"verification",Map.of("text",true)));
        PcSub2ApiPolicy.model(model,provider());assertFalse((Boolean)model.get("capabilityKnown"));assertFalse(model.containsKey("verification"));
        for(String field:List.of("supportsImages","dsml","reasoningHistory")){var bad=new LinkedHashMap<String,Object>();bad.put(field,true);assertThrows(PcException.class,()->PcSub2ApiPolicy.model(bad,provider()));}
    }
    @Test void everyFrozenVersionParticipatesInProof(){
        var proof=Map.of("text",true,"tools",true,"stream",true,"usage",true,"modelVersion",2,"providerVersion",3,"secretVersion",4,"wireApi","responses","model","exact-model");
        var model=Map.<String,Object>of("capabilityKnown",true,"verification",proof,"modelId","exact-model");
        assertTrue(PcSub2ApiPolicy.verified(model,2,provider(),3,4));assertFalse(PcSub2ApiPolicy.verified(model,3,provider(),3,4));
        assertFalse(PcSub2ApiPolicy.verified(model,2,provider(),4,4));assertFalse(PcSub2ApiPolicy.verified(model,2,provider(),3,5));
        var changed=provider();changed.put("wireApi","chat_completions");assertFalse(PcSub2ApiPolicy.verified(model,2,changed,3,4));
    }
    @Test void runnerMustAdvertiseTheSelectedProtocolAndLegacyProvidersStayCompatible(){
        var responses=provider();
        assertThrows(PcException.class,()->PcSub2ApiPolicy.requireRunner(responses,Map.of("capabilities",List.of("files"))));
        assertThrows(PcException.class,()->PcSub2ApiPolicy.requireRunner(responses,Map.of("capabilities",List.of("sub2api.chat_completions.v1"))));
        PcSub2ApiPolicy.requireRunner(responses,Map.of("capabilities",List.of("sub2api.responses.v1")));
        PcSub2ApiPolicy.requireRunner(Map.of("provider","deepseek"),Map.of());
    }
    @Test void bothProtocolsRejectPrivateAndAlternateLiteralHostsWithoutDnsLookup(){
        for(String protocol:List.of("responses","chat_completions")){
            for(String host:List.of("127.0.0.2","[::1]","2130706433","0x7f000001","127.1","0177.0.0.1","[fc00::1]","[fe80::1]","[::ffff:127.0.0.2]","[::127.0.0.2]","LOCALHOST.","localhost..","local.localhost","10.1.2.3","172.16.0.1","192.168.1.1","169.254.1.1","100.64.0.1","0.0.0.0")){
                var p=provider();p.put("wireApi",protocol);p.put("baseUrl","https://"+host+"/v1");assertThrows(PcException.class,()->PcSub2ApiPolicy.provider(p),protocol+" "+host);
            }
            for(String host:List.of("sub2api.example","8.8.8.8","[2606:4700:4700::1111]")){var p=provider();p.put("wireApi",protocol);p.put("baseUrl","https://"+host+"/v1");PcSub2ApiPolicy.provider(p);}
        }
    }
}
