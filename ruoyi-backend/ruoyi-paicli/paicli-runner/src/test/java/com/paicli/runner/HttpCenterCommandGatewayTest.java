package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.runtime.center.CenterCommandGateway;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HttpCenterCommandGatewayTest {
    private static MockResponse data(String body){return new MockResponse().setHeader("Content-Type","application/json").setBody("{\"code\":200,\"data\":"+body+"}");}
    private static final String SESSION="{\"id\":\"2\",\"workspaceId\":\"1\",\"runnerId\":\"3\",\"version\":7,\"mode\":\"REACT\",\"approvalMode\":\"ASK\",\"eventCursor\":12}";
    @Test void openingExistingSessionUsesRealEventCursorAndWorkspaceBinding()throws Exception{
        try(MockWebServer server=new MockWebServer()){server.start();server.enqueue(data(SESSION));
            try(var gateway=new HttpCenterCommandGateway(new CenterHttpClient(server.url("/").toString(),"jwt",false))){
                var session=gateway.open(new CenterCommandGateway.OpenSession("1","3","2"));assertEquals(12,session.lastSequence());assertEquals("ask",session.mode());
                assertEquals("Bearer jwt",server.takeRequest().getHeader("Authorization"));
                server.enqueue(data(SESSION));assertThrows(java.io.IOException.class,()->gateway.open(new CenterCommandGateway.OpenSession("9","3","2")));
            }
        }
    }
    @Test void submitPlanUsesVersionHeaderAndTaskIdInsteadOfInventedTurnId()throws Exception{
        try(MockWebServer server=new MockWebServer()){server.start();server.enqueue(data(SESSION));server.enqueue(data("{\"taskId\":\"88\",\"version\":1,\"eventCursor\":13}"));
            try(var gateway=new HttpCenterCommandGateway(new CenterHttpClient(server.url("/").toString(),"jwt",false))){
                var turn=gateway.submit("2","请修复","plan",List.of());assertEquals("88",turn.id());server.takeRequest();RecordedRequest request=server.takeRequest();
                assertEquals("7",request.getHeader("If-Match"));assertNotNull(request.getHeader("Idempotency-Key"));JsonNode body=CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());
                assertEquals("PLAN",body.path("mode").asText());assertEquals("PLAN",body.path("approvalMode").asText());assertTrue(body.path("attachments").isArray());
            }
        }
    }
    @Test void imageUploadProducesArtifactReferenceBeforeTurnSubmission()throws Exception{
        try(MockWebServer server=new MockWebServer()){server.start();server.enqueue(data(SESSION));server.enqueue(data("{\"id\":\"55\"}"));server.enqueue(data("{\"taskId\":\"88\"}"));
            try(var gateway=new HttpCenterCommandGateway(new CenterHttpClient(server.url("/").toString(),"jwt",false))){
                gateway.submit("2","看图片","ask",List.of(new CenterCommandGateway.Attachment("图.png","image/png","YQ==")));server.takeRequest();
                RecordedRequest upload=server.takeRequest();assertEquals("/paicli/v1/artifacts",upload.getPath());assertTrue(upload.getHeader("Content-Type").startsWith("multipart/form-data"));
                RecordedRequest turn=server.takeRequest();JsonNode body=CanonicalJson.MAPPER.readTree(turn.getBody().readUtf8());assertEquals("55",body.path("attachments").get(0).asText());assertFalse(body.toString().contains("YQ=="));
            }
        }
    }
    @Test void cancelChecksTaskSessionAndRealTaskVersion()throws Exception{
        try(MockWebServer server=new MockWebServer()){server.start();server.enqueue(data("{\"id\":\"88\",\"sessionId\":\"2\",\"version\":9}"));server.enqueue(data("{}"));
            try(var gateway=new HttpCenterCommandGateway(new CenterHttpClient(server.url("/").toString(),"jwt",false))){gateway.cancel("2","88");server.takeRequest();RecordedRequest request=server.takeRequest();assertEquals("/paicli/v1/tasks/88/cancel",request.getPath());assertEquals("9",request.getHeader("If-Match"));}
        }
    }
    @Test void runtimeSubmissionRejectsChangedModelAndFreezesVerifiedModel()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            server.start();server.enqueue(data(SESSION.replace("\"id\":\"2\"","\"modelId\":\"5\",\"id\":\"2\"")));
            try(var gateway=new HttpCenterCommandGateway(new CenterHttpClient(server.url("/").toString(),"jwt",false),"4")){
                assertThrows(java.io.IOException.class,()->gateway.submit("2","任务","ask",List.of()));assertEquals(1,server.getRequestCount());server.takeRequest();
                server.enqueue(data(SESSION.replace("\"id\":\"2\"","\"modelId\":\"4\",\"id\":\"2\"")));server.enqueue(data("{\"taskId\":\"88\"}"));
                gateway.submit("2","任务","ask",List.of());server.takeRequest();var request=server.takeRequest();
                assertEquals("4",CanonicalJson.MAPPER.readTree(request.getBody().readUtf8()).path("modelId").asText());assertEquals("7",request.getHeader("If-Match"));
            }
        }
    }
    @Test void artifactPagesUseUserCredentialsAndRejectScopeOrByteCursorMismatch()throws Exception{
        String page="{\"artifactId\":\"55\",\"sessionId\":\"2\",\"sha256\":\""+"a".repeat(64)+"\",\"size\":13,\"offset\":0,\"nextOffset\":4,\"hasMore\":true,\"text\":\"A中\"}";
        try(MockWebServer server=new MockWebServer()){
            server.start();server.enqueue(data(page));
            try(var gateway=new HttpCenterCommandGateway(new CenterHttpClient(server.url("/").toString(),"jwt",false))){
                var result=gateway.readArtifactText("2","55",0,5);assertEquals("A中",result.data().get("text"));
                var request=server.takeRequest();assertEquals("/paicli/v1/artifacts/55/text?sessionId=2&offset=0&limit=5",request.getPath());
                assertEquals("Bearer jwt",request.getHeader("Authorization"));assertNull(request.getHeader("X-PaiCLI-Runner-Token"));
                for(String bad:new String[]{page.replace("\"sessionId\":\"2\"","\"sessionId\":\"3\""),page.replace("\"nextOffset\":4","\"nextOffset\":5"),page.replace("\"hasMore\":true","\"hasMore\":false")}){
                    server.enqueue(data(bad));assertThrows(java.io.IOException.class,()->gateway.readArtifactText("2","55",0,5));server.takeRequest();
                }
                assertThrows(java.io.IOException.class,()->gateway.readArtifactText("2","55",-1,5));assertEquals(4,server.getRequestCount());
            }
        }
    }
    @Test void fullLedgerReadUsesFixedEndAndRejectsChangedSnapshot()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            server.start();server.enqueue(data("{\"entries\":[],\"endSequence\":9}"));
            try(var gateway=new HttpCenterCommandGateway(new CenterHttpClient(server.url("/").toString(),"jwt",false))){
                assertTrue(gateway.readLedger("2",0,9,200).successful());
                assertEquals("/paicli/v1/sessions/2/ledger?afterSequence=0&endSequence=9&limit=200",server.takeRequest().getPath());
                server.enqueue(data("{\"entries\":[],\"endSequence\":10}"));assertThrows(java.io.IOException.class,()->gateway.readLedger("2",0,9,200));
            }
        }
    }
    @Test void teamExecutionPreservesTheRealApprovalPolicyAndRestoredSessionMode()throws Exception{
        String team=SESSION.replace("\"mode\":\"REACT\"","\"mode\":\"TEAM\"").replace("\"approvalMode\":\"ASK\"","\"approvalMode\":\"AUTO\"");
        try(MockWebServer server=new MockWebServer()){
            server.start();server.enqueue(data(team));
            try(var gateway=new HttpCenterCommandGateway(new CenterHttpClient(server.url("/").toString(),"jwt",false))){
                assertEquals("team",gateway.open(new CenterCommandGateway.OpenSession("1","3","2")).mode());server.takeRequest();
                server.enqueue(data(team));server.enqueue(data("{\"taskId\":\"88\"}"));
                assertEquals("88",gateway.submit("2","团队审查任务","team",List.of()).id());server.takeRequest();
                var request=server.takeRequest();JsonNode body=CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());
                assertEquals("TEAM",body.path("mode").asText());assertEquals("AUTO",body.path("approvalMode").asText());assertEquals("7",request.getHeader("If-Match"));
            }
        }
    }
    @Test void taskEventsUseIndependentTaskCursorAndUserIdentityAcrossSessions()throws Exception{
        String first="{\"events\":[{\"taskId\":\"88\",\"sequence\":4,\"type\":\"TASK_RUNNING\",\"payload\":{\"taskId\":\"88\"}}],\"cursor\":\"4\",\"endSequence\":9,\"hasMore\":false,\"terminal\":false}";
        String second="{\"events\":[{\"taskId\":\"99\",\"sequence\":2,\"type\":\"TASK_COMPLETED\",\"payload\":{\"taskId\":\"99\"}}],\"cursor\":\"2\",\"endSequence\":2,\"hasMore\":false,\"terminal\":true}";
        try(MockWebServer server=new MockWebServer()){
            server.start();server.enqueue(data(first));server.enqueue(data(second));
            try(var gateway=new HttpCenterCommandGateway(new CenterHttpClient(server.url("/").toString(),"jwt",false))){
                var a=gateway.taskEvents("88",3,200);assertEquals(4,a.get(0).sequence());assertFalse(a.get(0).terminal());assertEquals("88",a.get(0).payload().get("taskId"));
                var request=server.takeRequest();assertEquals("/paicli/v1/tasks/88/events?after=3&limit=200",request.getPath());
                assertEquals("Bearer jwt",request.getHeader("Authorization"));assertNull(request.getHeader("X-PaiCLI-Runner-Token"));
                var b=gateway.taskEvents("99",0,10);assertEquals(2,b.get(0).sequence());assertTrue(b.get(0).terminal());
                assertEquals("/paicli/v1/tasks/99/events?after=0&limit=10",server.takeRequest().getPath());
            }
        }
    }
    @Test void taskEventsRejectOtherTaskEvidenceAndMalformedPagingWithoutPartialResults()throws Exception{
        String page="{\"events\":[{\"taskId\":\"88\",\"sequence\":4,\"type\":\"TASK_COMPLETED\",\"payload\":{\"taskId\":\"88\"}}],\"cursor\":\"4\",\"endSequence\":9,\"hasMore\":false,\"terminal\":true}";
        try(MockWebServer server=new MockWebServer()){
            server.start();
            try(var gateway=new HttpCenterCommandGateway(new CenterHttpClient(server.url("/").toString(),"jwt",false))){
                for(String invalid:new String[]{page.replace("\"taskId\":\"88\"","\"taskId\":\"99\""),page.replace("\"sequence\":4","\"sequence\":3"),page.replace("\"cursor\":\"4\"","\"cursor\":\"5\""),page.replace("\"endSequence\":9","\"endSequence\":2"),page.replace("\"hasMore\":false","\"hasMore\":\"false\""),page.replace("\"sequence\":4","\"sequence\":4.5")}){
                    server.enqueue(data(invalid));assertThrows(java.io.IOException.class,()->gateway.taskEvents("88",3,200));server.takeRequest();
                }
                server.enqueue(new MockResponse().setResponseCode(403).setHeader("Content-Type","application/json").setBody("{\"code\":403,\"msg\":\"无权限\"}"));
                assertThrows(java.io.IOException.class,()->gateway.taskEvents("88",3,200));server.takeRequest();
                assertThrows(java.io.IOException.class,()->gateway.taskEvents("88",-1,200));assertThrows(java.io.IOException.class,()->gateway.taskEvents("88",0,201));
                assertEquals(7,server.getRequestCount());
            }
        }
    }
}
