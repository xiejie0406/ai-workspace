package com.paicli.llm;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Sub2ApiHttpApprovalTest {
    private static final String ROOT="http://39.102.215.183:8080/v1";
    @Test void defaultClientsRejectRemoteHttpAndExplicitGrantMatchesOnlyItsRoot(){
        assertThrows(IllegalArgumentException.class,()->new Sub2ApiResponsesClient("fixture","exact-model",ROOT));
        assertThrows(IllegalArgumentException.class,()->new Sub2ApiChatClient("fixture","exact-model",ROOT));
        assertEquals(ROOT+"/responses",Sub2ApiEndpoint.endpoint(ROOT,"responses",ROOT));
        assertEquals(ROOT+"/chat/completions",Sub2ApiEndpoint.endpoint(ROOT,"chat_completions",ROOT));
        assertNotNull(new Sub2ApiResponsesClient("fixture","exact-model",ROOT,ROOT));
        assertNotNull(new Sub2ApiChatClient("fixture","exact-model",ROOT,ROOT));
        for(String bad:List.of("http://39.102.215.184:8080/v1","http://39.102.215.183:8081/v1",ROOT+"/",ROOT+"?q=x",ROOT+"#x",ROOT+"/responses","http://user@39.102.215.183:8080/v1","http://39.102.215.183:8080/a/../v1","http://39.102.215.183:8080/%76%31"))
            assertThrows(IllegalArgumentException.class,()->Sub2ApiEndpoint.endpoint(bad,"responses",ROOT),bad);
    }
}
