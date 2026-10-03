package com.paicli.snapshot;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotCacheArchiveTest {
    @TempDir Path container;
    private SideGitManager manager()throws Exception {
        Path root=Files.createDirectory(container.resolve("workspace"));Files.writeString(root.resolve("a.txt"),"original");
        return new SideGitManager(root,new SnapshotConfig(true,container.resolve("state"),50,List.of(".git")));
    }
    @Test void completeCacheArchiveKeepsOriginalObjectsAndWorktreeAndCreatesEmptyActive()throws Exception {
        var snapshots=manager();var known=snapshots.preTurnSnapshot("1","known");Files.writeString(snapshots.projectRoot().resolve("a.txt"),"later");var unknown=snapshots.postTurnSnapshot("2","unregistered");
        Map<String,Object> proof=snapshots.snapshotCacheManifest();assertEquals(Set.of(known.commitId(),unknown.commitId()),new HashSet<>((List<?>)proof.get("commits")));
        Path archive=Files.createDirectory(container.resolve("archive")).resolve("repository");snapshots.archiveSnapshotCache((String)proof.get("repositoryManifestHash"),archive);
        assertEquals("later",Files.readString(snapshots.projectRoot().resolve("a.txt")));assertTrue(snapshots.listSnapshots(50).isEmpty());
        assertTrue(Files.isRegularFile(archive.resolve(".git/config")));assertTrue(((Number)proof.get("fileCount")).intValue()>0);
        try(var repository=new FileRepositoryBuilder().setGitDir(archive.resolve(".git").toFile()).setWorkTree(snapshots.projectRoot().toFile()).build();var git=new Git(repository)) {
            assertEquals(unknown.commitId(),repository.resolve("HEAD").name());assertEquals(2,java.util.stream.StreamSupport.stream(git.log().call().spliterator(),false).count());
        }
    }
    @Test void laterCommitOrCacheFileInvalidatesApprovedManifestWithoutMovingActive()throws Exception {
        var snapshots=manager();snapshots.preTurnSnapshot("1","known");var proof=snapshots.snapshotCacheManifest();Path archive=Files.createDirectory(container.resolve("archive")).resolve("repository");
        Files.writeString(snapshots.projectRoot().resolve("a.txt"),"later");var later=snapshots.postTurnSnapshot("2","later");
        assertThrows(IOException.class,()->snapshots.archiveSnapshotCache((String)proof.get("repositoryManifestHash"),archive));assertFalse(Files.exists(archive));assertEquals(later.commitId(),snapshots.listSnapshots(1).get(0).commitId());
        var next=snapshots.snapshotCacheManifest();Files.writeString(snapshots.gitDir().getParent().resolve("operator-note.txt"),"after preview");
        assertThrows(IOException.class,()->snapshots.archiveSnapshotCache((String)next.get("repositoryManifestHash"),archive));assertFalse(Files.exists(archive));assertEquals("later",Files.readString(snapshots.projectRoot().resolve("a.txt")));
    }
    @Test void unpreparedBackupFailsBeforeAnyCacheRemoval()throws Exception {
        var snapshots=manager();var target=snapshots.preTurnSnapshot("1","original");var proof=snapshots.snapshotCacheManifest();
        assertThrows(IOException.class,()->snapshots.archiveSnapshotCache((String)proof.get("repositoryManifestHash"),container.resolve("missing/repository")));
        assertEquals(target.commitId(),snapshots.listSnapshots(1).get(0).commitId());assertEquals(proof.get("repositoryManifestHash"),snapshots.snapshotCacheManifest().get("repositoryManifestHash"));
    }
}
