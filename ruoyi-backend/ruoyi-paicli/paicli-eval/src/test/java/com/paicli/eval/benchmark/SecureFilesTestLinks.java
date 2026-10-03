package com.paicli.eval.benchmark;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** 测试实际路径别名：POSIX符号链接或Windows无需提权的目录junction，不跳过拒绝判定。 */
public final class SecureFilesTestLinks {
    private SecureFilesTestLinks() { }
    public static void createDirectoryLink(Path link,Path target)throws IOException {
        if(!Files.isDirectory(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("链接夹具目标须为当前测试目录");
        if(SecureFiles.hasPosix(target)){Files.createSymbolicLink(link,target);return;}
        String system=System.getenv("SystemRoot");if(system==null)throw new IOException("Windows系统目录未配置");
        String command="mklink /J \""+link.toAbsolutePath().normalize()+"\" \""+target.toAbsolutePath().normalize()+"\"";
        if(command.matches(".*[&|<>%\r\n].*"))throw new IOException("测试目录包含shell元字符");
        Process process=new ProcessBuilder(Path.of(system,"System32","cmd.exe").toString(),"/d","/c",command).redirectErrorStream(true).start();
        try { if(!process.waitFor(3,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("目录junction夹具创建超时");}if(process.exitValue()!=0)throw new IOException("目录junction夹具创建失败"); }
        catch(InterruptedException error){Thread.currentThread().interrupt();process.destroyForcibly();throw new IOException("目录junction夹具创建中断",error);}
        finally{process.destroy();}
        if(link.toAbsolutePath().normalize().equals(link.toRealPath()))throw new IOException("测试链接未形成真实重解析跳转");
    }
}
