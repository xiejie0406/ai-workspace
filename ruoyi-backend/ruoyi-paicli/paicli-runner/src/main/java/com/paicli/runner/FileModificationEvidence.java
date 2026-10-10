package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.policy.PathGuard;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import org.eclipse.jgit.diff.*;

/** 只在许可消费后读取真实正文；不以模型content/new_text拼接修改成果。 */
final class FileModificationEvidence {
    private static final long MAX_FILE=8L*1024*1024;
    private final PathGuard guard;
    private final Path path;
    private final String displayPath;
    private final byte[] before;
    private final boolean existed;
    private final String unavailable;
    private FileModificationEvidence(PathGuard guard,Path path,String displayPath,byte[] before,boolean existed,String unavailable){this.guard=guard;this.path=path;this.displayPath=displayPath;this.before=before;this.existed=existed;this.unavailable=unavailable;}
    static FileModificationEvidence capture(Path root,JsonNode arguments){
        PathGuard guard=new PathGuard(root.toString());String requested=arguments.path("path").asText();
        try{
            Path path=guard.resolveSafe(requested);String relative=root.relativize(path).toString().replace('\\','/');
            if(relative.chars().anyMatch(c->c<32))throw new IllegalArgumentException("文件名包含无法展示的控制字符");
            boolean exists=Files.exists(path,LinkOption.NOFOLLOW_LINKS);byte[] before=exists?read(path):new byte[0];
            return new FileModificationEvidence(guard,path,relative,before,exists,null);
        }catch(Exception unsupported){return new FileModificationEvidence(guard,null,requested,null,false,unsupported.getMessage());}
    }
    ObjectNode finish(){
        ObjectNode result=CanonicalJson.MAPPER.createObjectNode().put("path",displayPath);
        if(unavailable!=null)return result.put("diffUnavailable",unavailable);
        try{
            if(!path.equals(guard.resolveSafe(displayPath)))throw new IllegalStateException("文件改动后路径身份发生变化");
            boolean exists=Files.exists(path,LinkOption.NOFOLLOW_LINKS);byte[] after=exists?read(path):new byte[0];
            result.put("beforeSha256",CanonicalJson.sha256(before)).put("afterSha256",CanonicalJson.sha256(after));
            if(java.util.Arrays.equals(before,after))return result.put("diff","").put("changed",false);
            RawText oldText=new RawText(before),newText=new RawText(after);
            var algorithm=new HistogramDiff();algorithm.setFallbackAlgorithm(null);
            var edits=algorithm.diff(RawTextComparator.DEFAULT,oldText,newText);
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            output.write(("--- "+(existed?"a/"+displayPath:"/dev/null")+"\n+++ "+(exists?"b/"+displayPath:"/dev/null")+"\n").getBytes(StandardCharsets.UTF_8));
            try(var formatter=new DiffFormatter(output)){formatter.setContext(3);formatter.format(edits,oldText,newText);formatter.flush();}
            return result.put("diff",output.toString(StandardCharsets.UTF_8)).put("changed",true);
        }catch(Exception unsupported){return result.put("diffUnavailable",unsupported.getMessage()==null?"真实文件差异无法完整读取":unsupported.getMessage());}
    }
    private static byte[] read(Path path)throws Exception{
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path))throw new IllegalArgumentException("差异仅支持真实普通文本文件");
        if(Files.size(path)>MAX_FILE)throw new IllegalArgumentException("文件超过8MB完整差异取证上限，未生成截断差异");
        byte[] bytes=Files.readAllBytes(path);if(bytes.length>MAX_FILE)throw new IllegalArgumentException("文件读取期间超过差异取证上限");
        for(byte value:bytes)if(value==0)throw new IllegalArgumentException("二进制文件无法展示文本差异");
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
        return bytes;
    }
}
