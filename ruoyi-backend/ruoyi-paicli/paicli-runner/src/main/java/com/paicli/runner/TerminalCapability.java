package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** 网页CLI仅获得单终端短期能力。初始文件消费后删除，续期文件只允许同一私有目录。 */
final class TerminalCapability {
    final String centerUrl,terminalId,sessionId;
    final Path renewal;
    private volatile JsonNode current;
    private TerminalCapability(JsonNode value,Path path)throws IOException{
        centerUrl=required(value,"centerUrl");terminalId=id(value,"terminalId");sessionId=id(value,"sessionId");
        renewal=path.getParent().resolve("capability-renewal.json");current=value;validate(value);
    }
    static TerminalCapability fromEnvironment()throws IOException{
        return fromEnvironment(true);
    }
    static TerminalCapability observeFromEnvironment()throws IOException{return fromEnvironment(false);}
    private static TerminalCapability fromEnvironment(boolean consume)throws IOException{
        String file=System.getenv("PAICLI_TERMINAL_CAPABILITY_FILE");
        if(file==null||file.isBlank())return null;
        Path path=Path.of(file).toAbsolutePath().normalize();RunnerPrivateDirectory.requireRegularFile(path);
        if(Files.size(path)>16*1024)throw new IOException("终端能力文件超过上限");
        TerminalCapability result=new TerminalCapability(CanonicalJson.MAPPER.readTree(Files.readAllBytes(path)),path);
        if(consume){RunnerPrivateDirectory.requireRegularFile(path);Files.delete(path);}return result;
    }
    synchronized String token()throws IOException{
        if(Files.exists(renewal)){
            RunnerPrivateDirectory.requireRegularFile(renewal);
            if(Files.size(renewal)>16*1024)throw new IOException("终端续期文件超过上限");
            JsonNode value=CanonicalJson.MAPPER.readTree(Files.readAllBytes(renewal));validate(value);
            if(Instant.parse(value.path("expiresAt").asText()).isAfter(Instant.parse(current.path("expiresAt").asText())))current=value;
        }
        if(!Instant.parse(current.path("expiresAt").asText()).isAfter(Instant.now()))throw new IOException("网页终端身份已过期，请重新连接");
        return current.path("token").asText();
    }
    private void validate(JsonNode value)throws IOException{
        if(value==null||!centerUrl.equals(value.path("centerUrl").asText())||!terminalId.equals(value.path("terminalId").asText())
                ||!sessionId.equals(value.path("sessionId").asText()))throw new IOException("终端续期归属不符");
        required(value,"token");
        try{Instant.parse(required(value,"expiresAt"));}catch(RuntimeException invalid){throw new IOException("终端有效期非法",invalid);}
    }
    private static String required(JsonNode value,String key)throws IOException{
        String text=value==null?"":value.path(key).asText();if(text.isBlank()||text.indexOf('\n')>=0||text.indexOf('\r')>=0)throw new IOException("终端能力字段缺失："+key);return text;
    }
    private static String id(JsonNode value,String key)throws IOException{String id=required(value,key);if(!id.matches("[1-9][0-9]{0,18}"))throw new IOException("终端归属标识非法");return id;}
}
