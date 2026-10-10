package com.ruoyi.paicli.service;
import com.ruoyi.paicli.domain.PcException;
import com.ruoyi.system.secret.ManagedSecretService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PcEmbeddingPolicyTest {
    private final PcStore store=new PcStore(mock(com.ruoyi.paicli.mapper.PcLedgerMapper.class),new PcJson(new ObjectMapper()),java.time.Clock.systemUTC());
    private Map<String,Object> config(){return new LinkedHashMap<>(Map.of("provider","openai","baseUrl","https://dashscope.aliyuncs.com/compatible-mode/v1","model","text-embedding-v4","expectedDimensions",1024,"secretRef","ai.paicli.user.1.aliyun.embedding"));}
    @Test void declaredDimensionsAndRemoteRootAreStrict(){PcEmbeddingPolicy.validate(config());for(Object dims:List.of(0,1023,8193)){Map<String,Object> c=config();c.put("expectedDimensions",dims);assertThrows(PcException.class,()->PcEmbeddingPolicy.validate(c));}for(String url:List.of("http://dashscope.aliyuncs.com/v1","https://127.0.0.2/v1","https://2130706433/v1","https://[::1]/v1","https://host/v1/embeddings","https://host/v1?key=abc")){Map<String,Object> c=config();c.put("baseUrl",url);assertThrows(PcException.class,()->PcEmbeddingPolicy.validate(c));}}
    @Test void canonicalIdentityUsesTheSameProtocolArrayAsRunner(){Map<String,Object> c=config();String expected=store.json.hash(List.of("openai","https://dashscope.aliyuncs.com/compatible-mode/v1","text-embedding-v4",1024,"code-chunker-v1"));assertEquals(expected,PcEmbeddingPolicy.fingerprint(store,c));c.put("baseUrl","https://DASHSCOPE.ALIYUNCS.COM:443/compatible-mode/./v1///");assertEquals(expected,PcEmbeddingPolicy.fingerprint(store,c));c.put("secretRef","ai.paicli.user.1.rotated.embedding");assertEquals(expected,PcEmbeddingPolicy.fingerprint(store,c));c.put("model","another-1024-model");assertNotEquals(expected,PcEmbeddingPolicy.fingerprint(store,c));}
    @Test void credentialOwnershipProjectProviderAndCapabilityMustAllMatch(){ManagedSecretService secrets=mock(ManagedSecretService.class);Map<String,Object> c=config();String ref=PcJson.text(c,"secretRef");ManagedSecretService.Metadata valid=new ManagedSecretService.Metadata(ref,"AI","paicli","aliyun","embedding","api-key","合成用途","ACTIVE",1,1,java.time.LocalDateTime.now());when(secrets.metadata("AI",ref)).thenReturn(valid);assertEquals(1,PcEmbeddingPolicy.secretVersion(secrets,c,1));assertThrows(PcException.class,()->PcEmbeddingPolicy.secretVersion(secrets,c,2));for(String project:List.of("paismart","other")){when(secrets.metadata("AI",ref)).thenReturn(new ManagedSecretService.Metadata(ref,"AI",project,"aliyun","embedding","api-key","合成用途","ACTIVE",1,1,java.time.LocalDateTime.now()));assertThrows(PcException.class,()->PcEmbeddingPolicy.secretVersion(secrets,c,1));}when(secrets.metadata("AI",ref)).thenReturn(new ManagedSecretService.Metadata(ref,"AI","paicli","aliyun","chat","api-key","合成用途","ACTIVE",1,1,java.time.LocalDateTime.now()));assertThrows(PcException.class,()->PcEmbeddingPolicy.secretVersion(secrets,c,1));verify(secrets,never()).resolve(anyString(),anyString());}
}
