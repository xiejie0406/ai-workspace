package com.paicli.cli;
import com.paicli.runtime.center.CenterCommandGateway;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CenterArtifactTextTest {
    @Test void completeMultibyteArtifactUsesFixedIdentityAndFinalShaAcrossAllPages()throws Exception{
        var gateway=new Gateway();var output=new ByteArrayOutputStream();CenterArtifactText.show(gateway,"10","99",new PrintStream(output,true,StandardCharsets.UTF_8));
        String actual=output.toString(StandardCharsets.UTF_8);assertTrue(actual.contains(gateway.first+gateway.second));assertTrue(actual.contains("完整产物已读取"));assertEquals(2,gateway.calls);
    }
    @Test void changedHashOrByteOffsetNeverClaimsPartialTextIsComplete()throws Exception{
        for(boolean wrongOffset:List.of(false,true)) {
            var gateway=new Gateway();gateway.badHash=!wrongOffset;gateway.badOffset=wrongOffset;var output=new ByteArrayOutputStream();
            assertThrows(IOException.class,()->CenterArtifactText.show(gateway,"10","99",new PrintStream(output,true,StandardCharsets.UTF_8)));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("尚未完整读取"));assertFalse(output.toString(StandardCharsets.UTF_8).contains("完整产物已读取"));
        }
    }
    private static class Gateway implements CenterCommandGateway {
        final String first="原中文🙂",second="\n全部实际结果";int calls;boolean badHash,badOffset;
        public CommandResult readArtifactText(String session,String artifact,long offset,int limit){
            calls++;assertEquals("10",session);assertEquals("99",artifact);assertEquals(65536,limit);
            byte[] all=(first+second).getBytes(StandardCharsets.UTF_8);String text=calls==1?first:second;long next=offset+text.getBytes(StandardCharsets.UTF_8).length;
            var data=new LinkedHashMap<String,Object>();data.put("sessionId",session);data.put("artifactId",artifact);data.put("size",all.length);data.put("sha256",badHash&&calls==2?"c".repeat(64):com.paicli.runner.CanonicalJson.sha256(all));data.put("offset",offset);data.put("nextOffset",badOffset?next+1:next);data.put("hasMore",calls==1);data.put("text",text);return new CommandResult(true,"actual",data);
        }
        public Session open(OpenSession request){throw new AssertionError();}public Turn submit(String session,String text,String mode,List<Attachment> attachments){throw new AssertionError();}
        public CommandResult command(String session,String name,String arguments){throw new AssertionError();}public List<Event> events(String session,long after,int limit){throw new AssertionError();}public void cancel(String session,String turn){throw new AssertionError();}
    }
}
