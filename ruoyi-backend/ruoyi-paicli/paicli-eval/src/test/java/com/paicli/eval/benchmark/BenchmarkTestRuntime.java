package com.paicli.eval.benchmark;

import java.io.IOException;
import java.nio.file.*;

/** 技术 fixture 可显式指定已有 Python；不改变产品工具链、正式容器 argv 或系统安装。 */
public final class BenchmarkTestRuntime {
    private static Path shellAliases;
    private BenchmarkTestRuntime() { }
    public static ProcessBuilder processBuilder(String... command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        String configured = System.getProperty("paicli.test.python", "");
        if (configured.isBlank()) return builder;
        Path python = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(python, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(python)
                || !python.equals(python.toRealPath())) throw new IOException("测试 Python 必须是明确的既有标准解释器文件");
        if (command.length > 0 && "python3".equals(command[0])) builder.command().set(0, python.toString());
        if (command.length > 0 && "bash".equals(command[0])) {
            Path aliases = shellAliases(python);
            builder.environment().put("PATH", aliases + java.io.File.pathSeparator + builder.environment().getOrDefault("PATH", ""));
        }
        return builder;
    }
    private static synchronized Path shellAliases(Path python) throws IOException {
        if (shellAliases != null) return shellAliases;
        Path root = SecureFiles.createTempDirectory("paicli-test-python-");
        Path wrapper = SecureFiles.createFile(root.resolve("python3"));
        String executable = python.toString().replace('\\', '/').replace("'", "'\"'\"'");
        Files.writeString(wrapper, "#!/bin/sh\nexec '" + executable + "' \"$@\"\n");
        SecureFiles.setPermissions(wrapper, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        root.toFile().deleteOnExit(); wrapper.toFile().deleteOnExit(); shellAliases = root;
        return root;
    }
}
