package com.paicli.runner;

import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;
import java.nio.file.Path;
import java.util.UUID;

/** 正向安全测试使用真实用户home下原子创建的私有目录，不收紧共享TEMP或旧目录。 */
public final class RunnerPrivateTempDirFactory implements TempDirFactory {
    @Override public Path createTempDirectory(AnnotatedElementContext element,ExtensionContext context)throws Exception{
        String actualHome=System.getenv(System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("win")?"USERPROFILE":"HOME");
        if(actualHome==null||actualHome.isBlank())throw new java.io.IOException("安全测试缺少真实用户home");
        return RunnerPrivateDirectory.createDirectories(Path.of(actualHome).resolve("paicli-runner-test-"+UUID.randomUUID()));
    }
}
