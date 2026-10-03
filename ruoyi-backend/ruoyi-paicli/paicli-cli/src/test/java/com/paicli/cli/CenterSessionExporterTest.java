package com.paicli.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.runner.RunnerPrivateDirectory;
import com.paicli.runtime.center.CenterCommandGateway;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CenterSessionExporterTest {
    @Test void allFixedLedgerPagesBecomeMarkdownAndCompleteJsonWithNullMessageEvents() throws Exception {
        var gateway = new Gateway(); var initial = Map.<String,Object>of("ledgerEntries", List.of(Map.of("sequence",1,"message",Map.of("role","user","content","original user"))),"ledgerCursor",1,"endSequence",3,"ledgerHasMore",true,"rawPromptIncluded",false);
        gateway.page = Map.of("entries",List.of(Map.of("sequence",2,"event","compaction","metadata",Map.of("beforeTokens",100)),Map.of("sequence",3,"message",Map.of("role","assistant","content","actual answer"))),"cursor",3,"endSequence",3,"hasMore",false);
        Path directory=privateDirectory(); try {
            var output=CenterSessionExporter.export(gateway,"10",initial,directory);
            assertEquals(3,output.ledgerEntries()); assertEquals(2,output.messages()); assertEquals(1,gateway.calls);
            assertTrue(Files.readString(output.markdown()).contains("actual answer")); assertTrue(Files.readString(output.markdown()).contains("original user"));
            var json=new ObjectMapper().readTree(output.bundle().toFile()); assertEquals(3,json.path("ledgerEntries").size()); assertEquals("compaction",json.path("ledgerEntries").get(1).path("event").asText());
            RunnerPrivateDirectory.requireRegularFile(output.bundle()); RunnerPrivateDirectory.requireRegularFile(output.markdown());
        } finally { cleanup(directory); }
    }
    @Test void invalidFixedEndOrNoProgressDoesNotProducePartialFiles() throws Exception {
        var gateway=new Gateway(); gateway.page=Map.of("entries",List.of(Map.of("sequence",2)),"cursor",2,"endSequence",999,"hasMore",false);
        Path directory=privateDirectory(); try {
            assertThrows(IOException.class,()->CenterSessionExporter.export(gateway,"10",Map.of("ledgerCursor",1,"endSequence",3,"ledgerHasMore",true),directory));
            try(var files=Files.list(directory)){assertEquals(0,files.count());}
        } finally { cleanup(directory); }
    }
    private static Path privateDirectory()throws IOException { String home=System.getenv(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"USERPROFILE":"HOME"); return RunnerPrivateDirectory.createDirectories(Path.of(home).resolve("paicli-export-test-"+UUID.randomUUID())); }
    private static void cleanup(Path directory)throws IOException { try(var files=Files.walk(directory)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);} }
    private static final class Gateway implements CenterCommandGateway {
        Map<String,Object> page; int calls;
        public CommandResult readLedger(String session,long after,long end,int limit) { calls++;assertEquals(1,after);assertEquals(3,end);return new CommandResult(true,"page",page); }
        public Session open(OpenSession request) { throw new AssertionError(); } public Turn submit(String s,String t,String m,List<Attachment>a){throw new AssertionError();}
        public CommandResult command(String s,String n,String a){throw new AssertionError();} public List<Event> events(String s,long a,int l){throw new AssertionError();}public void cancel(String s,String t){throw new AssertionError();}
    }
}
