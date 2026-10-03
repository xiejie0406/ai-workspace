package com.paicli.cli;

import com.paicli.runtime.center.CenterCommandGateway;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CenterCompletionDataTest {
    private static class Gateway implements CenterCommandGateway {
        int reads; boolean unavailable;
        public Session open(OpenSession request) { throw new UnsupportedOperationException(); }
        public Turn submit(String session, String text, String mode, List<Attachment> parts) { throw new AssertionError("补全不能提交任务"); }
        public CommandResult command(String session, String name, String args) throws IOException {
            reads++; if (unavailable) throw new IOException("fixture unavailable");
            return new CommandResult(true, "metadata", switch(name) {
                case "model" -> Map.of("models", List.of(Map.of("id","42","enabled",true,"modelId","registered-model\u001b\n","provider","glm","apiKey","fixture-do-not-copy"), Map.of("id","99","enabled",false), Map.of("id","101"), Map.of("id","local-alias")));
                case "config" -> Map.of("connections", List.of(Map.of("provider","glm")));
                case "mcp" -> Map.of("connections", List.of(Map.of("name","repo","status","READY","configHash","abc","trustedHash","abc","resources",List.of(Map.of("uri","repo://files","name","项目资源"))),Map.of("name","disabled","status","STOPPED","resources",List.of(Map.of("uri","repo://old")))));
                case "skill" -> Map.of("connections", List.of(Map.of("name","project-skill","content","fixture-do-not-copy")));
                default -> throw new AssertionError("只有中心只读列表" + name);
            });
        }
        public List<Event> events(String session, long after, int limit) { throw new AssertionError(); }
        public void cancel(String session, String task) { throw new AssertionError(); }
    }
    @Test void completionUsesNumericModelAndCentralNamesWithoutRequestsOnTab() {
        var gateway=new Gateway(); var completion=new CenterCompletionData(); completion.refresh(gateway,"1");
        assertTrue(values(completion,"/model ").contains("42")); assertFalse(values(completion,"/model ").contains("99"));
        assertFalse(values(completion,"/model ").contains("deepseek")); assertFalse(values(completion,"/model ").contains("local-alias"));
        assertFalse(values(completion,"/model ").contains("101"));
        assertTrue(values(completion,"/mo").stream().noneMatch(value->value.startsWith("/model ")));
        assertEquals(List.of("project-skill"),values(completion,"/skill show "));assertEquals(List.of("glm"),values(completion,"/model add "));
        assertEquals(List.of("repo://files"),values(completion,"/mcp resources repo "));assertEquals(List.of(),values(completion,"/mcp resources disabled "));
        assertEquals(4,gateway.reads);
    }
    @Test void unavailableCenterClearsOldCandidatesAndNeverFallsBackToLocalConfig() {
        var gateway=new Gateway(); var completion=new CenterCompletionData(); completion.refresh(gateway,"1");gateway.unavailable=true;completion.refresh(gateway,"1");
        assertFalse(values(completion,"/model ").contains("42"));assertTrue(values(completion,"/skill show ").isEmpty());assertEquals(8,gateway.reads);
    }
    @Test void candidateDescriptionsExcludeCredentialsAndSkillBody() {
        var completion=new CenterCompletionData();completion.refresh(new Gateway(),"1");var candidates=new ArrayList<Candidate>();
        completion.complete(null,parsed("/model "),candidates);completion.complete(null,parsed("/skill show "),candidates);
        assertTrue(candidates.stream().allMatch(c -> !c.descr().contains("fixture-do-not-copy") && c.descr().codePoints().noneMatch(Character::isISOControl)));
        assertTrue(candidates.stream().anyMatch(c -> "42".equals(c.value()) && c.descr().contains("registered-model")));
    }
    private static List<String> values(CenterCompletionData completion,String line) {var result=new ArrayList<Candidate>();completion.complete(null,parsed(line),result);return result.stream().map(Candidate::value).toList();}
    private static ParsedLine parsed(String input) {String word=input.substring(input.lastIndexOf(' ')+1);return new ParsedLine() {
        public String word(){return word;}public int wordCursor(){return word.length();}public int wordIndex(){return 0;}public List<String> words(){return List.of(word);}public String line(){return input;}public int cursor(){return input.length();}
    };}
}
