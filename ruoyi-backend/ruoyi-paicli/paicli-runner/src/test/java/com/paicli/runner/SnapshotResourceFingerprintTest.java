package com.paicli.runner;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实临时文件树；平台不支持软链/模式时保留真实跳过原因，不伪造文件类型。 */
class SnapshotResourceFingerprintTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path container;
    private static final List<String> RESTORE_TOOLS = List.of("revert_turn", "snapshot__preview", "control__SNAPSHOT_PREVIEW", "control__SNAPSHOT_RESTORE");
    private ObjectNode args() { return CanonicalJson.MAPPER.createObjectNode(); }
    private Path root() throws IOException { return RunnerPrivateDirectory.createDirectories(container.resolve("root")); }
    private void link(Path path, Path target) throws IOException {
        try { Files.createSymbolicLink(path, target); }
        catch (UnsupportedOperationException | FileSystemException unsupported) {
            assumeTrue(false, "当前宿主无法建立真实符号链接：" + unsupported.getClass().getSimpleName() + ": " + unsupported.getMessage());
        }
    }
    @Test void snapshotInputNeverNarrowsTheFrozenTreeToAnOptionalPath() throws Exception {
        Path root=root(); Files.writeString(root.resolve("a.txt"), "a"); Files.writeString(root.resolve("b.txt"), "b");
        ResourceFingerprint resources=new ResourceFingerprint(root);
        for (String tool : RESTORE_TOOLS) assertEquals(resources.capture(tool,args()),resources.capture(tool,args().put("path","a.txt")),tool);
        String before=resources.capture("revert_turn",args().put("path","a.txt"));
        String ordinary=resources.capture("read_file",args().put("path","a.txt"));
        Files.writeString(root.resolve("b.txt"),"changed independently");
        assertNotEquals(before,resources.capture("revert_turn",args().put("path","a.txt")));
        assertEquals(ordinary,resources.capture("read_file",args().put("path","a.txt")));
    }
    @Test void allFormalPreviewAndRestoreNamesUseTheSameTreeProof() throws Exception {
        Path root=root(); Files.writeString(root.resolve("a.txt"),"中文内容");
        ResourceFingerprint resources=new ResourceFingerprint(root);
        String expected=resources.capture("snapshot__preview",args());
        for(String tool : RESTORE_TOOLS) assertEquals(expected,resources.capture(tool,args().put("snapshotId","a".repeat(40))),tool);
        Files.writeString(root.resolve("a.txt"),"新内容");
        assertNotEquals(expected,resources.capture("snapshot__preview",args()));
    }
    @Test void safeLeafLinksAreFingerprintedWithoutEnablingOrdinaryTools() throws Exception {
        Path root=root(); Files.writeString(root.resolve("a.txt"),"target");
        Path leaf=root.resolve("link"); link(leaf,Path.of("a.txt"));
        ResourceFingerprint resources=new ResourceFingerprint(root);
        String first=resources.capture("snapshot__preview",args());
        for(String tool:RESTORE_TOOLS) assertEquals(first,resources.capture(tool,args()));
        assertThrows(IOException.class,()->resources.capture("read_file",args()));
        assertThrows(IOException.class,()->resources.capture("write_file",args().put("path","link")));
        Files.delete(leaf); Files.writeString(leaf,"a.txt");
        assertNotEquals(first,resources.capture("snapshot__preview",args()),"链接不能与同字节的普通文件混淆");
    }
    @Test void changingOnlyTheSafeLinkTargetInvalidatesApproval() throws Exception {
        Path root=root(); Files.writeString(root.resolve("a.txt"),"same"); Files.writeString(root.resolve("b.txt"),"same");
        Path leaf=root.resolve("link"); link(leaf,Path.of("a.txt"));
        ResourceFingerprint resources=new ResourceFingerprint(root);
        String first=resources.capture("control__SNAPSHOT_RESTORE",args());
        Files.delete(leaf); Files.createSymbolicLink(leaf,Path.of("b.txt"));
        assertNotEquals(first,resources.capture("control__SNAPSHOT_RESTORE",args()));
    }
    @Test void outsideLinksRemainRejectedEvenWhenArgumentsSelectAnotherFile() throws Exception {
        Path root=root(),outside=RunnerPrivateDirectory.createDirectories(container.resolve("outside"));
        Files.writeString(outside.resolve("file.txt"),"outside");
        Path leaf=root.resolve("link"); link(leaf,outside.resolve("file.txt"));
        ResourceFingerprint resources=new ResourceFingerprint(root);
        for(String tool:RESTORE_TOOLS) assertThrows(IOException.class,()->resources.capture(tool,args()));
        Files.writeString(root.resolve("safe.txt"),"inside");
        for(String tool:RESTORE_TOOLS) assertThrows(IOException.class,()->resources.capture(tool,args().put("path","safe.txt")));
    }
    @Test void rootInternalDanglingLinkRemainsALinkInTheProof() throws Exception {
        Path root=root(); Path leaf=root.resolve("link"); link(leaf,Path.of("future.txt"));
        ResourceFingerprint resources=new ResourceFingerprint(root);
        String first=resources.capture("snapshot__preview",args());
        assertTrue(Files.isSymbolicLink(leaf)); assertFalse(Files.exists(leaf));
        Files.delete(leaf); Files.createSymbolicLink(leaf,Path.of("another-future.txt"));
        assertNotEquals(first,resources.capture("snapshot__preview",args()));
    }
    @Test void executableModeChangesInvalidateTheFrozenProof() throws Exception {
        Path root=root(),file=root.resolve("run.sh"); Files.writeString(file,"echo same\n");
        assumeTrue(Files.getFileStore(file).supportsFileAttributeView("posix"),"当前宿主未提供真实 POSIX 文件模式");
        Set<PosixFilePermission> permissions=new java.util.HashSet<>(Files.getPosixFilePermissions(file));
        permissions.removeAll(Set.of(PosixFilePermission.OWNER_EXECUTE,PosixFilePermission.GROUP_EXECUTE,PosixFilePermission.OTHERS_EXECUTE));
        Files.setPosixFilePermissions(file,permissions);
        ResourceFingerprint resources=new ResourceFingerprint(root);String before=resources.capture("snapshot__preview",args());
        permissions.add(PosixFilePermission.OWNER_EXECUTE);Files.setPosixFilePermissions(file,permissions);
        assertNotEquals(before,resources.capture("snapshot__preview",args()));
    }
}
