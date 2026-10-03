package com.paicli.cli;

import com.paicli.runtime.center.CenterCommandGateway;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** 用户身份的工具产物分页展示，固定摘要及UTF-8字节进度，不能把首段称为全文。 */
final class CenterArtifactText {
    private CenterArtifactText() { }
    static void show(CenterCommandGateway gateway,String session,String artifact,PrintStream output) throws IOException {
        if (artifact == null || !artifact.matches("[0-9]+")) throw new IOException("用法: /output <当前会话产物数字ID>");
        MessageDigest digest; try { digest=MessageDigest.getInstance("SHA-256"); } catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        long offset=0,size=-1;String sha=null;
        try {
            while(true) {
                var result=gateway.readArtifactText(session,artifact,offset,64*1024);
                if(!result.successful())throw new IOException(result.message());Map<String,Object> page=result.data();
                long currentSize=number(page,"size"),next=number(page,"nextOffset");String currentSha=String.valueOf(page.get("sha256"));
                if(!artifact.equals(String.valueOf(page.get("artifactId")))||!session.equals(String.valueOf(page.get("sessionId")))||number(page,"offset")!=offset||currentSize<0||!currentSha.matches("[a-f0-9]{64}"))throw new IOException("中心产物分页身份或摘要不符");
                if(size<0){size=currentSize;sha=currentSha;}else if(size!=currentSize||!sha.equals(currentSha))throw new IOException("产物分页期间摘要或大小发生变化");
                if(!(page.get("text") instanceof String text)||!(page.get("hasMore") instanceof Boolean more))throw new IOException("产物分页正文或进度字段无效");
                byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
                if(next<offset||next>size||bytes.length!=next-offset||bytes.length>64*1024||more&&next==offset||more!=(next<size))throw new IOException("产物分页UTF-8字节进度不完整");
                digest.update(bytes);output.print(text);offset=next;
                if(!more) {if(!sha.equals(HexFormat.of().formatHex(digest.digest())))throw new IOException("完整产物SHA-256核对失败");output.println();output.println("完整产物已读取: "+artifact+"；"+size+" UTF-8字节；SHA-256: "+sha);return;}
            }
        }catch(IOException failure){output.println();output.println("结果尚未完整读取，已显示 "+offset+" UTF-8字节；不能将当前输出视为全文。");throw failure;}
    }
    private static long number(Map<String,Object> value,String key)throws IOException {if(value.get(key)instanceof Number number)return number.longValue();throw new IOException("分页缺少数值字段: "+key);}
}
