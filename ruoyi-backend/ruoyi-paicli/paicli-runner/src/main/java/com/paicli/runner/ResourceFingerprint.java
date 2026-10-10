package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Stream;

/** 摘要同时绑定路径和内容；不可读、越界链接或超限时拒绝，不能退回词法路径。 */
public final class ResourceFingerprint {
    private static final java.util.Set<String> SNAPSHOT_TREE_TOOLS = java.util.Set.of(
            "revert_turn", "snapshot__preview", "control__SNAPSHOT_PREVIEW", "control__SNAPSHOT_RESTORE");
    private final Path root;
    private final String rootFingerprint;
    public ResourceFingerprint(Path root) throws IOException {
        this.root = root.toRealPath();
        if (!Files.isDirectory(this.root)) throw new IOException("工作根必须是存在的目录");
        String identity = this.root.toString();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) identity = identity.toLowerCase(Locale.ROOT);
        rootFingerprint = CanonicalJson.sha256(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public Path root() { return root; }
    public String rootFingerprint() { return rootFingerprint; }
    public String capture(String tool, JsonNode arguments) throws IOException {
        // 正式预览、提案、消费前复核和恢复共用整个树的证明；参数不能缩小恢复核对范围。
        if (tool != null && SNAPSHOT_TREE_TOOLS.contains(tool)) return captureSnapshotTree();
        ObjectNode files = CanonicalJson.MAPPER.createObjectNode();
        Path requested = arguments.hasNonNull("path") ? resolve(arguments.path("path").asText()) : root;
        if ("create_project".equals(tool)) requested = resolve(arguments.path("name").asText());
        if (Files.isDirectory(requested)) {
            long bytes = 0; int count = 0;
            java.util.List<Path> entries = new java.util.ArrayList<>();
            Files.walkFileTree(requested, new SimpleFileVisitor<>() {
                private void collect(Path path) throws IOException {
                    verifyExisting(path);
                    if (entries.size() >= 100_000) throw new IOException("输入资源超过十万个条目，请缩小工作区");
                    entries.add(path);
                }
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException { collect(dir); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { collect(file); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException { throw failure; }
            });
            entries.sort(java.util.Comparator.naturalOrder());
                for (Path path : entries) {
                    verifyExisting(path);
                    if (++count > 100_000) throw new IOException("输入资源超过十万个条目，请缩小工作区");
                    if (Files.isRegularFile(path)) {
                        long size = Files.size(path); bytes += size;
                        if (size > 64L * 1024 * 1024 || bytes > 512L * 1024 * 1024) throw new IOException("输入资源摘要超过容量限制");
                        files.put(key(path), digestFile(path));
                    } else files.put(key(path), "directory");
                }
        } else if (Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) {
            verifyExisting(requested);
            if (!Files.isRegularFile(requested) || Files.size(requested) > 64L * 1024 * 1024) throw new IOException("输入资源不是允许大小的普通文件");
            files.put(key(requested), digestFile(requested));
        } else { files.put(key(requested), "absent"); }
        ObjectNode value = CanonicalJson.MAPPER.createObjectNode();
        value.put("rootFingerprint", rootFingerprint); value.set("resources", files);
        return CanonicalJson.hash(value);
    }
    /** 只在快照恢复边界支持安全叶软链；普通文件工具的链接拒绝规则保持原样。 */
    private String captureSnapshotTree() throws IOException {
        var safety = new com.paicli.snapshot.SnapshotPathSafety(root);
        java.util.List<Path> entries = new java.util.ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            private void collect(Path path) throws IOException {
                verifySnapshotNode(path, safety);
                if (entries.size() >= 100_000) throw new IOException("输入资源超过十万个条目，请缩小工作区");
                entries.add(path);
            }
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException { collect(dir); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { collect(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException { throw failure; }
        });
        entries.sort(java.util.Comparator.naturalOrder());
        ObjectNode files = CanonicalJson.MAPPER.createObjectNode();
        long bytes = 0;
        for (Path path : entries) {
            verifySnapshotNode(path, safety);
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                byte[] target = safety.readLinkTarget(path);
                bytes += target.length;
                if (bytes > 512L * 1024 * 1024) throw new IOException("输入资源摘要超过容量限制");
                files.putObject(snapshotKey(path)).put("mode", 0120000).put("sha256", CanonicalJson.sha256(target));
            } else if (attributes.isRegularFile()) {
                int mode = snapshotFileMode(path);
                SnapshotDigest content = digestSnapshotFile(path, 512L * 1024 * 1024 - bytes);
                verifySnapshotNode(path, safety);
                BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!after.isRegularFile() || attributes.size() != after.size()
                        || !attributes.lastModifiedTime().equals(after.lastModifiedTime())
                        || !java.util.Objects.equals(attributes.fileKey(), after.fileKey()) || mode != snapshotFileMode(path))
                    throw new IOException("摘要期间文件内容、类型或模式发生变化");
                bytes += content.bytes();
                files.putObject(snapshotKey(path)).put("mode", mode).put("sha256", content.hash());
            } else if (attributes.isDirectory()) files.put(snapshotKey(path), "directory");
            else throw new IOException("快照输入资源不是普通文件、目录或安全叶链接");
        }
        ObjectNode value = CanonicalJson.MAPPER.createObjectNode();
        value.put("rootFingerprint", rootFingerprint); value.set("resources", files);
        return CanonicalJson.hash(value);
    }
    private void verifySnapshotNode(Path path, com.paicli.snapshot.SnapshotPathSafety safety) throws IOException {
        if (!path.equals(root)) safety.resolveLeaf(root.relativize(path).toString());
        if (Files.isSymbolicLink(path)) safety.readLinkTarget(path);
        else if (!path.toRealPath().equals(path.toAbsolutePath().normalize())) throw new IOException("快照资源含重定向目录");
    }
    private String snapshotKey(Path path) { return root.relativize(path).toString().replace(java.io.File.separatorChar, '/'); }
    private static int snapshotFileMode(Path path) {
        return !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") && Files.isExecutable(path) ? 0100755 : 0100644;
    }
    private record SnapshotDigest(String hash, long bytes) { }
    private SnapshotDigest digestSnapshotFile(Path path, long remainingBytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long readBytes = 0;
            try (var channel = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = java.nio.ByteBuffer.allocate(8192);
                int read;
                while ((read = channel.read(buffer)) != -1) {
                    readBytes += read;
                    if (readBytes > 64L * 1024 * 1024 || readBytes > remainingBytes) throw new IOException("输入资源摘要超过容量限制");
                    buffer.flip(); digest.update(buffer); buffer.clear();
                }
            }
            return new SnapshotDigest(HexFormat.of().formatHex(digest.digest()), readBytes);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public Path resolve(String input) throws IOException {
        if (input == null || input.isBlank() || input.indexOf('\0') >= 0) throw new IOException("缺少有效资源路径");
        Path candidate = root.resolve(input).normalize();
        if (!candidate.startsWith(root)) throw new IOException("资源路径超出工作根");
        Path ancestor = candidate;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (ancestor == null || !ancestor.toRealPath().startsWith(root)) throw new IOException("资源链接超出工作根");
        return candidate;
    }
    private void verifyExisting(Path path) throws IOException {
        if (!path.toRealPath().startsWith(root) || Files.isSymbolicLink(path)) throw new IOException("工作区存在不可授权的链接资源");
    }
    private String key(Path path) { return root.relativize(path).toString().replace('\\', '/'); }
    private String digestFile(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) { byte[] buffer = new byte[8192]; int read; while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
