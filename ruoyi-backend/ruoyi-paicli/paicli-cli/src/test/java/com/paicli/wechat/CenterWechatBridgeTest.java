package com.paicli.wechat;

import com.paicli.runner.*;
import com.paicli.render.PlainRenderer;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CenterWechatBridgeTest {
    @TempDir Path temp;
    @Test void actualSenderHashAndRealTerminalMessageAreUsedWithoutSendingThinkingOrPartialFailure()throws Exception{
        for(String terminal:List.of("TASK_COMPLETED","TASK_FAILED"))try(var server=new MockWebServer()) {
            var seen=new ArrayList<String>();server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){try{
                var body=CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());var data=CanonicalJson.MAPPER.createObjectNode();
                if(request.getPath().endsWith("/turn")){seen.add(body.path("senderHash").asText());data.put("taskId","11");}
                else if(request.getPath().endsWith("/events")){var events=data.putArray("events");events.addObject().put("sequence",1).put("type","MODEL_DELTA").putObject("payload").put("presentation","THINKING_DELTA").put("delta","私有思考");events.addObject().put("sequence",2).put("type","MODEL_DELTA").putObject("payload").put("presentation","ASSISTANT_DELTA").put("delta","未完成片段");events.addObject().put("sequence",3).put("type",terminal).putObject("payload").put("message",terminal.equals("TASK_COMPLETED")?"最终实际正文":"真实失败原因");}
                return new MockResponse().setHeader("Content-Type","application/json").setBody(CanonicalJson.MAPPER.createObjectNode().put("code",200).set("data",data).toString());
            }catch(IOException error){throw new UncheckedIOException(error);}}});server.start();
            var resolved=CanonicalJson.MAPPER.createObjectNode().put("channelId","7").put("expiresAt",Instant.now().plusSeconds(90).toString());
            try(var client=new CenterHttpClient(server.url("/").toString(),"fixture",true);var bridge=new CenterWechatControl.Bridge(client,resolved,"1");var session=new CenterWechatControl.RemoteSession(bridge)){
                String result=session.submitMessage(new WechatMessage("original-id","actual-wechat-sender","context","问题",List.of())).get(5,TimeUnit.SECONDS);
                assertEquals(List.of(CanonicalJson.sha256("actual-wechat-sender".getBytes(StandardCharsets.UTF_8))),seen);assertFalse(result.contains("私有思考"));assertFalse(result.contains("未完成片段"));
                assertTrue(result.contains(terminal.equals("TASK_COMPLETED")?"最终实际正文":"真实失败原因"));if(terminal.equals("TASK_FAILED"))assertTrue(result.contains("未完成"));
            }
        }
    }
    @Test void boundHashFiltersForeignWechatSenderAndRepliesOnlyToActualMatchedSender()throws Exception{
        String allowed="actual-wechat-sender",hash=CanonicalJson.sha256(allowed.getBytes(StandardCharsets.UTF_8));List<String> recipients=new ArrayList<>();List<WechatMessage> submitted=new ArrayList<>();WechatMessageLoop[] loop=new WechatMessageLoop[1];
        var client=new IlinkClient(){int polls;public void notifyStart(WechatAccount account){}public void notifyStop(WechatAccount account){}public void sendTyping(WechatAccount account,String user,String token,int status){}public void sendText(WechatAccount account,String user,String token,String text){recipients.add(user);}public WechatUpdate getUpdates(WechatAccount account,long timeout){if(++polls==1)return new WechatUpdate(0,"","",null,List.of(new WechatMessage("unbound","foreign-sender","x","不允许的问题",List.of()),new WechatMessage("allowed",allowed,"y","实际任务",List.of())));loop[0].stop();return new WechatUpdate(0,"","",null,List.of());}};
        var session=new WechatTaskSession(){boolean completed;public boolean isRunning(){return false;}public boolean hasCompletedRun(){return completed;}public Future<String> submit(String prompt){throw new AssertionError();}public Future<String> submitMessage(WechatMessage message){submitted.add(message);completed=true;return CompletableFuture.completedFuture("实际结果");}public String awaitCurrent(){completed=false;return "实际结果";}public void cancel(){}public void clear(){}public String compact(){return "";}public void close(){}};
        var store=new WechatAccountStore(temp){public void save(WechatAccount account){throw new AssertionError("No cursor changed");}};
        var account=new WechatAccount("fixture-token","fixture-account","https://example.invalid","42",temp.toString(),"","now");
        loop[0]=new WechatMessageLoop(client,store,account,new PlainRenderer(new PrintStream(new ByteArrayOutputStream()),new BufferedReader(new StringReader(""))),sender->session,value->hash.equals(CanonicalJson.sha256(value.getBytes(StandardCharsets.UTF_8))));
        loop[0].run();assertEquals(1,submitted.size());assertEquals(allowed,submitted.get(0).fromUserId());assertEquals(List.of(allowed),recipients);
    }
}
