package com.paicli.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class RunnerReadinessTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path state;
    @Test void onlyWritesAllowedIdentityAndCanReplaceReadyWithStopped()throws Exception{
        var response=CanonicalJson.MAPPER.createObjectNode().put("runnerId","11").put("ownerUserId","1").put("directoryContextId","22")
            .put("sessionToken","secret-session").put("deviceToken","secret-device");
        Instant expiry=Instant.now().plusSeconds(600);RunnerReadiness.write(state,response,expiry,true);
        String text=Files.readString(state.resolve("runner-readiness.json"));assertFalse(text.contains("secret"));
        var saved=CanonicalJson.MAPPER.readTree(text);assertEquals(ProcessHandle.current().pid(),saved.path("pid").asLong());assertTrue(saved.path("terminalReady").asBoolean());assertEquals("1",saved.path("ownerUserId").asText());
        RunnerReadiness.write(state,response,expiry,false);assertFalse(CanonicalJson.MAPPER.readTree(Files.readString(state.resolve("runner-readiness.json"))).path("terminalReady").asBoolean());
        RunnerPrivateDirectory.requireRegularFile(state.resolve("runner-readiness.json"));
    }
    @Test void windowsReadHandleWithoutDeleteSharingIsBoundedAndDoesNotLoseExistingReport()throws Exception{
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name","").toLowerCase().contains("win"));
        Instant expiry=Instant.now().plusSeconds(600);RunnerReadiness.write(state,null,expiry,true);Path file=state.resolve("runner-readiness.json");String original=Files.readString(file);
        var handle=com.sun.jna.platform.win32.Kernel32.INSTANCE.CreateFile(file.toString(),com.sun.jna.platform.win32.WinNT.GENERIC_READ,
            com.sun.jna.platform.win32.WinNT.FILE_SHARE_READ|com.sun.jna.platform.win32.WinNT.FILE_SHARE_WRITE,null,com.sun.jna.platform.win32.WinNT.OPEN_EXISTING,com.sun.jna.platform.win32.WinNT.FILE_ATTRIBUTE_NORMAL,null);
        assertNotEquals(com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE,handle);
        try{var busy=assertThrows(RunnerReadiness.PublicationBusy.class,()->RunnerReadiness.write(state,null,expiry,false));
            System.out.println("Windows no-ShareDelete atomic replace: "+busy.getCause().getClass().getName()+", reason="+((FileSystemException)busy.getCause()).getReason());
            assertEquals(original,Files.readString(file));}
        finally{com.sun.jna.platform.win32.Kernel32.INSTANCE.CloseHandle(handle);}
        RunnerReadiness.write(state,null,expiry,false);assertFalse(CanonicalJson.MAPPER.readTree(Files.readString(file)).path("terminalReady").asBoolean());
        try(var paths=Files.list(state)){assertFalse(paths.anyMatch(p->p.getFileName().toString().startsWith(".readiness-")));}
    }
    @Test void permanentReadonlyTargetIsRejectedWithoutBeingClassifiedAsBusy()throws Exception{
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name","").toLowerCase().contains("win"));
        Instant expiry=Instant.now().plusSeconds(600);RunnerReadiness.write(state,null,expiry,true);Path file=state.resolve("runner-readiness.json");
        var dos=Files.getFileAttributeView(file,java.nio.file.attribute.DosFileAttributeView.class);dos.setReadOnly(true);
        try{var failure=assertThrows(java.io.IOException.class,()->RunnerReadiness.write(state,null,expiry,false));
            assertFalse(failure instanceof RunnerReadiness.PublicationBusy);assertTrue(dos.readAttributes().isReadOnly());}
        finally{dos.setReadOnly(false);}
    }
}
