package com.paicli.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceLockDirectoryTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path home;
    @Test void createsPrivateManagedParentsAndOrdinaryLockFile() throws Exception {
        DeviceCredentialCache.restrict(home);
        Path locks = WorkspaceLockDirectory.prepare(home); Path file = locks.resolve("fixture.lock");
        WorkspaceLockDirectory.prepareFile(file); WorkspaceLockDirectory.requireFile(file);
        assertTrue(Files.isRegularFile(file)); assertEquals(home.resolve(".cache/ruoyi-paicli/workspace-locks"), locks);
    }
    @Test void unsafeExistingCacheFailsWithoutChangingItsPermissionsOrCreatingManagedDirectories() throws Exception {
        DeviceCredentialCache.restrict(home); Path cache = Files.createDirectory(home.resolve(".cache"));
        PosixFileAttributeView posix = Files.getFileAttributeView(cache, PosixFileAttributeView.class);
        if (posix != null) {
            var insecure = PosixFilePermissions.fromString("rwxrwx---"); Files.setPosixFilePermissions(cache, insecure);
            assertThrows(IOException.class, () -> WorkspaceLockDirectory.prepare(home)); assertEquals(insecure, Files.getPosixFilePermissions(cache));
        } else {
            DeviceCredentialCache.restrict(cache); AclFileAttributeView acl = Files.getFileAttributeView(cache, AclFileAttributeView.class);
            UserPrincipal everyone = cache.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("Everyone");
            var entries = new ArrayList<>(acl.getAcl()); entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(everyone)
                    .setPermissions(AclEntryPermission.WRITE_DATA, AclEntryPermission.DELETE_CHILD).build()); acl.setAcl(entries);
            assertThrows(IOException.class, () -> WorkspaceLockDirectory.prepare(home)); assertEquals(entries, acl.getAcl());
        }
        assertFalse(Files.exists(cache.resolve("ruoyi-paicli")));
    }
    @Test void specialLockNodeAndUnsafeManagedParentAreRejected() throws Exception {
        DeviceCredentialCache.restrict(home); Path locks = WorkspaceLockDirectory.prepare(home);
        Path fake = Files.createDirectory(locks.resolve("fixture.lock"));
        assertThrows(IOException.class, () -> WorkspaceLockDirectory.requireFile(fake));
        assertThrows(IOException.class, () -> WorkspaceLockDirectory.prepareFile(fake));
    }
    @Test void windowsSystemOwnedHomeAndCacheAreReadOnlyAcceptedButPrivateLocksRemainUserOwned()throws Exception{
        var acl=Files.getFileAttributeView(home,AclFileAttributeView.class);org.junit.jupiter.api.Assumptions.assumeTrue(acl!=null,"需要Windows ACL文件系统");
        DeviceCredentialCache.restrict(home);Path cache=Files.createDirectory(home.resolve(".cache"));DeviceCredentialCache.restrict(cache);
        var lookup=home.getFileSystem().getUserPrincipalLookupService();UserPrincipal user=lookup.lookupPrincipalByName(System.getProperty("user.name")),authority=lookup.lookupPrincipalByName("BUILTIN\\Administrators");
        try{
            try{Files.setOwner(home,authority);Files.setOwner(cache,authority);}catch(IOException unavailable){org.junit.jupiter.api.Assumptions.assumeTrue(false,"本机测试进程不能设置系统主体所有者");}
            var homeAcl=Files.getFileAttributeView(home,AclFileAttributeView.class).getAcl();var cacheAcl=Files.getFileAttributeView(cache,AclFileAttributeView.class).getAcl();
            Path locks=WorkspaceLockDirectory.prepare(home);assertEquals(authority,Files.getOwner(home));assertEquals(authority,Files.getOwner(cache));assertEquals(user,Files.getOwner(locks));
            assertEquals(homeAcl,Files.getFileAttributeView(home,AclFileAttributeView.class).getAcl());assertEquals(cacheAcl,Files.getFileAttributeView(cache,AclFileAttributeView.class).getAcl());
            Path lock=locks.resolve("system-owner.lock");WorkspaceLockDirectory.prepareFile(lock);Files.setOwner(lock,authority);
            try{assertThrows(IOException.class,()->WorkspaceLockDirectory.requireFile(lock));}finally{Files.setOwner(lock,user);}
            Files.setOwner(locks,authority);try{assertThrows(IOException.class,()->WorkspaceLockDirectory.prepare(home));}finally{Files.setOwner(locks,user);}
        }finally{Files.setOwner(cache,user);Files.setOwner(home,user);}
    }
    @Test void existingWindowsSystemOwnedProfileIsAcceptedOnlyAsNonPrivateParentWithoutMutation()throws Exception{
        String profile=System.getenv("USERPROFILE");org.junit.jupiter.api.Assumptions.assumeTrue(profile!=null,"需要Windows实际用户目录");Path actual=Path.of(profile).toRealPath();
        var acl=Files.getFileAttributeView(actual,AclFileAttributeView.class);org.junit.jupiter.api.Assumptions.assumeTrue(acl!=null,"需要Windows ACL");
        var lookup=actual.getFileSystem().getUserPrincipalLookupService();UserPrincipal owner=Files.getOwner(actual);
        var authorities=List.of(lookup.lookupPrincipalByName("NT AUTHORITY\\SYSTEM"),lookup.lookupPrincipalByName("BUILTIN\\Administrators"));org.junit.jupiter.api.Assumptions.assumeTrue(authorities.contains(owner),"仅核对实际受信系统所有者目录");
        var before=acl.getAcl();WorkspaceLockDirectory.requireDirectory(actual,false);assertThrows(IOException.class,()->WorkspaceLockDirectory.requireDirectory(actual,true));
        assertEquals(owner,Files.getOwner(actual));assertEquals(before,acl.getAcl());
    }
    @Test void differentRootsCannotShareStateAndFailedConstructorReleasesRootLock() throws Exception {
        Path rootA=Files.createDirectory(home.resolve("root-a")),rootB=Files.createDirectory(home.resolve("root-b"));
        Path state=home.resolve("private-state"),locksHome=Files.createDirectory(home.resolve("lock-home"));DeviceCredentialCache.restrict(locksHome);
        try(var centerA=new CenterHttpClient("http://127.0.0.1:1","fixture",true);var centerB=new CenterHttpClient("http://127.0.0.1:1","fixture",true);
            var first=new RunnerEngine(centerA,rootA,state,(lease,tools)->"",null,null,locksHome)) {
            assertThrows(IOException.class,()->new RunnerEngine(centerB,rootB,state,(lease,tools)->"",null,null,locksHome));
            if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")) {
                Path caseAlias=Path.of(state.toString().toUpperCase(Locale.ROOT));
                assertEquals(state.toRealPath(),caseAlias.toRealPath());
                assertThrows(IOException.class,()->new RunnerEngine(centerB,rootB,caseAlias,(lease,tools)->"",null,null,locksHome));
            }
            try(var separate=new RunnerEngine(centerB,rootB,home.resolve("other-state"),(lease,tools)->"",null,null,locksHome)) { assertNotNull(separate); }
        }
        try(var center=new CenterHttpClient("http://127.0.0.1:1","fixture",true);var reattached=new RunnerEngine(center,rootB,state,(lease,tools)->"",null,null,locksHome)){assertNotNull(reattached);}
    }
}
