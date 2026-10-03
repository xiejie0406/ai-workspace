package com.paicli.eval.benchmark;

import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.util.*;

/** 仅保护JUnit为当前测试创建的目录；不读取正式历史、不改金标、不替换判定。 */
public final class SecureFilesTempDirExtension implements BeforeEachCallback,AfterEachCallback,InvocationInterceptor {
    private static final ExtensionContext.Namespace NAMESPACE=ExtensionContext.Namespace.create(SecureFilesTempDirExtension.class);
    @Override public void beforeEach(ExtensionContext context)throws Exception{
        for(Object instance:context.getTestInstances().orElseThrow().getAllInstances())for(Class<?> type=instance.getClass();type!=null;type=type.getSuperclass())for(Field field:type.getDeclaredFields())if(field.isAnnotationPresent(TempDir.class)){field.setAccessible(true);protect(context,field.get(instance));}
    }
    @Override public void interceptTestMethod(Invocation<Void> invocation,ReflectiveInvocationContext<Method> method,ExtensionContext context)throws Throwable{protectParameters(method,context);invocation.proceed();}
    @Override public void interceptTestTemplateMethod(Invocation<Void> invocation,ReflectiveInvocationContext<Method> method,ExtensionContext context)throws Throwable{protectParameters(method,context);invocation.proceed();}
    private static void protectParameters(ReflectiveInvocationContext<Method> method,ExtensionContext context)throws IOException{Parameter[] parameters=method.getExecutable().getParameters();for(int i=0;i<parameters.length;i++)if(parameters[i].isAnnotationPresent(TempDir.class))protect(context,method.getArguments().get(i));}
    @SuppressWarnings("unchecked") private static Set<Path> roots(ExtensionContext context){return context.getStore(NAMESPACE).getOrComputeIfAbsent("roots",key->new LinkedHashSet<Path>(),Set.class);}
    private static void protect(ExtensionContext context,Object value)throws IOException{Path root=value instanceof Path p?p:value instanceof java.io.File f?f.toPath():null;if(root==null)return;root=root.toAbsolutePath().normalize();SecureFiles.setPermissions(root,SecureFiles.PRIVATE_DIRECTORY);roots(context).add(root);}
    @Override public void afterEach(ExtensionContext context)throws Exception{
        for(Path root:roots(context))if(Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(root)&&root.equals(root.toRealPath()))Files.walkFileTree(root,new SimpleFileVisitor<>(){
            @Override public FileVisitResult preVisitDirectory(Path directory,BasicFileAttributes attrs)throws IOException{if(!directory.toAbsolutePath().normalize().startsWith(root)||Files.isSymbolicLink(directory)||!directory.toAbsolutePath().normalize().equals(directory.toRealPath()))return FileVisitResult.SKIP_SUBTREE;SecureFiles.setPermissions(directory,SecureFiles.PRIVATE_DIRECTORY);return FileVisitResult.CONTINUE;}
            // 删除权限由恢复后的私有父目录提供；不修改硬链文件或目录外来源的ACL。
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs){return FileVisitResult.CONTINUE;}
        });
    }
}
