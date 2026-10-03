package com.paicli.snapshot;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SideGitManager {
    private static final PersonIdent SNAPSHOT_IDENT = new PersonIdent("PaiCLI Snapshot", "snapshot@paicli.local");

    private final Path projectRoot;
    private final SnapshotConfig config;
    private final Path gitDir;

    public SideGitManager(Path projectRoot) {
        this(projectRoot, SnapshotConfig.fromEnvironment());
    }

    public SideGitManager(Path projectRoot, SnapshotConfig config) {
        this.projectRoot = normalizeProjectRoot(projectRoot);
        this.config = config == null ? SnapshotConfig.fromEnvironment() : config;
        this.gitDir = this.config.snapshotsRoot()
                .resolve(hash(parentKey(this.projectRoot)))
                .resolve(hash(this.projectRoot.toString()))
                .resolve(".git");
    }

    public synchronized TurnSnapshot preTurnSnapshot(String turnId, String summary) throws IOException, GitAPIException {
        return createSnapshot(SnapshotPhase.PRE_TURN, turnId, summary);
    }

    public synchronized TurnSnapshot postTurnSnapshot(String turnId, String summary) throws IOException, GitAPIException {
        return createSnapshot(SnapshotPhase.POST_TURN, turnId, summary);
    }

    public synchronized TurnSnapshot preRestoreSnapshot(String turnId, String summary) throws IOException, GitAPIException {
        return createSnapshot(SnapshotPhase.PRE_RESTORE, turnId, summary);
    }

    public synchronized TurnSnapshot createSnapshot(SnapshotPhase phase, String turnId, String summary)
            throws IOException, GitAPIException {
        if (!config.enabled()) {
            return null;
        }
        try (Git git = openGit()) {
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call();
            String message = phase.label() + " " + safeTurnId(turnId)
                    + (summary == null || summary.isBlank() ? "" : "\n\n" + summary.trim());
            RevCommit commit = git.commit()
                    .setAllowEmpty(true)
                    .setAuthor(SNAPSHOT_IDENT)
                    .setCommitter(SNAPSHOT_IDENT)
                    .setMessage(message)
                    .call();
            return toSnapshot(commit);
        }
    }

    public synchronized List<TurnSnapshot> listSnapshots(int limit) throws IOException, GitAPIException {
        if (!config.enabled() || !Files.exists(gitDir.resolve("config"))) {
            return List.of();
        }
        int max = limit <= 0 ? config.maxSnapshots() : limit;
        List<TurnSnapshot> snapshots = new ArrayList<>();
        try (Git git = openGit()) {
            if(git.getRepository().resolve("HEAD")==null)return List.of();
            for (RevCommit commit : git.log().setMaxCount(max).call()) {
                snapshots.add(toSnapshot(commit));
            }
        }
        return snapshots;
    }

    public synchronized List<TurnSnapshot> listPreTurnSnapshots(int limit) throws IOException, GitAPIException {
        return listSnapshots(limit <= 0 ? config.maxSnapshots() * 4 : limit).stream()
                .filter(snapshot -> snapshot.phase() == SnapshotPhase.PRE_TURN)
                .limit(limit <= 0 ? config.maxSnapshots() : limit)
                .toList();
    }

    public synchronized RestoreResult restorePreTurn(int offset) throws IOException, GitAPIException {
        if (!config.enabled()) {
            return RestoreResult.failure("快照功能已关闭");
        }
        int normalizedOffset = Math.max(1, offset);
        List<TurnSnapshot> preTurns = listPreTurnSnapshots(Math.max(normalizedOffset, config.maxSnapshots()));
        if (preTurns.size() < normalizedOffset) {
            return RestoreResult.failure("找不到最近第 " + normalizedOffset + " 个 pre-turn 快照");
        }
        TurnSnapshot target = preTurns.get(normalizedOffset - 1);
        return restoreSnapshot(target.commitId());
    }

    public synchronized String formatStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("📸 Side-Git 快照状态\n");
        sb.append("   状态: ").append(config.enabled() ? "启用" : "关闭").append('\n');
        sb.append("   项目根: ").append(projectRoot).append('\n');
        sb.append("   Side-Git: ").append(gitDir).append('\n');
        sb.append("   最大展示/保留数: ").append(config.maxSnapshots()).append('\n');
        sb.append("   排除: ").append(String.join(", ", config.excludes())).append('\n');
        try {
            List<TurnSnapshot> snapshots = listSnapshots(1);
            if (snapshots.isEmpty()) {
                sb.append("   最近快照: 暂无");
            } else {
                TurnSnapshot latest = snapshots.get(0);
                sb.append("   最近快照: ")
                        .append(latest.phase().label())
                        .append(" ")
                        .append(latest.shortCommitId())
                        .append(" ")
                        .append(latest.createdAt());
            }
        } catch (Exception e) {
            sb.append("   最近快照: 读取失败 - ").append(e.getMessage());
        }
        return sb.toString();
    }

    /** 按中心冻结commit预览；不按可变化的最近第N条选择。 */
    public synchronized Map<String, Object> previewRestore(String commitId) throws IOException, GitAPIException {
        requireSnapshot(commitId);
        try (Git git = openGit()) {
            Map<String, ObjectId> target = treeEntries(git.getRepository(), ObjectId.fromString(commitId));
            com.paicli.policy.PathGuard guard = new com.paicli.policy.PathGuard(projectRoot.toString());
            var paths = new java.util.TreeSet<>(target.keySet());
            ObjectId head = git.getRepository().resolve("HEAD"); if (head != null) paths.addAll(treeEntries(git.getRepository(), head).keySet());
            try (TreeWalk current = new TreeWalk(git.getRepository())) {
                current.addTree(new org.eclipse.jgit.treewalk.FileTreeIterator(git.getRepository()));
                current.setRecursive(true); current.setFilter(new org.eclipse.jgit.treewalk.filter.NotIgnoredFilter(0));
                while (current.next()) if (!isExcluded(current.getPathString())) paths.add(current.getPathString());
            }
            Map<String,String> modes = treeModes(git.getRepository(), ObjectId.fromString(commitId));
            List<PreviewFile> files = new ArrayList<>(); List<String> conflicts = new ArrayList<>();
            for (String path : paths) {
                if (hasLinkedAncestor(path)) { conflicts.add(path + ": 路径含链接或重定向目录"); continue; }
                Path node = guard.resolveSafe(path);
                boolean exists = Files.exists(node, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (exists && (!Files.isRegularFile(node, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(node))) { conflicts.add(path + ": 非普通文件"); continue; }
                String targetMode = modes.get(path);
                if (target.containsKey(path) && (!List.of("100644", "100755").contains(targetMode) || isWindows() && "100755".equals(targetMode))) { conflicts.add(path + ": 不支持的快照节点模式"); continue; }
                byte[] before = exists ? boundedFile(node) : null;
                byte[] after = target.containsKey(path) ? git.getRepository().open(target.get(path), org.eclipse.jgit.lib.Constants.OBJ_BLOB).getBytes(64 * 1024 * 1024) : null;
                String currentHash = before == null ? null : digest(before), targetHash = after == null ? null : digest(after);
                String currentMode = exists ? Files.isExecutable(node) && !isWindows() ? "100755" : "100644" : null;
                String status = before == null ? "ADD" : after == null ? "DELETE" : currentHash.equals(targetHash) && java.util.Objects.equals(currentMode, targetMode) ? "UNCHANGED" : "MODIFY";
                if (before != null || after != null) files.add(new PreviewFile(path,status,currentHash,targetHash,
                        currentMode==null?null:Integer.parseInt(currentMode,8),targetMode==null?null:Integer.parseInt(targetMode,8),previewDiff(path,before,after)));
            }
            Map<String,Object> preview = new LinkedHashMap<>(); preview.put("commitId",commitId); preview.put("restoredFiles",List.copyOf(target.keySet()));
            preview.put("files",List.copyOf(files)); preview.put("canRestore",conflicts.isEmpty()); preview.put("conflicts",List.copyOf(conflicts)); return preview;
        }
    }

    public record PreviewFile(String path,String status,String currentHash,String targetHash,Integer currentMode,Integer targetMode,String diff) { }
    private boolean hasLinkedAncestor(String relative) throws IOException {
        Path requested = projectRoot.resolve(relative).normalize();
        if (!requested.startsWith(projectRoot)) return true;
        Path current = projectRoot;
        for (Path segment : projectRoot.relativize(requested)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) return true;
            if (Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !current.toRealPath().equals(current.toAbsolutePath().normalize())) return true;
        }
        return false;
    }
    private static void requireRestorable(Map<String,Object> preview) throws IOException {
        if (!Boolean.TRUE.equals(preview.get("canRestore"))) throw new IOException("快照存在不可恢复的节点或文件模式，请先处理冲突");
    }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"); }
    private static byte[] boundedFile(Path path) throws IOException { if(Files.size(path)>64L*1024*1024)throw new IOException("预览文件超过64MiB支持范围");return Files.readAllBytes(path); }
    private static String digest(byte[] bytes) {try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);} }
    private static String previewDiff(String path,byte[] before,byte[] after) throws IOException {
        byte[] current=before==null?new byte[0]:before,target=after==null?new byte[0]:after;
        if(current.length>256*1024||target.length>256*1024||org.eclipse.jgit.diff.RawText.isBinary(current)||org.eclipse.jgit.diff.RawText.isBinary(target))return null;
        try {StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(current));StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(target));}
        catch(java.nio.charset.CharacterCodingException binary){return null;}
        var oldText=new org.eclipse.jgit.diff.RawText(current);var newText=new org.eclipse.jgit.diff.RawText(target);
        var edits=org.eclipse.jgit.diff.DiffAlgorithm.getAlgorithm(org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm.HISTOGRAM).diff(org.eclipse.jgit.diff.RawTextComparator.DEFAULT,oldText,newText);
        if(edits.isEmpty())return "";
        var output=new java.io.ByteArrayOutputStream();try(var formatter=new org.eclipse.jgit.diff.DiffFormatter(output)){formatter.format(edits,oldText,newText);}
        return "--- current/"+path+"\n+++ snapshot/"+path+"\n"+output.toString(StandardCharsets.UTF_8);
    }
    private Map<String,String> treeModes(Repository repository,ObjectId commitId)throws IOException {
        Map<String,String> modes=new LinkedHashMap<>();try(RevWalk rev=new RevWalk(repository);TreeWalk tree=new TreeWalk(repository)) {tree.addTree(rev.parseCommit(commitId).getTree());tree.setRecursive(true);while(tree.next())if(!isExcluded(tree.getPathString()))modes.put(tree.getPathString(),Integer.toOctalString(tree.getFileMode(0).getBits()));}return modes;
    }

    public synchronized RestoreResult restoreSnapshot(String commitId) throws IOException, GitAPIException {
        requireSnapshot(commitId);
        requireRestorable(previewRestore(commitId)); // 全目标路径在任何写入前验证。
        TurnSnapshot backup = preRestoreSnapshot("restore-" + Instant.now().toEpochMilli(), "Before restoring " + commitId);
        try (Git git = openGit()) {
            Map<String, ObjectId> target = treeEntries(git.getRepository(), ObjectId.fromString(commitId));
            Map<String, ObjectId> current = treeEntries(git.getRepository(), ObjectId.fromString(backup.commitId()));
            return restoreTree(git.getRepository(),commitId,backup.commitId(),current,target,
                    treeModes(git.getRepository(),ObjectId.fromString(backup.commitId())),treeModes(git.getRepository(),ObjectId.fromString(commitId)));
        }
    }

    private void requireSnapshot(String commitId) throws IOException, GitAPIException {
        if (!config.enabled() || commitId == null || !commitId.matches("[0-9a-f]{40}"))
            throw new IOException("快照内容不存在或不属于当前工作区");
        try(Git git=openGit();RevWalk revisions=new RevWalk(git.getRepository())) {
            ObjectId head=git.getRepository().resolve("HEAD");
            if(head==null||!revisions.isMergedInto(revisions.parseCommit(ObjectId.fromString(commitId)),revisions.parseCommit(head)))
                throw new IOException("快照内容不存在或不属于当前工作区");
        }
    }

    public synchronized String cleanSnapshots() {
        if (!Files.exists(gitDir)) {
            return "📭 暂无 Side-Git 快照目录";
        }
        try {
            deleteRecursively(gitDir.getParent());
            return "🧹 已清理当前项目的 Side-Git 快照目录: " + gitDir.getParent();
        } catch (IOException e) {
            return "❌ 清理快照失败: " + e.getMessage();
        }
    }

    /** 同root缓存的完整清理预览；包含悬空commit和所有缓存文件，不创建缺失仓库。 */
    public synchronized Map<String,Object> snapshotCacheManifest()throws IOException {
        return cacheManifestAt(gitDir.getParent());
    }
    private Map<String,Object> cacheManifestAt(Path cache)throws IOException {
        List<Map<String,Object>> rows=new ArrayList<>();long total=0;int files=0;String head=null;
        List<Map<String,String>> refs=new ArrayList<>();var commits=new java.util.TreeSet<String>();
        if(Files.exists(cache,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Path real=cache.toRealPath();if(!real.equals(cache.toAbsolutePath().normalize())||Files.isSymbolicLink(cache))throw new IOException("快照缓存路径含重定向");
            try(var walk=Files.walk(cache)) {
                List<Path> nodes=walk.limit(100_001).sorted().toList();
                if(nodes.size()>100_000)throw new IOException("快照缓存超过十万个节点，保留原缓存，未清理");
                for(Path node:nodes) {
                    if(node.equals(cache))continue;var attrs=Files.readAttributes(node,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    if(Files.isSymbolicLink(node)||!node.toRealPath().equals(node.toAbsolutePath().normalize())||!(attrs.isDirectory()||attrs.isRegularFile()))throw new IOException("快照缓存包含特殊节点或链接");
                    Map<String,Object> row=new java.util.TreeMap<>();row.put("path",cache.relativize(node).toString().replace('\\','/'));row.put("type",attrs.isDirectory()?"DIRECTORY":"FILE");
                    if(attrs.isRegularFile()) {
                        MessageDigest hash;try{hash=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
                        long size=0;try(var input=Files.newInputStream(node)){byte[] bytes=new byte[256*1024];int length;while((length=input.read(bytes))!=-1){hash.update(bytes,0,length);size+=length;}}
                        var after=Files.readAttributes(node,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
                        if(size!=attrs.size()||size!=after.size()||!attrs.lastModifiedTime().equals(after.lastModifiedTime())||!java.util.Objects.equals(attrs.fileKey(),after.fileKey()))throw new IOException("快照清理预览期间缓存改变");
                        row.put("size",size);row.put("sha256",java.util.HexFormat.of().formatHex(hash.digest()));total=Math.addExact(total,size);files++;
                    }
                    rows.add(row);
                }
            }
            Path repositoryDir=cache.resolve(".git");
            if(Files.isRegularFile(repositoryDir.resolve("config")))try(Repository repository=new FileRepositoryBuilder().setGitDir(repositoryDir.toFile()).setWorkTree(projectRoot.toFile()).build();var reader=repository.newObjectReader()) {
                ObjectId resolved=repository.resolve("HEAD");head=resolved==null?null:resolved.name();
                for(var ref:repository.getRefDatabase().getRefsByPrefix(org.eclipse.jgit.lib.Constants.R_REFS))if(ref.getObjectId()!=null)refs.add(Map.of("name",ref.getName(),"objectId",ref.getObjectId().name()));
                refs.sort(java.util.Comparator.comparing(row->row.get("name")));
                var ids=new java.util.TreeSet<String>();Path objects=repositoryDir.resolve("objects");
                if(Files.isDirectory(objects))try(var walk=Files.walk(objects)){for(Path node:walk.filter(Files::isRegularFile).limit(100_000).toList()){
                    String relative=objects.relativize(node).toString().replace('\\','/');
                    if(relative.matches("[a-f0-9]{2}/[a-f0-9]{38}"))ids.add(relative.replace("/",""));
                    else if(relative.matches("pack/pack-[a-f0-9]{40}\\.idx"))for(var entry:org.eclipse.jgit.internal.storage.file.PackIndex.open(node.toFile())){ids.add(entry.name());if(ids.size()>100_000)throw new IOException("快照对象超过十万个，保留原缓存，未清理");}
                }}
                for(String id:ids)if(reader.open(ObjectId.fromString(id)).getType()==org.eclipse.jgit.lib.Constants.OBJ_COMMIT)commits.add(id);
            }
        }
        byte[] manifest=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(rows);
        if(manifest.length>16*1024*1024)throw new IOException("快照完整清单超过16MiB，保留原缓存，未清理");
        Map<String,Object> result=new LinkedHashMap<>();result.put("head",head);result.put("refs",refs);result.put("commits",List.copyOf(commits));result.put("repositoryManifestHash",digest(manifest));result.put("fileCount",files);result.put("totalBytes",total);result.put("manifest",rows);return result;
    }
    /** 已批准完整缓存摘要的原子归档；任何后来的文件/ref/commit变化均拒绝。 */
    public synchronized void archiveSnapshotCache(String expectedHash,Path repositoryArchive)throws IOException {
        Map<String,Object> before=snapshotCacheManifest();if(!java.util.Objects.equals(expectedHash,before.get("repositoryManifestHash")))throw new IOException("快照缓存在批准后改变，未清理");
        if(Files.exists(repositoryArchive,java.nio.file.LinkOption.NOFOLLOW_LINKS)||!Files.isDirectory(repositoryArchive.getParent()))throw new IOException("归档目标须是已准备私有目录内的新节点");
        Path active=gitDir.getParent();boolean moved=false;
        try {
            if(Files.exists(active)){Files.move(active,repositoryArchive,java.nio.file.StandardCopyOption.ATOMIC_MOVE);moved=true;}else Files.createDirectory(repositoryArchive);
            if(!java.util.Objects.equals(expectedHash,cacheManifestAt(repositoryArchive).get("repositoryManifestHash")))throw new IOException("归档缓存摘要未确认");
            try(Git ignored=openGit()){ /* 备份摘要已确认后才建立新的空active仓库。 */ }
        }catch(IOException | GitAPIException error) {
            IOException failure=error instanceof IOException io?io:new IOException("空快照仓库初始化失败",error);
            if(moved&&!Files.exists(active))try{Files.move(repositoryArchive,active,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}catch(IOException rollback){failure.addSuppressed(rollback);}
            throw failure;
        }
    }

    public Path gitDir() {
        return gitDir;
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public SnapshotConfig config() {
        return config;
    }

    private Git openGit() throws IOException, GitAPIException {
        Files.createDirectories(gitDir.getParent());
        if (!Files.exists(gitDir.resolve("config"))) {
            Git.init()
                    .setGitDir(gitDir.toFile())
                    .setDirectory(projectRoot.toFile())
                    .call()
                    .close();
        }
        writeExcludeFile();
        Repository repository = new FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .setWorkTree(projectRoot.toFile())
                .build();
        return new Git(repository);
    }

    private void writeExcludeFile() throws IOException {
        Path info = gitDir.resolve("info");
        Files.createDirectories(info);
        StringBuilder sb = new StringBuilder("# Managed by PaiCLI side-history snapshots\n");
        for (String exclude : config.excludes()) {
            sb.append(exclude).append('\n');
        }
        Files.writeString(info.resolve("exclude"), sb.toString(), StandardCharsets.UTF_8);
    }

    private TurnSnapshot toSnapshot(RevCommit commit) {
        String firstLine = Optional.ofNullable(commit.getShortMessage()).orElse("");
        String[] parts = firstLine.split("\\s+", 2);
        SnapshotPhase phase = parsePhase(parts.length > 0 ? parts[0] : "");
        String turnId = parts.length > 1 ? parts[1] : "";
        return new TurnSnapshot(
                commit.getId().name(),
                phase,
                turnId,
                Instant.ofEpochSecond(commit.getCommitTime()),
                firstLine
        );
    }

    private SnapshotPhase parsePhase(String value) {
        for (SnapshotPhase phase : SnapshotPhase.values()) {
            if (phase.label().equals(value)) {
                return phase;
            }
        }
        return SnapshotPhase.POST_TURN;
    }

    private Map<String, ObjectId> treeEntries(Repository repository, ObjectId commitId) throws IOException {
        Map<String, ObjectId> entries = new LinkedHashMap<>();
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(commitId);
            try (TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(commit.getTree());
                treeWalk.setRecursive(true);
                while (treeWalk.next()) {
                    String path = treeWalk.getPathString();
                    if (!isExcluded(path)) {
                        entries.put(path, treeWalk.getObjectId(0));
                    }
                }
            }
        }
        return entries;
    }

    private RestoreResult restoreTree(Repository repository,String targetCommit,String backupCommit,
            Map<String,ObjectId> current,Map<String,ObjectId> target,Map<String,String> currentModes,Map<String,String> targetModes)throws IOException {
        List<RestoreChange> attempted=new ArrayList<>();List<String> restored=new ArrayList<>(),removed=new ArrayList<>();
        var paths=new java.util.TreeSet<String>();paths.addAll(current.keySet());paths.addAll(target.keySet());
        try {
            for(String path:paths) {
                ObjectId previous=current.get(path),next=target.get(path);String previousMode=currentModes.get(path),nextMode=targetModes.get(path);
                if(java.util.Objects.equals(previous,next)&&java.util.Objects.equals(previousMode,nextMode))continue;
                byte[] previousBytes=blob(repository,previous),nextBytes=blob(repository,next);
                Path file=restorePath(path);requireCurrentValue(file,previousBytes,previousMode);
                RestoreChange change=new RestoreChange(path,previous,previousMode,next,nextMode);attempted.add(change);
                beforeRestoreWrite(path,false);
                if(next==null){Files.delete(file);removed.add(path);}else{writeRestoreFile(file,nextBytes,nextMode);restored.add(path);}
            }
            return RestoreResult.success(targetCommit,restored,removed);
        } catch(IOException | RuntimeException error) {
            IOException failure=error instanceof IOException io?io:new IOException("快照文件写入异常",error);
            List<String> compensated=new ArrayList<>(),unrestored=new ArrayList<>();
            for(int i=attempted.size()-1;i>=0;i--) {
                RestoreChange change=attempted.get(i);
                try {
                    Path file=restorePath(change.path());byte[] previousBytes=blob(repository,change.previous()),nextBytes=blob(repository,change.next());
                    if(matches(file,previousBytes,change.previousMode()))continue;
                    // 后续外部修改不被补偿覆盖；与本次目标不同的内容留给人工核对。
                    if(!matches(file,nextBytes,change.nextMode()))throw new IOException("文件在恢复后再次改变");
                    beforeRestoreWrite(change.path(),true);
                    if(previousBytes==null){Files.delete(file);pruneEmptyParents(file.getParent());}
                    else writeRestoreFile(file,previousBytes,change.previousMode());
                    compensated.add(change.path());
                }catch(IOException compensationFailure){unrestored.add(change.path());failure.addSuppressed(compensationFailure);}
            }
            throw new SnapshotRestoreException(targetCommit,backupCommit,attempted.stream().map(RestoreChange::path).toList(),compensated,unrestored,failure);
        }
    }
    private record RestoreChange(String path,ObjectId previous,String previousMode,ObjectId next,String nextMode) { }
    /** 故障注入点位于文件变更之前，不改变实际原子写入和补偿逻辑。 */
    protected void beforeRestoreWrite(String relativePath,boolean compensation)throws IOException { }
    private Path restorePath(String relative)throws IOException {
        if(hasLinkedAncestor(relative))throw new IOException("恢复路径含链接或重定向目录");
        Path file=new com.paicli.policy.PathGuard(projectRoot.toString()).resolveSafe(relative);
        if(Files.exists(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IOException("恢复目标不是普通文件");
        return file;
    }
    private static byte[] blob(Repository repository,ObjectId id)throws IOException {return id==null?null:repository.open(id,org.eclipse.jgit.lib.Constants.OBJ_BLOB).getBytes(64*1024*1024);}
    private static boolean matches(Path file,byte[] expected,String mode)throws IOException {
        if(expected==null)return !Files.exists(file,java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if(!Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))return false;
        String actualMode=Files.isExecutable(file)&&!isWindows()?"100755":"100644";
        return java.util.Objects.equals(mode,actualMode)&&digest(expected).equals(digest(boundedFile(file)));
    }
    private static void requireCurrentValue(Path file,byte[] expected,String mode)throws IOException {if(!matches(file,expected,mode))throw new IOException("恢复执行前文件已改变");}
    private void writeRestoreFile(Path file,byte[] bytes,String mode)throws IOException {
        Files.createDirectories(file.getParent());Path stage=Files.createTempFile(file.getParent(),".paicli-restore-",".tmp");
        try {
            Files.write(stage,bytes);
            if(!isWindows()) {
                var permissions=Files.exists(file)?Files.getPosixFilePermissions(file):Files.getPosixFilePermissions(stage);
                if("100755".equals(mode))permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
                else permissions.removeIf(permission->permission==java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE||permission==java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE||permission==java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(stage,permissions);
            }
            restorePath(projectRoot.relativize(file).toString());
            Files.move(stage,file,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(stage);}
    }

    private void pruneEmptyParents(Path dir) throws IOException {
        Path current = dir;
        while (current != null && current.startsWith(projectRoot) && !current.equals(projectRoot)) {
            try (var stream = Files.list(current)) {
                if (stream.findAny().isPresent()) {
                    return;
                }
            }
            Files.deleteIfExists(current);
            current = current.getParent();
        }
    }

    private boolean isExcluded(String relative) {
        String normalized = relative.replace('\\', '/');
        for (String raw : config.excludes()) {
            String pattern = raw == null ? "" : raw.trim().replace('\\', '/');
            if (pattern.isEmpty()) {
                continue;
            }
            if (pattern.endsWith("/")) {
                pattern = pattern.substring(0, pattern.length() - 1);
            }
            if (normalized.equals(pattern) || normalized.startsWith(pattern + "/")) {
                return true;
            }
            if (pattern.contains("*")) {
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                Path fileName = Path.of(normalized).getFileName();
                if (fileName != null && matcher.matches(fileName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Path normalizeProjectRoot(Path path) {
        Path root = path == null ? Path.of(System.getProperty("user.dir")) : path;
        return root.toAbsolutePath().normalize();
    }

    private static String parentKey(Path path) {
        Path parent = path.getParent();
        return parent == null ? path.toString() : parent.toString();
    }

    private static String safeTurnId(String turnId) {
        return turnId == null || turnId.isBlank() ? "turn-" + Instant.now().toEpochMilli() : turnId.trim();
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
