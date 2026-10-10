package com.paicli.runner;

import com.paicli.runtime.center.CenterCommandGateway;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 只在port0的本机端口验证兼容传输；Gateway fixture没有模型、数据库或ToolRegistry。 */
class CenterRuntimeApiServerTest {
    private static final String KEY="fixture-runtime-key-32-bytes";
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    @Test void runtimeArgumentsPreserveOriginalHttpFlag() {
        assertEquals(8080,CenterRuntimeApiServer.parsePort(new String[]{"serve","--http"}));
        assertEquals(24081,CenterRuntimeApiServer.parsePort(new String[]{"serve","--http","--port","24081"}));
        assertEquals(24082,CenterRuntimeApiServer.parsePort(new String[]{"serve","--port","24082","--HTTP"}));
    }
    @Test void runtimeArgumentsPreserveCurrentPortForms() {
        assertEquals(8080,CenterRuntimeApiServer.parsePort(new String[]{"serve"}));
        assertEquals(24083,CenterRuntimeApiServer.parsePort(new String[]{"serve","--port","24083"}));
        assertEquals(24085,CenterRuntimeApiServer.parsePort(new String[]{"serve","--port","24084","--port","24085"}));
        // 端口范围仍由原构造边界裁决，参数提取不增加另一套限制。
        assertEquals(-1,CenterRuntimeApiServer.parsePort(new String[]{"serve","--port","-1"}));
        assertEquals(65536,CenterRuntimeApiServer.parsePort(new String[]{"serve","--port","65536"}));
    }
    @Test void runtimeArgumentsRejectMalformedInputBeforeConfiguration() {
        for(String[] args:List.of(new String[]{"serve","--unknown"},new String[]{"serve","--port"},
                new String[]{"serve","--port","not-a-number"},new String[]{"serve","--port","2147483648"},
                new String[]{"serve","--http","--port"}))
            assertThrows(IllegalArgumentException.class,()->CenterRuntimeApiServer.parsePort(args));
    }
    @Test void threadTurnAndSnapshotSseUseCenterIdsAndConfirmedEvents()throws Exception{
        FakeGateway gateway=new FakeGateway();try(var server=server(gateway)){
            server.start();assertTrue(server.address().getAddress().isLoopbackAddress());
            var thread=request(server,"POST","/v1/threads","",true);assertEquals(200,thread.statusCode());assertTrue(thread.body().contains("thread_42"));assertTrue(thread.body().contains("\"object\":\"thread\""));
            var turn=request(server,"POST","/v1/threads/thread_42/turns","{\"input\":\"第二轮依据第一轮结果\"}",true);assertEquals(202,turn.statusCode());assertTrue(turn.body().contains("turn_90"));assertEquals("第二轮依据第一轮结果",gateway.submitted);assertEquals("ask",gateway.mode);
            var events=request(server,"GET","/v1/threads/thread_42/events?after=0&limit=20","",true);assertEquals(200,events.statusCode());assertTrue(events.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
            assertTrue(events.body().contains("event: turn.started"));assertTrue(events.body().contains("event: message.delta"));assertTrue(events.body().contains("event: turn.completed"));assertTrue(events.body().contains("中心正式结果"));assertEquals("no-store",events.headers().firstValue("Cache-Control").orElse(""));
            var resumed=request(server,"GET","/v1/threads/thread_42/events?after=2","",true);assertFalse(resumed.body().contains("id: 1\n"));assertFalse(resumed.body().contains("id: 2\n"));assertTrue(resumed.body().contains("id: 3\n"));assertEquals(2,gateway.after);
        }assertTrue(gateway.closed);
    }
    @Test void missingAndWrongKeysAndUrlCredentialsCannotReachGateway()throws Exception{
        FakeGateway gateway=new FakeGateway();try(var server=server(gateway)){server.start();assertEquals(401,request(server,"POST","/v1/threads","",false).statusCode());
            HttpRequest wrong=HttpRequest.newBuilder(uri(server,"/v1/threads")).header("X-PaiCLI-API-Key","wrong-key").POST(HttpRequest.BodyPublishers.noBody()).build();assertEquals(401,client.send(wrong,HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(400,request(server,"POST","/v1/threads?token="+KEY,"",true).statusCode());assertEquals(0,gateway.opened);
            HttpRequest direct=HttpRequest.newBuilder(uri(server,"/v1/threads")).header("X-PaiCLI-API-Key",KEY).POST(HttpRequest.BodyPublishers.noBody()).build();assertEquals(200,client.send(direct,HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void inputSchemaBodyAndCursorBoundsRejectBeforeSubmit()throws Exception{
        FakeGateway gateway=new FakeGateway();try(var server=server(gateway)){server.start();
            assertEquals(400,request(server,"POST","/v1/threads/thread_42/turns","not-json",true).statusCode());
            assertEquals(400,request(server,"POST","/v1/threads/thread_42/turns","{\"input\":\"\"}",true).statusCode());
            assertEquals(400,request(server,"POST","/v1/threads/thread_42/turns","{\"input\":\"safe\",\"mode\":\"auto\",\"modelId\":\"other\"}",true).statusCode());
            assertEquals(413,request(server,"POST","/v1/threads/thread_42/turns","x".repeat(CenterRuntimeApiServer.MAX_BODY_BYTES+1),true).statusCode());
            for(String query:List.of("after=-1","after=abc","limit=0","limit=201","after=1&after=2","api_key="+KEY))assertEquals(400,request(server,"GET","/v1/threads/thread_42/events?"+query,"",true).statusCode(),query);
            assertNull(gateway.submitted);
        }
    }
    @Test void foreignThreadCannotEscapeFixedWorkspaceOrRunnerAndOriginIsRejected()throws Exception{
        FakeGateway gateway=new FakeGateway();try(var server=server(gateway)){server.start();
            assertEquals(403,request(server,"POST","/v1/threads/thread_99/turns","{\"input\":\"no\"}",true).statusCode());assertNull(gateway.submitted);
            var origin=HttpRequest.newBuilder(uri(server,"/v1/threads")).header("Authorization","Bearer "+KEY).header("Origin","https://untrusted.example").POST(HttpRequest.BodyPublishers.noBody()).build();assertEquals(403,client.send(origin,HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void cancelIsAnExplicitCenterRequestAndDoesNotClaimEffectsReverted()throws Exception{
        FakeGateway gateway=new FakeGateway();try(var server=server(gateway)){server.start();var cancel=request(server,"POST","/v1/threads/thread_42/turns/turn_90/cancel","",true);assertEquals(202,cancel.statusCode());assertEquals(List.of("42","90"),gateway.cancelled);assertTrue(cancel.body().contains("cancelling"));assertFalse(cancel.body().contains("cancelled"));}
    }
    @Test void oversizedEventAndEventTypeInjectionNeverBecomeSseOutput()throws Exception{
        FakeGateway gateway=new FakeGateway();try(var server=server(gateway)){server.start();
            gateway.events=List.of(new CenterCommandGateway.Event(1,"MESSAGE",Map.of("content","x".repeat(CenterRuntimeApiServer.MAX_EVENT_BYTES+1)),false));assertEquals(413,request(server,"GET","/v1/threads/thread_42/events","",true).statusCode());
            gateway.events=List.of(new CenterCommandGateway.Event(1,"MESSAGE\nX-Forged",Map.of(),false));assertEquals(502,request(server,"GET","/v1/threads/thread_42/events","",true).statusCode());
        }
    }
    @Test void nativeDeltasMatchOriginalContentFieldAndLedgerSnapshotsDoNotDuplicateThem()throws Exception{
        FakeGateway gateway=new FakeGateway();gateway.events=List.of(
                new CenterCommandGateway.Event(1,"MODEL_DELTA",Map.of("taskId","90","delta","真实增量"),false),
                new CenterCommandGateway.Event(2,"LEDGER_MESSAGE",Map.of("taskId","90","message",Map.of("role","assistant","content","真实增量完整结果")),false));
        try(var server=server(gateway)){server.start();var response=request(server,"GET","/v1/threads/thread_42/events","",true);
            assertEquals(200,response.statusCode());assertTrue(response.body().contains("\"content\":\"真实增量\""));assertTrue(response.body().contains("\"turn_id\":\"turn_90\""));
            assertTrue(response.body().contains("event: message.snapshot"));assertEquals(1,response.body().split("event: message.delta",-1).length-1);
        }
    }
    @Test void anotherModelOrUnknownModelCannotUseTheRuntimeKey()throws Exception{
        FakeGateway gateway=new FakeGateway();try(var server=server(gateway)){server.start();
            for(String thread:List.of("98","97"))assertEquals(403,request(server,"POST","/v1/threads/thread_"+thread+"/turns","{\"input\":\"no\"}",true).statusCode());
            assertNull(gateway.submitted);
        }
    }
    private CenterRuntimeApiServer server(FakeGateway gateway)throws Exception{return new CenterRuntimeApiServer(gateway,new CenterCommandGateway.OpenSession("1","2",null),"3",0,KEY);}
    private static URI uri(CenterRuntimeApiServer server,String path){return URI.create("http://127.0.0.1:"+server.port()+path);}
    private HttpResponse<String> request(CenterRuntimeApiServer server,String method,String path,String body,boolean authenticated)throws Exception{
        HttpRequest.Builder request=HttpRequest.newBuilder(uri(server,path)).timeout(Duration.ofSeconds(4)).header("Content-Type","application/json");if(authenticated)request.header("Authorization","Bearer "+KEY);
        request.method(method,body.isEmpty()?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static final class FakeGateway implements CenterCommandGateway{
        volatile String submitted,mode;volatile long after;volatile int opened;volatile boolean closed;List<String> cancelled=new ArrayList<>();
        List<Event> events=List.of(new Event(1,"TASK_ENQUEUED",Map.of("taskId","90"),false),new Event(2,"MESSAGE",Map.of("content","中心正式结果","taskId","90"),false),new Event(3,"TASK_COMPLETED",Map.of("taskId","90"),true));
        public Session open(OpenSession input){opened++;return new Session(input.sessionId()==null?"42":input.sessionId(),"99".equals(input.sessionId())?"other":"1","2","ask","deepseek","fixed-model","98".equals(input.sessionId())?"4":"97".equals(input.sessionId())?null:"3",0);}
        public Turn submit(String session,String text,String mode,List<Attachment> images){this.submitted=text;this.mode=mode;return new Turn("90","ENQUEUED");}
        public List<Event> events(String session,long after,int limit){this.after=after;return events.stream().filter(event->event.sequence()>after).limit(limit).toList();}
        public CommandResult command(String session,String name,String args){throw new AssertionError("原Runtime接口不接受任意命令");}
        public void cancel(String session,String turn){cancelled=List.of(session,turn);}
        public void close(){closed=true;}
    }
}
