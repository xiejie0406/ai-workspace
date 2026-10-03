package com.paicli.runner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 真NT ACL/POSIX测试仅改变自有home临时目录，不修改home或共享TEMP权限。 */
class RunnerPrivateDirectoryTest {
    private Path home,root;
    @BeforeEach void privateFixture() throws IOException {
        String environment=System.getenv(windows()?"USERPROFILE":"HOME");
        if(environment==null||environment.isBlank())throw new IOException("fixture缺少真实用户home");
        home=Path.of(environment).toAbsolutePath().normalize();
        root=RunnerPrivateDirectory.prepare(home.resolve("paicli-private-boundary-test-"+UUID.randomUUID()));
    }
    @AfterEach void removeOwnFixture() throws IOException {
        if(root==null)return;
        if(!root.getParent().equals(home)||!root.getFileName().toString().startsWith("paicli-private-boundary-test-"))throw new IOException("fixture清理范围改变");
        try(var nodes=Files.walk(root)) { for(Path path:nodes.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path); }
    }
    @Test void homeDescendantsAndFilesArePrivateBeforeTheirFirstByte() throws Exception {
        Path nested=RunnerPrivateDirectory.createDirectories(root.resolve("state/pending"));
        RunnerPrivateDirectory.requireDirectory(nested);
        Path file=nested.resolve("initial.json");
        try(var channel=RunnerPrivateDirectory.createFile(file)) {
            assertEquals(0,Files.size(file));RunnerPrivateDirectory.requireRegularFile(file);
            channel.write(ByteBuffer.wrap("fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)));channel.force(true);
        }
        assertEquals("fixture",Files.readString(file));
        if(posix(file))assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));
        else assertFalse(Files.getFileAttributeView(file,AclFileAttributeView.class).getAcl().stream().anyMatch(entry->entry.type()==AclEntryType.ALLOW&&entry.permissions().contains(AclEntryPermission.EXECUTE)));
        assertEquals(nested,RunnerPrivateDirectory.prepare(nested));
    }
    @Test void atomicWritePublishesOnlyCompleteNewFilesAndNeverOverwrites() throws Exception {
        Path file=RunnerPrivateDirectory.writeAtomic(root,"receipt.json","complete-original".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("complete-original",Files.readString(file));RunnerPrivateDirectory.requireFile(file);
        assertThrows(FileAlreadyExistsException.class,()->RunnerPrivateDirectory.writeAtomic(root,"receipt.json",new byte[]{1}));
        assertEquals("complete-original",Files.readString(file));
        assertThrows(IOException.class,()->RunnerPrivateDirectory.writeAtomic(root,"../escape.json",new byte[]{1}));
        try(var entries=Files.list(root)){assertEquals(List.of(file),entries.toList());}
    }
    @Test void tempFilesHaveInitialPrivatePermissionsAndValidNames() throws Exception {
        Path first=RunnerPrivateDirectory.createTempFile(root,".staging-",".tmp"),second=RunnerPrivateDirectory.createTempFile(root,".staging-",".tmp");
        assertNotEquals(first,second);assertEquals(0,Files.size(first));RunnerPrivateDirectory.requireRegularFile(first);
        assertThrows(IOException.class,()->RunnerPrivateDirectory.createTempFile(root,"../",".tmp"));
    }
    @Test void broadExistingRootIsRejectedWithoutChangingItsPermissions() throws Exception {
        widenAndRestore(root,()-> {
            Object before=permissionSnapshot(root);assertThrows(IOException.class,()->RunnerPrivateDirectory.prepare(root));
            assertEquals(before,permissionSnapshot(root));assertThrows(IOException.class,()->RunnerPrivateDirectory.createFile(root.resolve("no-write")));
            assertFalse(Files.exists(root.resolve("no-write")));
        });
    }
    @Test void privateChildBelowBroadParentIsRejectedWithoutChmodOrNewNodes() throws Exception {
        Path child=RunnerPrivateDirectory.prepare(root.resolve("private-child"));
        widenAndRestore(root,()-> {
            Object before=permissionSnapshot(root);assertThrows(IOException.class,()->RunnerPrivateDirectory.requireDirectory(child));
            assertThrows(IOException.class,()->RunnerPrivateDirectory.prepare(child.resolve("not-created")));assertFalse(Files.exists(child.resolve("not-created")));
            assertEquals(before,permissionSnapshot(root));
        });
    }
    @Test void fileReadGrantsAreRejectedAndNotSilentlyRepaired() throws Exception {
        Path file=RunnerPrivateDirectory.writeAtomic(root,"private.json",new byte[]{1});
        widenAndRestore(file,()-> {Object before=permissionSnapshot(file);assertThrows(IOException.class,()->RunnerPrivateDirectory.requireRegularFile(file));assertEquals(before,permissionSnapshot(file));});
    }
    @Test void metadataMutationInGrandparentCannotChangeThePrivateBoundary() throws Exception {
        Path child=RunnerPrivateDirectory.prepare(root.resolve("state/pending"));
        if(posix(root)) {
            widenAndRestore(root,()->assertThrows(IOException.class,()->RunnerPrivateDirectory.requireDirectory(child)));
        } else {
            AclFileAttributeView view=Files.getFileAttributeView(root,AclFileAttributeView.class);List<AclEntry> before=view.getAcl();
            UserPrincipal users=root.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("Everyone");
            List<AclEntry> wider=new ArrayList<>(before);wider.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(users).setPermissions(AclEntryPermission.WRITE_ACL).build());
            view.setAcl(wider);
            try {Object changed=permissionSnapshot(root);assertThrows(IOException.class,()->RunnerPrivateDirectory.requireDirectory(child));assertEquals(changed,permissionSnapshot(root));}
            finally {view.setAcl(before);}
        }
    }
    @Test void junctionOrLinkParentCannotRedirectEvenToAnOtherwisePrivateDirectory() throws Exception {
        Path destination=RunnerPrivateDirectory.prepare(root.resolve("target")),link=root.resolve("redirect");
        if(windows()) {
            Process operation=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",link.toString(),destination.toString()).redirectErrorStream(true).start();
            byte[] diagnostics=operation.getInputStream().readNBytes(4096);
            assertTrue(operation.waitFor(5,TimeUnit.SECONDS));assertEquals(0,operation.exitValue(),new String(diagnostics,java.nio.charset.StandardCharsets.UTF_8));
        } else Files.createSymbolicLink(link,destination);
        try {
            assertThrows(IOException.class,()->RunnerPrivateDirectory.requireDirectory(link));
            assertThrows(IOException.class,()->RunnerPrivateDirectory.prepare(link.resolve("blocked")));assertFalse(Files.exists(destination.resolve("blocked")));
        } finally { Files.delete(link); }
    }
    @Test void ordinaryWindowsTempCannotServeAsSafePrivateParent() throws Exception {
        if(!windows())return; // POSIX TMP父链策略由上面的真实group-write fixture覆盖。
        Path temporary=Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        AclFileAttributeView view=Files.getFileAttributeView(temporary,AclFileAttributeView.class);
        UserPrincipal current=temporary.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        boolean ordinaryWrite=view.getAcl().stream().anyMatch(entry->entry.type()==AclEntryType.ALLOW&&!entry.principal().equals(current)
                &&!entry.flags().contains(AclEntryFlag.INHERIT_ONLY)&&entry.principal().getName().toLowerCase(Locale.ROOT).contains("codexsandbox")
                &&entry.permissions().contains(AclEntryPermission.DELETE_CHILD));
        if(ordinaryWrite) {
            List<AclEntry> before=view.getAcl();Path candidate=temporary.resolve("paicli-rejected-wide-parent-"+UUID.randomUUID());
            assertThrows(IOException.class,()->RunnerPrivateDirectory.prepare(candidate));assertFalse(Files.exists(candidate));assertEquals(before,view.getAcl());
        } else {
            // 不假定其他机器TEMP宽；本测试环境真实广泛ACL必须由fixture检查而非硬编码。
            Path broad=RunnerPrivateDirectory.prepare(root.resolve("ordinary-temp-parent"));
            widenAndRestore(broad,()->assertThrows(IOException.class,()->RunnerPrivateDirectory.prepare(broad.resolve("blocked"))));
        }
    }
    private interface Checked {void run() throws Exception;}
    private void widenAndRestore(Path path,Checked operation) throws Exception {
        if(posix(path)) {
            Set<PosixFilePermission> before=Files.getPosixFilePermissions(path);
            EnumSet<PosixFilePermission> wider=EnumSet.copyOf(before);wider.add(Files.isDirectory(path)?PosixFilePermission.GROUP_WRITE:PosixFilePermission.GROUP_READ);
            Files.setPosixFilePermissions(path,wider);try{operation.run();}finally{Files.setPosixFilePermissions(path,before);}
        } else {
            AclFileAttributeView view=Files.getFileAttributeView(path,AclFileAttributeView.class);List<AclEntry> before=view.getAcl();
            UserPrincipal users=path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("Everyone");
            Set<AclEntryPermission> permissions=Files.isDirectory(path)?Set.of(AclEntryPermission.READ_DATA,AclEntryPermission.WRITE_DATA,AclEntryPermission.DELETE_CHILD):Set.of(AclEntryPermission.READ_DATA);
            List<AclEntry> wider=new ArrayList<>(before);wider.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(users).setPermissions(permissions).build());
            view.setAcl(wider);try{operation.run();}finally{view.setAcl(before);}
        }
    }
    private static Object permissionSnapshot(Path path)throws IOException {return posix(path)?Files.getPosixFilePermissions(path):Files.getFileAttributeView(path,AclFileAttributeView.class).getAcl();}
    private static boolean posix(Path path){return Files.getFileAttributeView(path,PosixFileAttributeView.class)!=null;}
    private static boolean windows(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");}
}
