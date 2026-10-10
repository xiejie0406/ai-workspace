package com.paicli.runner;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.tool.ToolOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PendingImageEvidenceTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path temp;
    private ObjectNode binding(String call){return CanonicalJson.MAPPER.createObjectNode().put("taskId","1").put("leaseId","2").put("toolCallId",call).put("permitToken","fixture-private-permit");}
    @Test void twoRegistryInstancesShareTheFourExecutionQuota()throws Exception{
        PendingImageEvidence first=new PendingImageEvidence(temp),second=new PendingImageEvidence(temp);
        try(var one=first.reserve();var two=second.reserve();var three=first.reserve();var four=second.reserve()){
            assertThrows(IOException.class,first::reserve);assertThrows(IOException.class,second::reserve);
        }
        try(var released=second.reserve()){assertNotNull(released);}
    }
    @Test void writtenBytesAndFilesReplaceTheirReservationInsteadOfBeingCountedTwice()throws Exception{
        PendingImageEvidence first=new PendingImageEvidence(temp),second=new PendingImageEvidence(temp);
        try(var one=first.reserve();var two=second.reserve()){
            Path result=one.preserveOutput(binding("one"),ToolOutput.text("完整实际结果"));assertTrue(Files.exists(result));
            try(var three=second.reserve();var four=first.reserve()){assertThrows(IOException.class,second::reserve);}
            // 一项实际文件与其未用容量仍合计512MB，后两项保留不会受已写正文双计费影响。
            assertTrue(Files.exists(result));
        }
    }
    @Test void simultaneousRegistriesCannotReserveMoreThanTheSharedLimit()throws Exception{
        PendingImageEvidence first=new PendingImageEvidence(temp),second=new PendingImageEvidence(temp);var start=new java.util.concurrent.CountDownLatch(1);var held=new java.util.concurrent.CopyOnWriteArrayList<PendingImageEvidence.Reservation>();var rejected=new java.util.concurrent.atomic.AtomicInteger();var workers=java.util.concurrent.Executors.newFixedThreadPool(8);var attempts=new ArrayList<java.util.concurrent.Future<?>>();
        try{for(int i=0;i<8;i++){PendingImageEvidence pending=i%2==0?first:second;attempts.add(workers.submit(()->{try{start.await();held.add(pending.reserve());}catch(IOException full){rejected.incrementAndGet();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}}));}start.countDown();for(var attempt:attempts)attempt.get(30,java.util.concurrent.TimeUnit.SECONDS);assertEquals(4,held.size());assertEquals(4,rejected.get());}
        finally{held.forEach(PendingImageEvidence.Reservation::close);workers.shutdownNow();}
    }
    @Test void oneExecutionKeepsAtMostTwoDistinctResultsAndNeverPersistsThePermit()throws Exception{
        PendingImageEvidence pending=new PendingImageEvidence(temp);Path first,second;
        try(var execution=pending.reserve()){
            first=execution.preserveOutput(binding("call"),ToolOutput.text("原始控制结果"));assertEquals(first,execution.preserveOutput(binding("call"),ToolOutput.text("原始控制结果")));
            second=execution.preserveOutput(binding("call"),ToolOutput.text("最终工具结果"));assertNotEquals(first,second);assertThrows(IOException.class,()->execution.preserveOutput(binding("call"),ToolOutput.text("不允许第三份结果")));
        }
        var evidence=CanonicalJson.MAPPER.readTree(Files.readAllBytes(first));assertEquals("原始控制结果",evidence.path("result").path("text").asText());assertEquals("EXECUTION_RESULT_UNCONFIRMED",evidence.path("phase").asText());assertFalse(evidence.toString().contains("fixture-private-permit"));
        pending.acknowledged(first);assertFalse(Files.exists(first));assertTrue(Files.exists(second));
    }
    @Test void closingAReservationIsIdempotentAndCannotAuthorizeFurtherEvidenceWrites()throws Exception{
        PendingImageEvidence pending=new PendingImageEvidence(temp);var execution=pending.reserve();execution.close();execution.close();assertThrows(IOException.class,()->execution.preserveOutput(binding("closed"),ToolOutput.text("禁止释放后的写入")));
        try(var one=pending.reserve();var two=pending.reserve();var three=pending.reserve();var four=pending.reserve()){assertThrows(IOException.class,pending::reserve);}
    }
    @Test void modificationEvidenceKeepsTheCompleteActualResultAndItsIndependentOriginal()throws Exception{
        PendingImageEvidence pending=new PendingImageEvidence(temp);
        ObjectNode identity=binding("edit").put("ownerUserId","7").put("directoryContextId","11").put("sessionId","13");
        ToolOutput actual=ToolOutput.text("真实工具结果\n"+"原正文".repeat(20000));
        ObjectNode modification=CanonicalJson.MAPPER.createObjectNode().put("path","actual.txt").put("diff","--- a/actual.txt\n+++ b/actual.txt\n-原文\n+实文\n");
        Path original,combined;
        try(var execution=pending.reserve()){
            original=execution.preserveOutput(identity,actual);
            byte[] originalBytes=Files.readAllBytes(original);
            combined=execution.preserveModification(identity,actual,modification);
            assertNotEquals(original,combined);assertArrayEquals(originalBytes,Files.readAllBytes(original));
        }
        for(Path path:List.of(original,combined)){
            var saved=CanonicalJson.MAPPER.readTree(Files.readAllBytes(path));
            assertEquals(actual.text(),saved.path("result").path("text").asText());
            assertEquals("7",saved.path("ownerUserId").asText());assertEquals("13",saved.path("sessionId").asText());
            assertFalse(saved.toString().contains("fixture-private-permit"));
        }
        assertEquals(modification,CanonicalJson.MAPPER.readTree(Files.readAllBytes(combined)).path("fileModification"));
        pending.acknowledged(combined);assertTrue(Files.exists(original));
    }
    @Test void broadAncestorIsRejectedForConstructionWritingAndAcknowledgementWithoutPermissionRepair()throws Exception{
        Path ancestor=RunnerPrivateDirectory.createDirectories(temp.resolve("broad-parent"));PendingImageEvidence pending=new PendingImageEvidence(ancestor);Path saved=pending.preserveOutput(binding("saved"),ToolOutput.text("已有完整证据"));byte[] original=Files.readAllBytes(saved);
        PosixFileAttributeView posix=Files.getFileAttributeView(ancestor,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null){
            Set<PosixFilePermission> permissions=posix.readAttributes().permissions(),broad=EnumSet.copyOf(permissions);broad.add(PosixFilePermission.GROUP_WRITE);Files.setPosixFilePermissions(ancestor,broad);
            try{assertBroadParentRejected(pending,ancestor,saved,original);assertEquals(broad,Files.getPosixFilePermissions(ancestor));}finally{Files.setPosixFilePermissions(ancestor,permissions);}
        }else{
            AclFileAttributeView acl=Files.getFileAttributeView(ancestor,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);assertNotNull(acl);List<AclEntry> originalAcl=List.copyOf(acl.getAcl()),broad=new ArrayList<>(originalAcl);UserPrincipal group=ancestor.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("Everyone");broad.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(group).setPermissions(AclEntryPermission.DELETE_CHILD,AclEntryPermission.WRITE_ACL).build());acl.setAcl(broad);
            try{assertBroadParentRejected(pending,ancestor,saved,original);assertEquals(broad,acl.getAcl());}finally{acl.setAcl(originalAcl);}
        }
    }
    private void assertBroadParentRejected(PendingImageEvidence pending,Path ancestor,Path saved,byte[] original){
        assertThrows(IOException.class,()->new PendingImageEvidence(ancestor));assertThrows(IOException.class,()->pending.preserveOutput(binding("new"),ToolOutput.text("不能写入宽权限父链")));assertThrows(IOException.class,()->pending.acknowledged(saved));assertTrue(Files.exists(saved));try{assertArrayEquals(original,Files.readAllBytes(saved));}catch(IOException failed){throw new AssertionError(failed);}
    }
}
