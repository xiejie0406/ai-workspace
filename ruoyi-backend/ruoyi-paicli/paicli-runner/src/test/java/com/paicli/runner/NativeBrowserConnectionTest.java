package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.transport.McpTransport;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class NativeBrowserConnectionTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path container;
    @Test void approvedBrowserConnectBindsRealToolsAndDisconnectRestartsOnlyModeFlags() throws Exception {
        Path root=RunnerPrivateDirectory.createDirectories(container.resolve("workspace")); Path state=RunnerPrivateDirectory.createDirectories(container.resolve("state"));
        List<List<String>> starts=new ArrayList<>(); List<JsonNode> events=new ArrayList<>(); AtomicInteger grants=new AtomicInteger();
        var mcp=CanonicalJson.MAPPER.createObjectNode().put("id","5").put("directoryContextId","7").put("version",6).put("configHash","b".repeat(64)).put("trustedHash","b".repeat(64)).put("name","chrome-devtools").put("transport","STDIO").put("command","fixture-node").put("enabled",true);
        mcp.putArray("args").add("chrome-devtools-mcp").add("--viewport=900x600").add("--isolated=true");mcp.putObject("envSecretRefs");mcp.putObject("headerSecretRefs");mcp.putObject("headers");
        var browser=CanonicalJson.MAPPER.createObjectNode().put("id","8").put("version",9).put("enabled",true).put("mode","SHARED").put("endpoint","http://127.0.0.1:9333").put("mcpConfigId","5").put("mcpConfigVersion",6).put("mcpConfigHash","b".repeat(64));
        browser.set("mcpConfig",mcp);browser.putArray("modeArgs").add("--browserUrl=http://127.0.0.1:9333");browser.putArray("disconnectModeArgs").add("--isolated=true");
        try(MockWebServer server=new MockWebServer()) {
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){try{
                ObjectNode body=(ObjectNode)CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());String path=request.getPath();
                if(path.endsWith("/proposals")){body.put("decision","PERMITTED").put("permitToken","fixture").put("expiresAt",Instant.now().plusSeconds(60).toString());return ok(body);}
                if(path.endsWith("/consume")){body.put("consumed",true);return ok(body);}
                if(path.endsWith("/mcp-context")){
                    grants.incrementAndGet();ObjectNode grant=body.deepCopy();grant.put("expiresAt",Instant.now().plusSeconds(50).toString());grant.putObject("envSecretRefs");grant.putObject("headerSecretRefs");grant.putObject("secretVersions");grant.putObject("env");grant.putObject("headers");
                    ObjectNode bound=CanonicalJson.MAPPER.createObjectNode();for(String key:List.of("configId","configVersion","configHash","envSecretRefs","headerSecretRefs","secretVersions","taskId","leaseId","fencingToken","version"))bound.set(key,grant.get(key));grant.put("grantHash",CanonicalJson.hash(bound));return ok(grant);
                }
                if(path.endsWith("/events")){events.add(body.path("payload"));return ok(CanonicalJson.MAPPER.createObjectNode());}
                if(path.endsWith("/receipts")){body.put("accepted",true).put("acknowledged",true).put("archived",false);return ok(body);}
                return ok(CanonicalJson.MAPPER.createObjectNode());
            }catch(IOException e){throw new UncheckedIOException(e);}}});server.start();
            try(var center=new CenterHttpClient(server.url("/").toString(),"fixture",true);var controls=new NativeControlExecutor(center,state,null,(config,grant,tools)->{starts.add(List.copyOf(config.getArgs()));return new FixtureMcp();})) {
                for(String operation:List.of("BROWSER_CONNECT","BROWSER_DISCONNECT")) {
                    var lease=new RunnerLease(claim(operation).put("rootFingerprint",new ResourceFingerprint(root).rootFingerprint()));var tools=new CenterToolRegistry(center,lease,new ReceiptSpool(state.resolve("receipts")),root);
                    // 只注入内存 transport，不宣称 Windows 真实 STDIO 隔离可用。
                    ObjectNode context=CanonicalJson.MAPPER.createObjectNode();context.putObject("directoryContext").put("sandboxVerified",true);context.set("browserConfig",browser);context.putArray("mcpConfigs").add(mcp);
                    context.putObject("taskPayload").put("operation",operation).putObject("arguments").put("configId","8").put("configVersion",9).set("config",browser);
                    assertTrue(controls.execute(lease,tools,context).contains("READY"));controls.attachConnections(lease,tools,context);
                    assertTrue(tools.hasTool("mcp__chrome-devtools__list_pages")); assertTrue(tools.executeToolOutput("browser_status","{}").text().contains(operation.equals("BROWSER_CONNECT")?"SHARED":"ISOLATED"));
                }
                assertEquals(List.of("chrome-devtools-mcp","--viewport=900x600","--browserUrl=http://127.0.0.1:9333"),starts.get(0));
                assertEquals(List.of("chrome-devtools-mcp","--viewport=900x600","--isolated=true"),starts.get(1)); assertTrue(grants.get()>=6);assertEquals(2,events.size());assertEquals("BROWSER",events.get(0).path("kind").asText());
            }
        }
    }
    private static ObjectNode claim(String task){return CanonicalJson.MAPPER.createObjectNode().put("taskId",task.equals("BROWSER_CONNECT")?"1":"2").put("sessionId","10").put("directoryContextId","7").put("leaseId","2").put("fencingToken",3).put("version",4).put("policyVersion",1).put("rootFingerprint","a".repeat(64)).put("expiresAt",Instant.now().plusSeconds(120).toString());}
    private static MockResponse ok(JsonNode data){return new MockResponse().setHeader("Content-Type","application/json").setBody(CanonicalJson.MAPPER.createObjectNode().put("code",200).set("data",data).toString());}
    private static final class FixtureMcp implements McpTransport {
        Consumer<JsonNode> listener;public void onReceive(Consumer<JsonNode> value){listener=value;}
        public void send(JsonNode request){if(!request.has("id"))return;var response=CanonicalJson.MAPPER.createObjectNode().put("jsonrpc","2.0");response.set("id",request.get("id"));var result=response.putObject("result");
            switch(request.path("method").asText()) {case "initialize"->result.put("protocolVersion","2025-03-26").putObject("capabilities");case "tools/list"->result.putArray("tools").addObject().put("name","list_pages").put("description","actual pages").putObject("inputSchema").put("type","object");case "prompts/list"->result.putArray("prompts");default->throw new AssertionError(request.path("method").asText());}listener.accept(response);}
        public String transportName(){return "fixture";}public void close(){}
    }
}
