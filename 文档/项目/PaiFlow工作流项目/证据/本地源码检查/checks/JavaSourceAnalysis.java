import javax.tools.*;
import com.sun.source.util.JavacTask;
import java.util.*;

/** 仅在内存parse/analyze指定源码，不生成class、不写target、不执行测试。 */
public class JavaSourceAnalysis {
    public static void main(String[] args) throws Exception {
        var compiler=ToolProvider.getSystemJavaCompiler();var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var files=compiler.getStandardFileManager(diagnostics,null,java.nio.charset.StandardCharsets.UTF_8)) {
            var task=(JavacTask)compiler.getTask(null,files,diagnostics,List.of("-proc:none","-classpath",System.getProperty("java.class.path")),null,files.getJavaFileObjects(args));
            task.parse();task.analyze();long errors=0;
            for(var diagnostic:diagnostics.getDiagnostics())if(diagnostic.getKind()==Diagnostic.Kind.ERROR){errors++;System.out.println(diagnostic);}
            System.out.println("sourceFiles="+args.length+", analysisErrors="+errors+", noBytecode=true, testsExecuted=0");
            if(errors>0)System.exit(1);
        }
    }
}
