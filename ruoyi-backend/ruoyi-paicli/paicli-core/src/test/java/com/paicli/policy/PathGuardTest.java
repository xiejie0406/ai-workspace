package com.paicli.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PathGuardTest {

    @Test
    void allowsRelativePathInsideRoot(@TempDir Path root) throws Exception {
        PathGuard guard = new PathGuard(root.toString());
        Path resolved = guard.resolveSafe("src/Main.java");
        assertTrue(resolved.startsWith(root.toRealPath()));
        assertTrue(resolved.endsWith(Path.of("src", "Main.java")));
    }

    @Test
    void allowsAbsolutePathInsideRoot(@TempDir Path root) throws Exception {
        PathGuard guard = new PathGuard(root.toString());
        Path target = root.resolve("a/b.txt");
        Path resolved = guard.resolveSafe(target.toString());
        assertTrue(resolved.startsWith(root.toRealPath()));
    }

    @Test
    void allowsCurrentDirectory(@TempDir Path root) throws Exception {
        PathGuard guard = new PathGuard(root.toString());
        Path resolved = guard.resolveSafe(".");
        assertEquals(root.toRealPath(), resolved);
    }

    @Test
    void allowsNonExistingTargetForCreate(@TempDir Path root) throws Exception {
        PathGuard guard = new PathGuard(root.toString());
        Path resolved = guard.resolveSafe("nested/deeply/new-file.txt");
        assertTrue(resolved.startsWith(root.toRealPath()));
        assertFalse(Files.exists(resolved));
    }

    @Test
    void rejectsAbsolutePathOutsideRoot(@TempDir Path root) {
        PathGuard guard = new PathGuard(root.toString());
        PolicyException ex = assertThrows(PolicyException.class,
                () -> guard.resolveSafe("/etc/passwd"));
        assertTrue(ex.getMessage().contains("路径越界"));
    }

    @Test
    void rejectsParentTraversalEscape(@TempDir Path root) {
        PathGuard guard = new PathGuard(root.toString());
        assertThrows(PolicyException.class,
                () -> guard.resolveSafe("../../etc/passwd"));
    }

    @Test
    void rejectsParentTraversalThroughLeadingDots(@TempDir Path root) {
        PathGuard guard = new PathGuard(root.toString());
        assertThrows(PolicyException.class,
                () -> guard.resolveSafe(".."));
    }

    @Test
    void rejectsBlankPath(@TempDir Path root) {
        PathGuard guard = new PathGuard(root.toString());
        assertThrows(PolicyException.class, () -> guard.resolveSafe(""));
        assertThrows(PolicyException.class, () -> guard.resolveSafe("   "));
        assertThrows(PolicyException.class, () -> guard.resolveSafe(null));
    }

    @Test
    void rejectsSymlinkEscapingRoot(@TempDir Path root, @TempDir Path outside) throws IOException {
        Path outsideTarget = outside.resolve("secret.txt");
        Files.writeString(outsideTarget, "leak");

        Path linkInsideRoot = root.resolve("backdoor");
        try {
            Files.createSymbolicLink(linkInsideRoot, outside);
        } catch (UnsupportedOperationException | IOException e) {
            // 当前文件系统不支持符号链接（Windows 无管理员权限），跳过此用例
            return;
        }

        PathGuard guard = new PathGuard(root.toString());
        PolicyException ex = assertThrows(PolicyException.class,
                () -> guard.resolveSafe("backdoor/secret.txt"));
        assertTrue(ex.getMessage().contains("路径越界"));
    }

    @Test
    void rejectNullRoot() {
        assertThrows(IllegalArgumentException.class, () -> new PathGuard(null));
        assertThrows(IllegalArgumentException.class, () -> new PathGuard(""));
        assertThrows(IllegalArgumentException.class, () -> new PathGuard("  "));
    }

    @Test
    void rejectsMissingOrFileRoot(@TempDir Path root) throws IOException {
        assertThrows(PolicyException.class, () -> new PathGuard(root.resolve("missing").toString()));
        Path file = root.resolve("not-a-directory");
        Files.writeString(file, "fixture");
        assertThrows(PolicyException.class, () -> new PathGuard(file.toString()));
    }

    @Test
    void rejectsInvalidPath(@TempDir Path root) {
        PathGuard guard = new PathGuard(root.toString());
        assertThrows(PolicyException.class, () -> guard.resolveSafe("invalid\u0000path"));
    }

    @Test
    void rejectsDanglingSymlinkRatherThanTreatingItAsNewFile(@TempDir Path root) throws IOException {
        Path link = root.resolve("dangling");
        try {
            Files.createSymbolicLink(link, root.resolve("missing"));
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "当前文件系统不能创建符号链接");
        }
        assertThrows(PolicyException.class, () -> new PathGuard(root.toString()).resolveSafe("dangling/new.txt"));
    }
}
