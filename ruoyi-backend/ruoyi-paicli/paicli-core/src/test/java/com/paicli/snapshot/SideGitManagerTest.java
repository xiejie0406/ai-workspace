package com.paicli.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SideGitManagerTest {

    @TempDir
    Path tempDir;

    // 只使用真实平台软链；无法创建时保留真实原因，不用复制或junction替代。
    private void requireRealLinks(Path project) throws Exception {
        Path target=project.resolve("link-capability-target"),link=project.resolve("link-capability-probe");
        Files.writeString(target,"probe");
        try {Files.createSymbolicLink(link,Path.of("link-capability-target"));}
        catch(java.io.IOException|UnsupportedOperationException|SecurityException unavailable) {
            throw new org.opentest4j.TestAbortedException("真实软链样本创建失败: "+unavailable.getClass().getSimpleName()+": "+unavailable.getMessage(),unavailable);
        } finally {Files.deleteIfExists(link);Files.deleteIfExists(target);}
    }
    private SideGitManager linkManager(Path project,String name) {
        return new SideGitManager(project,new SnapshotConfig(true,tempDir.resolve(name+"-state"),50,List.of(".git")));
    }
    private void replaceLink(Path path,String target)throws Exception {Files.deleteIfExists(path);Files.createSymbolicLink(path,Path.of(target));}

    @Test void restoresMissingSafeLeafLinkAndAcceptsUnchangedSafeLink()throws Exception {
        Path project=Files.createDirectory(tempDir.resolve("link-add"));requireRealLinks(project);
        Files.writeString(project.resolve("target.txt"),"target unchanged");
        Files.createSymbolicLink(project.resolve("added.link"),Path.of("target.txt"));Files.createSymbolicLink(project.resolve("unchanged.link"),Path.of("target.txt"));
        var manager=linkManager(project,"link-add");var target=manager.preTurnSnapshot("before","safe links");Files.delete(project.resolve("added.link"));
        var preview=manager.previewRestore(target.commitId());assertTrue((Boolean)preview.get("canRestore"));
        @SuppressWarnings("unchecked") var rows=(List<SideGitManager.PreviewFile>)preview.get("files");
        var added=rows.stream().filter(row->row.path().equals("added.link")).findFirst().orElseThrow();
        assertEquals("ADD",added.status());assertEquals(Integer.valueOf(0120000),added.targetMode());
        assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("target.txt".getBytes(java.nio.charset.StandardCharsets.UTF_8))),added.targetHash());
        assertTrue(rows.stream().anyMatch(row->row.path().equals("unchanged.link")&&row.status().equals("UNCHANGED")));
        assertTrue(manager.restoreSnapshot(target.commitId()).success());assertTrue(Files.isSymbolicLink(project.resolve("added.link")));
        assertEquals(Path.of("target.txt"),Files.readSymbolicLink(project.resolve("added.link")));assertEquals("target unchanged",Files.readString(project.resolve("target.txt")));
    }

    @Test void restoresChangedLinkAndBothFileLinkTypeTransitions()throws Exception {
        Path project=Files.createDirectory(tempDir.resolve("link-types"));requireRealLinks(project);
        Files.writeString(project.resolve("a.txt"),"a");Files.writeString(project.resolve("b.txt"),"b");
        Files.createSymbolicLink(project.resolve("changed.link"),Path.of("a.txt"));Files.createSymbolicLink(project.resolve("was-link"),Path.of("a.txt"));Files.writeString(project.resolve("was-file"),"ordinary snapshot");
        var manager=linkManager(project,"link-types");var target=manager.preTurnSnapshot("before","types");
        replaceLink(project.resolve("changed.link"),"b.txt");Files.delete(project.resolve("was-link"));Files.writeString(project.resolve("was-link"),"current ordinary");replaceLink(project.resolve("was-file"),"b.txt");
        assertTrue((Boolean)manager.previewRestore(target.commitId()).get("canRestore"));assertTrue(manager.restoreSnapshot(target.commitId()).success());
        assertEquals(Path.of("a.txt"),Files.readSymbolicLink(project.resolve("changed.link")));assertTrue(Files.isSymbolicLink(project.resolve("was-link")));
        assertFalse(Files.isSymbolicLink(project.resolve("was-file")));assertEquals("ordinary snapshot",Files.readString(project.resolve("was-file")));
        assertEquals("a",Files.readString(project.resolve("a.txt")));assertEquals("b",Files.readString(project.resolve("b.txt")));
    }

    @Test void deletesAdditionalSafeLinkWithoutDeletingItsTarget()throws Exception {
        Path project=Files.createDirectory(tempDir.resolve("link-delete"));requireRealLinks(project);Files.writeString(project.resolve("target.txt"),"preserved");
        var manager=linkManager(project,"link-delete");var target=manager.preTurnSnapshot("before","ordinary");Files.createSymbolicLink(project.resolve("extra.link"),Path.of("target.txt"));
        assertTrue((Boolean)manager.previewRestore(target.commitId()).get("canRestore"));assertTrue(manager.restoreSnapshot(target.commitId()).success());
        assertFalse(Files.exists(project.resolve("extra.link"),java.nio.file.LinkOption.NOFOLLOW_LINKS));assertEquals("preserved",Files.readString(project.resolve("target.txt")));
    }

    @Test void refusesExternalSnapshotLinkAndLinkedParentBeforeAnyWrite()throws Exception {
        Path project=Files.createDirectory(tempDir.resolve("link-unsafe"));requireRealLinks(project);Path outside=Files.createDirectory(tempDir.resolve("outside"));Files.writeString(outside.resolve("protected.txt"),"outside");
        Files.createSymbolicLink(project.resolve("unsafe.link"),outside.resolve("protected.txt"));Files.writeString(project.resolve("first.txt"),"snapshot");
        var manager=linkManager(project,"link-unsafe");var target=manager.preTurnSnapshot("before","external snapshot link");Files.delete(project.resolve("unsafe.link"));Files.writeString(project.resolve("first.txt"),"current");
        assertFalse((Boolean)manager.previewRestore(target.commitId()).get("canRestore"));org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,()->manager.restoreSnapshot(target.commitId()));
        assertEquals("current",Files.readString(project.resolve("first.txt")));assertEquals("outside",Files.readString(outside.resolve("protected.txt")));
        Path second=Files.createDirectory(tempDir.resolve("linked-parent"));requireRealLinks(second);Files.createDirectory(second.resolve("dir"));Files.writeString(second.resolve("dir/file.txt"),"snapshot");
        var secondManager=linkManager(second,"linked-parent");var secondTarget=secondManager.preTurnSnapshot("before","parent");Files.delete(second.resolve("dir/file.txt"));Files.delete(second.resolve("dir"));Files.createSymbolicLink(second.resolve("dir"),outside);
        assertFalse((Boolean)secondManager.previewRestore(secondTarget.commitId()).get("canRestore"));org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,()->secondManager.restoreSnapshot(secondTarget.commitId()));assertEquals("outside",Files.readString(outside.resolve("protected.txt")));
    }

    @Test void detectsSafeLinkTargetSwapImmediatelyBeforeWrite()throws Exception {
        Path project=Files.createDirectory(tempDir.resolve("link-cas"));requireRealLinks(project);for(String name:List.of("a","b","c"))Files.writeString(project.resolve(name),name);
        Files.createSymbolicLink(project.resolve("changed.link"),Path.of("a"));
        var manager=new SideGitManager(project,new SnapshotConfig(true,tempDir.resolve("link-cas-state"),50,List.of(".git"))) {
            @Override protected void beforeRestoreWrite(String path,boolean compensation)throws java.io.IOException {if(!compensation&&path.equals("changed.link")){Files.delete(project.resolve(path));Files.createSymbolicLink(project.resolve(path),Path.of("c"));}}
        };
        var target=manager.preTurnSnapshot("before","a");replaceLink(project.resolve("changed.link"),"b");
        org.junit.jupiter.api.Assertions.assertThrows(SnapshotRestoreException.class,()->manager.restoreSnapshot(target.commitId()));assertEquals(Path.of("c"),Files.readSymbolicLink(project.resolve("changed.link")));
    }

    @Test void rejectsOrdinaryContentSwapImmediatelyBeforeWrite()throws Exception {
        Path project=Files.createDirectory(tempDir.resolve("ordinary-cas"));Files.writeString(project.resolve("changed.txt"),"snapshot");
        var manager=new SideGitManager(project,new SnapshotConfig(true,tempDir.resolve("ordinary-cas-state"),50,List.of(".git"))) {
            @Override protected void beforeRestoreWrite(String path,boolean compensation)throws java.io.IOException {if(!compensation&&path.equals("changed.txt"))Files.writeString(project.resolve(path),"external later change");}
        };
        var target=manager.preTurnSnapshot("before","ordinary");Files.writeString(project.resolve("changed.txt"),"current");
        org.junit.jupiter.api.Assertions.assertThrows(SnapshotRestoreException.class,()->manager.restoreSnapshot(target.commitId()));assertEquals("external later change",Files.readString(project.resolve("changed.txt")));
    }

    @Test void failedLaterWriteCompensatesSafeLink()throws Exception {assertLinkCompensation(false);}
    @Test void linkCompensationPreservesLaterExternalChange()throws Exception {assertLinkCompensation(true);}
    private void assertLinkCompensation(boolean changed)throws Exception {
        Path project=Files.createDirectory(tempDir.resolve(changed?"link-compensation-later":"link-compensation"));requireRealLinks(project);for(String name:List.of("old","current","later"))Files.writeString(project.resolve(name),name);
        Files.createSymbolicLink(project.resolve("a.link"),Path.of("old"));Files.writeString(project.resolve("b.txt"),"old b");
        var manager=new SideGitManager(project,new SnapshotConfig(true,tempDir.resolve(changed?"link-later-state":"link-rollback-state"),50,List.of(".git"))) {
            @Override protected void beforeRestoreWrite(String path,boolean compensation)throws java.io.IOException {
                if(!compensation&&path.equals("b.txt")) {if(changed){Files.delete(project.resolve("a.link"));Files.createSymbolicLink(project.resolve("a.link"),Path.of("later"));}throw new java.io.IOException("fixture later write failure");}
            }
        };
        var target=manager.preTurnSnapshot("before","old links");replaceLink(project.resolve("a.link"),"current");Files.writeString(project.resolve("b.txt"),"current b");
        var failure=org.junit.jupiter.api.Assertions.assertThrows(SnapshotRestoreException.class,()->manager.restoreSnapshot(target.commitId()));
        assertEquals(Path.of(changed?"later":"current"),Files.readSymbolicLink(project.resolve("a.link")));assertEquals("current b",Files.readString(project.resolve("b.txt")));
        if(changed)assertEquals(List.of("a.link"),failure.unrestoredFiles());else assertEquals(List.of("a.link"),failure.compensatedFiles());
    }

    @Test void restoresActualExecutableFileModeOnSupportedPlatform()throws Exception {
        Path project=Files.createDirectory(tempDir.resolve("mode-project"));Path file=project.resolve("run.sh");Files.writeString(file,"#!/bin/sh\nexit 0\n");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(project).supportsFileAttributeView("posix"),"真实文件系统不支持POSIX模式");
        Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        var manager=linkManager(project,"mode");var target=manager.preTurnSnapshot("before","executable");Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        assertTrue((Boolean)manager.previewRestore(target.commitId()).get("canRestore"));assertTrue(manager.restoreSnapshot(target.commitId()).success());assertTrue(Files.isExecutable(file));
    }

    @Test void linkBlobValidationAllowsDanglingInternalTargetsAndRejectsMalformedOrEscapingBytes()throws Exception {
        Path project=Files.createDirectory(tempDir.resolve("link-blob-validation"));var safety=new SnapshotPathSafety(project);Path leaf=safety.resolveLeaf("leaf.link");
        assertEquals(Path.of("missing/inside.txt"),safety.validateLinkTarget(leaf,"missing/inside.txt".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for(byte[] invalid:List.of(new byte[0],new byte[]{(byte)0xc3,0x28},new byte[]{'a',0,'b'},new byte[SnapshotPathSafety.MAX_LINK_TARGET_BYTES+1],"../outside".getBytes(java.nio.charset.StandardCharsets.UTF_8),"missing/../../outside".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,()->safety.validateLinkTarget(leaf,invalid));
        byte[] outAndBack=("../"+project.getFileName()+"/inside.txt").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,()->safety.validateLinkTarget(leaf,outAndBack));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,()->safety.resolveLeaf(""));org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,()->safety.resolveLeaf("."));org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,()->safety.resolveLeaf("../outside"));
        Files.writeString(project.resolve("not-dir"),"ordinary");org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,()->safety.resolveLeaf("not-dir/child"));
    }

    @Test void frozenLinkTopologyValidationCatchesCyclesAndEscapingIntermediateHops()throws Exception {
        Path project=Files.createDirectory(tempDir.resolve("link-topology-validation"));Files.writeString(project.resolve("inside.txt"),"inside");
        var safety=new SnapshotPathSafety(project);var leaf=safety.resolveLeaf("a.link");
        var modes=java.util.Map.of("a.link","120000","b.link","120000","inside.txt","100644");
        var links=new java.util.LinkedHashMap<String,byte[]>();links.put("a.link","b.link".getBytes(java.nio.charset.StandardCharsets.UTF_8));links.put("b.link","inside.txt".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(Path.of("b.link"),safety.validateLinkTarget(leaf,links.get("a.link"),links,modes));
        assertEquals(java.util.Set.of("b.link","inside.txt"),safety.linkDependencies(leaf,links.get("a.link"),links,modes));
        links.put("b.link","a.link".getBytes(java.nio.charset.StandardCharsets.UTF_8));org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,()->safety.validateLinkTarget(leaf,links.get("a.link"),links,modes));
        links.put("b.link","../outside/../link-topology-validation/inside.txt".getBytes(java.nio.charset.StandardCharsets.UTF_8));org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,()->safety.validateLinkTarget(leaf,links.get("a.link"),links,modes));
        var directoryModes=java.util.Map.of("a.link","120000","directory.link","120000");
        var directoryLinks=java.util.Map.of("a.link","directory.link/../inside.txt".getBytes(java.nio.charset.StandardCharsets.UTF_8),"directory.link",".".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,()->safety.validateLinkTarget(leaf,directoryLinks.get("a.link"),directoryLinks,directoryModes));
        Files.writeString(project.resolve("removed-parent"),"ordinary node scheduled for deletion");
        var deletedModes=new java.util.LinkedHashMap<String,String>();deletedModes.put("a.link","120000");deletedModes.put("removed-parent",null);
        byte[] dangling="removed-parent/future".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(Path.of("removed-parent/future"),safety.validateLinkTarget(leaf,dangling,java.util.Map.of("a.link",dangling),deletedModes));
        assertEquals(java.util.Set.of("removed-parent"),safety.linkDependencies(leaf,dangling,java.util.Map.of("a.link",dangling),deletedModes));
        Path nativeName=project.resolve("literal\\component.txt");Files.createDirectories(nativeName.getParent());Files.writeString(nativeName,"native filename");
        String nativeKey=project.relativize(nativeName).toString().replace(java.io.File.separatorChar,'/');
        byte[] nativeTarget=project.relativize(nativeName).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var nativeModes=java.util.Map.of("a.link","120000",nativeKey,"100644");
        assertEquals(java.util.Set.of(nativeKey),safety.linkDependencies(leaf,nativeTarget,java.util.Map.of("a.link",nativeTarget),nativeModes));
        // 使用真实宿主Path identity；无需创建链接即可验证冻结目标树的大小写别名。
        if(project.resolve("b.link").equals(project.resolve("B.link"))) {
            var aliasModes=java.util.Map.of("a.link","120000","b.link","120000");
            var aliasLinks=java.util.Map.of("a.link","B.link".getBytes(java.nio.charset.StandardCharsets.UTF_8),"b.link","a.link".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,()->safety.validateLinkTarget(leaf,aliasLinks.get("a.link"),aliasLinks,aliasModes));
            var duplicateModes=java.util.Map.of("b.link","100644","B.link","100644");
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,()->safety.validateLinkTarget(leaf,"b.link".getBytes(java.nio.charset.StandardCharsets.UTF_8),java.util.Map.of(),duplicateModes));
        }
    }

    @Test void restoresRealSafeLinkChainIncludingInternalDirectoryLinkTarget()throws Exception {
        Path project=Files.createDirectory(tempDir.resolve("link-chain"));requireRealLinks(project);Files.createDirectory(project.resolve("inside"));Files.writeString(project.resolve("inside/file.txt"),"kept");
        Files.createSymbolicLink(project.resolve("directory.link"),Path.of("inside"));Files.createSymbolicLink(project.resolve("b.link"),Path.of("directory.link/file.txt"));Files.createSymbolicLink(project.resolve("a.link"),Path.of("b.link"));
        var manager=linkManager(project,"link-chain");var target=manager.preTurnSnapshot("before","safe chain");Files.delete(project.resolve("a.link"));Files.delete(project.resolve("b.link"));Files.delete(project.resolve("directory.link"));
        assertTrue((Boolean)manager.previewRestore(target.commitId()).get("canRestore"));assertTrue(manager.restoreSnapshot(target.commitId()).success());
        assertEquals(Path.of("b.link"),Files.readSymbolicLink(project.resolve("a.link")));assertEquals("kept",Files.readString(project.resolve("a.link")));assertEquals("kept",Files.readString(project.resolve("inside/file.txt")));
    }

    @Test void frozenSnapshotLinkCycleIsRejectedEvenWhenCurrentNodesAreOrdinary()throws Exception {
        Path project=Files.createDirectory(tempDir.resolve("link-cycle-target"));requireRealLinks(project);
        Files.createSymbolicLink(project.resolve("a.link"),Path.of("b.link"));Files.createSymbolicLink(project.resolve("b.link"),Path.of("a.link"));
        var manager=linkManager(project,"link-cycle-target");var target=manager.preTurnSnapshot("before","cycle");Files.delete(project.resolve("a.link"));Files.delete(project.resolve("b.link"));Files.writeString(project.resolve("a.link"),"current a");Files.writeString(project.resolve("b.link"),"current b");
        assertFalse((Boolean)manager.previewRestore(target.commitId()).get("canRestore"));org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,()->manager.restoreSnapshot(target.commitId()));assertEquals("current a",Files.readString(project.resolve("a.link")));assertEquals("current b",Files.readString(project.resolve("b.link")));
    }

    @Test void safeLinkChainTransitionRestoresDependenciesBeforeReferrers()throws Exception {assertChainTransition(0);}
    @Test void safeLinkChainFailureCompensatesInReverseActualOrder()throws Exception {assertChainTransition(1);}
    @Test void safeLinkChainCompensationDoesNotOverwriteLaterLinkTarget()throws Exception {assertChainTransition(2);}
    private void assertChainTransition(int failureMode)throws Exception {
        Path project=Files.createDirectory(tempDir.resolve("chain-transition-"+failureMode));requireRealLinks(project);
        Files.writeString(project.resolve("c.txt"),"c");Files.writeString(project.resolve("later.txt"),"later");Files.writeString(project.resolve("z.txt"),"snapshot z");
        Files.createSymbolicLink(project.resolve("a.link"),Path.of("b.link"));Files.createSymbolicLink(project.resolve("b.link"),Path.of("c.txt"));
        var manager=new SideGitManager(project,new SnapshotConfig(true,tempDir.resolve("chain-transition-state-"+failureMode),50,List.of(".git"))) {
            @Override protected void beforeRestoreWrite(String path,boolean compensation)throws java.io.IOException {
                if(!compensation&&path.equals("z.txt")&&failureMode>0){if(failureMode==2){Files.delete(project.resolve("a.link"));Files.createSymbolicLink(project.resolve("a.link"),Path.of("later.txt"));}throw new java.io.IOException("fixture after both links");}
            }
        };
        var target=manager.preTurnSnapshot("before","a to b to c");replaceLink(project.resolve("a.link"),"c.txt");replaceLink(project.resolve("b.link"),"a.link");Files.writeString(project.resolve("z.txt"),"current z");
        assertTrue((Boolean)manager.previewRestore(target.commitId()).get("canRestore"));
        if(failureMode==0){assertTrue(manager.restoreSnapshot(target.commitId()).success());assertEquals(Path.of("b.link"),Files.readSymbolicLink(project.resolve("a.link")));assertEquals(Path.of("c.txt"),Files.readSymbolicLink(project.resolve("b.link")));}
        else {
            var failure=org.junit.jupiter.api.Assertions.assertThrows(SnapshotRestoreException.class,()->manager.restoreSnapshot(target.commitId()));
            assertEquals(List.of("b.link","a.link","z.txt"),failure.attemptedFiles());
            assertEquals(Path.of(failureMode==2?"later.txt":"c.txt"),Files.readSymbolicLink(project.resolve("a.link")));assertEquals(Path.of("a.link"),Files.readSymbolicLink(project.resolve("b.link")));assertEquals("current z",Files.readString(project.resolve("z.txt")));
            if(failureMode==2)assertEquals(List.of("a.link"),failure.unrestoredFiles());else{assertTrue(failure.unrestoredFiles().isEmpty());assertEquals(List.of("a.link","b.link"),failure.compensatedFiles());}
        }
        assertEquals("c",Files.readString(project.resolve("c.txt")));assertEquals("later",Files.readString(project.resolve("later.txt")));
    }

    @Test void failedLaterWriteCompensatesOnlyChangesMadeByThisRestore() throws Exception {assertCompensation(false);}
    @Test void compensationDoesNotOverwriteAnExternalChangeAndReportsItsPath() throws Exception {assertCompensation(true);}
    private void assertCompensation(boolean externalChange)throws Exception {
        Path project=Files.createDirectory(tempDir.resolve("rollback-project"));
        Files.writeString(project.resolve("a.txt"),"snapshot a");Files.writeString(project.resolve("b.txt"),"snapshot b");
        var manager=new SideGitManager(project,new SnapshotConfig(true,tempDir.resolve("rollback-state"),50,List.of(".git"))) {
            @Override protected void beforeRestoreWrite(String path,boolean compensation)throws java.io.IOException {
                if(!compensation&&path.equals("b.txt")) {if(externalChange)Files.writeString(project.resolve("a.txt"),"external later a");throw new java.io.IOException("fixture second write");}
            }
        };
        var target=manager.preTurnSnapshot("before","original");Files.writeString(project.resolve("a.txt"),"current a");Files.writeString(project.resolve("b.txt"),"current b");
        var failure=org.junit.jupiter.api.Assertions.assertThrows(SnapshotRestoreException.class,()->manager.restoreSnapshot(target.commitId()));
        assertEquals(target.commitId(),failure.targetCommitId());assertTrue(failure.backupCommitId().matches("[0-9a-f]{40}"));
        assertEquals("current b",Files.readString(project.resolve("b.txt")));assertEquals(List.of("a.txt","b.txt"),failure.attemptedFiles());
        if(externalChange){assertEquals("external later a",Files.readString(project.resolve("a.txt")));assertEquals(List.of("a.txt"),failure.unrestoredFiles());assertTrue(failure.compensatedFiles().isEmpty());}
        else{assertEquals("current a",Files.readString(project.resolve("a.txt")));assertEquals(List.of("a.txt"),failure.compensatedFiles());assertTrue(failure.unrestoredFiles().isEmpty());}
    }

    @Test void previewReportsActualHashesAddModifyDeleteAndTextDiffWithoutChangingWorktree() throws Exception {
        Path project=Files.createDirectory(tempDir.resolve("preview-project")),snapshots=tempDir.resolve("preview-state");
        Files.writeString(project.resolve("changed.txt"),"original\n");Files.writeString(project.resolve("missing.txt"),"restore me\n");
        var manager=new SideGitManager(project,new SnapshotConfig(true,snapshots,50,List.of(".git","target")));var target=manager.preTurnSnapshot("p","original");
        Files.writeString(project.resolve("changed.txt"),"actual current\n");Files.delete(project.resolve("missing.txt"));Files.writeString(project.resolve("extra.txt"),"remove me\n");
        var preview=manager.previewRestore(target.commitId());assertTrue((Boolean)preview.get("canRestore"));
        @SuppressWarnings("unchecked") var files=(List<SideGitManager.PreviewFile>)preview.get("files");assertEquals(3,files.size());
        var changed=files.stream().filter(row->row.path().equals("changed.txt")).findFirst().orElseThrow();assertEquals("MODIFY",changed.status());assertTrue(changed.currentHash().matches("[a-f0-9]{64}"));assertFalse(changed.currentHash().equals(changed.targetHash()));assertTrue(changed.diff().contains("-actual current"));assertTrue(changed.diff().contains("+original"));
        assertTrue(files.stream().anyMatch(row->row.status().equals("ADD")&&row.path().equals("missing.txt")));assertTrue(files.stream().anyMatch(row->row.status().equals("DELETE")&&row.path().equals("extra.txt")));
        assertEquals("actual current\n",Files.readString(project.resolve("changed.txt")));assertTrue(Files.exists(project.resolve("extra.txt")));assertFalse(Files.exists(project.resolve("missing.txt")));
    }

    @Test
    void restoresTrackedFilesToPreTurnSnapshot() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        Files.writeString(project.resolve("a.txt"), "before");

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git", "target", "*.class")));
        manager.preTurnSnapshot("turn-1", "before task");

        Files.writeString(project.resolve("a.txt"), "after");
        Files.writeString(project.resolve("new.txt"), "new file");
        manager.postTurnSnapshot("turn-1", "after task");

        RestoreResult result = manager.restorePreTurn(1);

        assertTrue(result.success());
        assertEquals("before", Files.readString(project.resolve("a.txt")));
        assertFalse(Files.exists(project.resolve("new.txt")));
        assertTrue(Files.exists(manager.gitDir().resolve("config")));
    }
    @Test void previewConflictCannotBeBypassedByDirectOrOrdinalRestore() throws Exception {
        Path project=Files.createDirectory(tempDir.resolve("conflict-project"));Files.writeString(project.resolve("a.txt"),"original");
        var manager=new SideGitManager(project,new SnapshotConfig(true,tempDir.resolve("conflict-state"),50,List.of(".git")));
        var target=manager.preTurnSnapshot("before","snapshot");Files.delete(project.resolve("a.txt"));Files.createDirectory(project.resolve("a.txt"));Files.writeString(project.resolve("a.txt/child.txt"),"later user content");
        var preview=manager.previewRestore(target.commitId());assertFalse((Boolean)preview.get("canRestore"));assertFalse(((List<?>)preview.get("conflicts")).isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,()->manager.restoreSnapshot(target.commitId()));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,()->manager.restorePreTurn(1));
        assertEquals("later user content",Files.readString(project.resolve("a.txt/child.txt")));assertTrue(Files.isDirectory(project.resolve("a.txt")));
    }

    @Test
    void serviceWritesPostTurnSnapshotAsynchronously() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        SnapshotConfig config = new SnapshotConfig(true, snapshots, 50, List.of(".git", "target"));
        SnapshotService service = new SnapshotService(new SideGitManager(project, config));

        String output = service.runTurn("react", "write file", () -> {
            Files.writeString(project.resolve("a.txt"), "created");
            return "ok";
        });
        service.awaitIdle();

        assertEquals("ok", output);
        List<TurnSnapshot> all = service.listSnapshots(10);
        assertEquals(2, all.size());
        assertEquals(SnapshotPhase.POST_TURN, all.get(0).phase());
        assertEquals(SnapshotPhase.PRE_TURN, all.get(1).phase());
    }
}
