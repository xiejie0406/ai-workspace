package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.tool.ToolOutput;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class FileModificationEvidenceTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path temp;
    @Test void completeDifferenceUsesDiskContentsNotRequestedModelReplacement()throws Exception{
        Files.writeString(temp.resolve("中文.txt"),"原文\r\n保留行\r\n");
        ObjectNode args=CanonicalJson.MAPPER.createObjectNode().put("path","中文.txt").put("new_text","模型候选正文");
        var before=FileModificationEvidence.capture(temp,args);Files.writeString(temp.resolve("中文.txt"),"实际新文\r\n保留行\r\n");
        JsonNode result=before.finish();assertTrue(result.path("diff").asText().contains("-原文"));assertTrue(result.path("diff").asText().contains("+实际新文"));
        assertFalse(result.path("diff").asText().contains("模型候选正文"));assertFalse(result.has("diffUnavailable"));
    }
    @Test void newFileShowsAllLinesAndBinaryNeverMasqueradesAsAnEmptyBefore()throws Exception{
        ObjectNode args=CanonicalJson.MAPPER.createObjectNode().put("path","new.txt");var before=FileModificationEvidence.capture(temp,args);
        Files.writeString(temp.resolve("new.txt"),"一\n二\n尾行");String diff=before.finish().path("diff").asText();
        assertTrue(diff.contains("--- /dev/null"));assertTrue(diff.contains("+尾行"));assertTrue(diff.contains("No newline"));
        Files.write(temp.resolve("binary"),new byte[]{0,1,2});JsonNode unsupported=FileModificationEvidence.capture(temp,CanonicalJson.MAPPER.createObjectNode().put("path","binary")).finish();
        assertTrue(unsupported.path("diffUnavailable").asText().contains("二进制"));assertFalse(unsupported.has("diff"));
        JsonNode outside=FileModificationEvidence.capture(temp,CanonicalJson.MAPPER.createObjectNode().put("path","../outside")).finish();assertTrue(outside.has("diffUnavailable"));
    }
    @Test void receiptRetainsRealDifferenceWhileAgentKeepsOriginalToolText()throws Exception{
        Path root=RunnerPrivateDirectory.createDirectories(temp.resolve("project")),state=RunnerPrivateDirectory.createDirectories(temp.resolve("state"));
        Files.writeString(root.resolve("a.txt"),"before\n");AtomicReference<JsonNode> receipt=new AtomicReference<>();
        try(var server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){try{
                ObjectNode body=(ObjectNode)CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());
                if(request.getPath().endsWith("/proposals"))body.put("decision","PERMITTED").put("permitToken","fixture").put("expiresAt",Instant.now().plusSeconds(60).toString());
                if(request.getPath().endsWith("/consume"))body.put("consumed",true);
                if(request.getPath().endsWith("/receipts")){receipt.set(body.deepCopy());body.put("accepted",true).put("acknowledged",true).put("archived",false);}
                return new MockResponse().setHeader("Content-Type","application/json").setBody(CanonicalJson.MAPPER.createObjectNode().put("code",200).set("data",body).toString());
            }catch(Exception invalid){throw new IllegalStateException(invalid);}}});server.start();
            try(var center=new CenterHttpClient(server.url("/").toString(),"fixture",true)){
                var lease=new RunnerLease(PersonalDirectoryContextTest.claim(root,"7"));
                var registry=new CenterToolRegistry(center,lease,new ReceiptSpool(state.resolve("receipts")),root){
                    @Override protected ToolOutput executeAuthorized(String name,String arguments){try{Files.writeString(root.resolve("a.txt"),"actual\n");return ToolOutput.text("original-status");}catch(Exception failed){throw new IllegalStateException(failed);}}
                };
                ToolOutput output=registry.executeToolOutput("write_file","{\"path\":\"a.txt\",\"content\":\"model-candidate\"}");
                assertTrue(output.successful());assertEquals("original-status",output.text());
                JsonNode result=receipt.get().path("result");assertEquals("a.txt",result.path("path").asText());assertTrue(result.path("diff").asText().contains("+actual"));
                assertFalse(result.path("diff").asText().contains("model-candidate"));assertEquals("original-status",result.path("text").asText());
            }
        }
    }
    @Test void largeCompleteDifferenceUsesOneArtifactAndKeepsOriginalStatusInline()throws Exception{
        Path root=RunnerPrivateDirectory.createDirectories(temp.resolve("project")),state=RunnerPrivateDirectory.createDirectories(temp.resolve("state"));
        String old=("old-"+"a".repeat(100)+"\n").repeat(4000),actual=("new-"+"b".repeat(100)+"\n").repeat(4000)+"actual-end\n";
        Files.writeString(root.resolve("large.txt"),old);AtomicReference<JsonNode> receipt=new AtomicReference<>(),uploaded=new AtomicReference<>();
        try(var server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){try{
                ObjectNode body=(ObjectNode)CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());
                if(request.getPath().endsWith("/proposals"))body.put("decision","PERMITTED").put("permitToken","fixture").put("expiresAt",Instant.now().plusSeconds(60).toString());
                if(request.getPath().endsWith("/consume"))body.put("consumed",true);
                if(request.getPath().endsWith("/tool-outputs")){uploaded.set(body.deepCopy());body=CanonicalJson.MAPPER.createObjectNode().put("artifactId","91").put("sha256",body.path("sha256").asText()).put("size",body.path("content").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);}
                if(request.getPath().endsWith("/receipts")){receipt.set(body.deepCopy());body.put("accepted",true).put("acknowledged",true).put("archived",false);}
                return new MockResponse().setHeader("Content-Type","application/json").setBody(CanonicalJson.MAPPER.createObjectNode().put("code",200).set("data",body).toString());
            }catch(Exception invalid){throw new IllegalStateException(invalid);}}});server.start();
            try(var center=new CenterHttpClient(server.url("/").toString(),"fixture",true)){
                var lease=new RunnerLease(PersonalDirectoryContextTest.claim(root,"7"));
                var registry=new CenterToolRegistry(center,lease,new ReceiptSpool(state.resolve("receipts")),root){
                    @Override protected ToolOutput executeAuthorized(String name,String arguments){try{Files.writeString(root.resolve("large.txt"),actual);return ToolOutput.text("original-status");}catch(Exception failed){throw new IllegalStateException(failed);}}
                };
                assertEquals("original-status",registry.executeToolOutput("edit_file","{\"path\":\"large.txt\",\"old_text\":\"old\",\"new_text\":\"candidate\"}").text());
                assertNotNull(uploaded.get());assertTrue(uploaded.get().path("content").asText().contains("+actual-end"));
                JsonNode result=receipt.get().path("result");assertFalse(result.has("diff"));assertEquals("91",result.path("diffArtifact").path("artifactId").asText());
                assertEquals("original-status",result.path("text").asText());assertEquals("7",uploaded.get().path("ownerUserId").asText());
            }
        }
    }
}
