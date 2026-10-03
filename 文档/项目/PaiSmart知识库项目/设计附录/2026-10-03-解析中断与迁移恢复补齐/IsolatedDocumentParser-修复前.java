package com.ruoyi.paismart.infrastructure.intelligence;
import com.ruoyi.paismart.infrastructure.Jsons;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** 固定worker类、无shell、受限临时文件、字节/页/时间预算；进程按显式配置启动。 */
@Component
@ConditionalOnProperty(name="paismart.enabled",havingValue="true")
public class IsolatedDocumentParser {
    public record Page(int number,String text){}
    private final com.ruoyi.paismart.application.intelligence.OcrService ocr;private final IntelligenceSettings settings;private final Semaphore permits;
    public IsolatedDocumentParser(IntelligenceSettings settings,com.ruoyi.paismart.application.intelligence.OcrService ocr){this.ocr=ocr;this.settings=settings;this.permits=new Semaphore(Math.max(1,settings.number("parse.concurrency",2)),true);}
    public List<Page> parse(InputStream input,String mime,com.ruoyi.paismart.application.jobs.JobService.Job job)throws IOException {
        if(!settings.enabled("parse.process-enabled"))throw new IllegalStateException("解析独立进程未启用");
        if(Runtime.getRuntime().freeMemory()/(double)Math.max(1,Runtime.getRuntime().totalMemory())<0.1)throw new IllegalStateException("JOB_BUSY:解析内存背压");
        boolean acquired=false;Path dir=null;Process process=null;
        try{acquired=permits.tryAcquire(10,TimeUnit.SECONDS);if(!acquired)throw new IllegalStateException("JOB_BUSY:解析并发达到上限");dir=Files.createTempDirectory("paismart-parse-");Path source=dir.resolve("input"),output=dir.resolve("output.json"),log=dir.resolve("process.log");
            try(OutputStream out=Files.newOutputStream(source)){long max=settings.number("parse.max-bytes",100*1024*1024),count=0;byte[] buf=new byte[8192];int n;while((n=input.read(buf))>=0){if((count+=n)>max)throw new IllegalArgumentException("文档超过解析字节上限");out.write(buf,0,n);}}
            List<String> cmd=new ArrayList<>();boolean lite="application/pdf".equals(mime)&&"liteparse".equals(settings.text("parse.pdf-engine","liteparse"));
            if(lite){Path exe=Path.of(settings.text("parse.liteparse-command",""));if(!exe.isAbsolute()||!Files.isRegularFile(exe))throw new IllegalStateException("LiteParse未配置绝对可执行文件路径");cmd.addAll(List.of(exe.toString(),"parse",source.toString(),"--format","json","--output",output.toString(),"--max-pages",String.valueOf(settings.number("parse.max-pages",1000)),"--dpi",String.valueOf(Values.range(settings.number("parse.dpi",150),150,50,300)),"--num-workers",String.valueOf(Values.range(settings.number("parse.num-workers",1),1,1,4)),"--quiet"));if(settings.enabled("parse.ocr-enabled"))cmd.addAll(List.of("--ocr-language",settings.text("parse.ocr-language","chi_sim+eng")));else cmd.add("--no-ocr");String callback=ocr.callback(job);if(!callback.isBlank())cmd.addAll(List.of("--ocr-server-url",callback));}
            else{String cp=settings.text("parse.worker-classpath","");if(cp.isBlank())throw new IllegalStateException("解析worker classpath尚未配置");Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");cmd.addAll(List.of(java.toString(),"-Xmx256m","-Djava.io.tmpdir="+dir,"-cp",cp,ParserWorker.class.getName(),source.toString(),output.toString(),String.valueOf(settings.number("parse.max-pages",1000)),String.valueOf(settings.number("parse.max-characters",4*1024*1024))));}
            ProcessBuilder builder=new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());Map<String,String> environment=builder.environment();String path=environment.get("PATH");environment.clear();if(path!=null)environment.put("PATH",path);String systemRoot=System.getenv("SystemRoot");if(systemRoot!=null)environment.put("SystemRoot",systemRoot);environment.put("TMP",dir.toString());environment.put("TEMP",dir.toString());
            process=builder.start();if(!process.waitFor(settings.number("parse.timeout-seconds",300),TimeUnit.SECONDS)){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();throw new IllegalStateException("解析超时");}if(process.exitValue()!=0)throw new IllegalStateException("解析独立进程失败，退出码"+process.exitValue());if(!Files.isRegularFile(output)||Files.size(output)>16*1024*1024)throw new IllegalStateException("解析输出缺失或超过预算");
            Object raw=Jsons.map(Files.readString(output)).get("pages");List<Page> pages=new ArrayList<>();int chars=0;for(Object row:Values.list(raw)){Map<String,Object> m=Values.map(row);int number=Values.range(m.getOrDefault("pageNumber",m.getOrDefault("number",m.get("page"))),pages.size()+1,1,settings.number("parse.max-pages",1000));String text=Values.text(m.getOrDefault("text",m.get("content"))).replace('\u00A0',' ').replaceAll("(?m)^No\\.\\s*\\d+\\s*/\\s*\\d+%?\\s*$","").trim();if((chars+=text.length())>settings.number("parse.max-characters",4*1024*1024))throw new IllegalStateException("解析字符超出预算");pages.add(new Page(number,text));}if(pages.isEmpty()||pages.stream().allMatch(p->p.text().isBlank()))throw new IllegalStateException("文档未解析出文本，扫描PDF请启用受控OCR");return List.copyOf(pages);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("解析任务已中断");}finally{if(process!=null&&process.isAlive())process.destroyForcibly();if(acquired)permits.release();if(dir!=null){try(var files=Files.walk(dir)){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}}}
    }
}

