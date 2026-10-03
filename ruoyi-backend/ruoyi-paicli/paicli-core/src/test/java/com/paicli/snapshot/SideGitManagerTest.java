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
