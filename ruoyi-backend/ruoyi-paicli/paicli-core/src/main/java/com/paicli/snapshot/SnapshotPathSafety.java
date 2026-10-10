package com.paicli.snapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 快照专用路径规则：不跟随恢复叶节点，父路径拒绝链接，目标链逐跳校验根边界。 */
public final class SnapshotPathSafety {
    public static final int MAX_LINK_TARGET_BYTES = 16 * 1024;
    private final Path root;

    public SnapshotPathSafety(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        verifyRoot();
    }

    /** 返回词法叶节点；空串、根自身、越界及父目录链接均不允许。 */
    public Path resolveLeaf(String relative) throws IOException {
        verifyRoot();
        if (relative == null || relative.isBlank() || relative.indexOf('\0') >= 0)
            throw new IOException("快照叶路径无效");
        final Path input;
        try { input = Path.of(relative); }
        catch (InvalidPathException invalid) { throw new IOException("快照叶路径无效", invalid); }
        if (input.isAbsolute()) throw new IOException("快照叶路径必须相对工作根");
        Path leaf = root.resolve(input).normalize();
        if (!leaf.startsWith(root) || leaf.equals(root)) throw new IOException("快照叶路径越界或指向工作根");
        verifySegments(leaf.getParent(), true);
        return leaf;
    }

    /** 原始链接目标的严格UTF-8字节；返回前已执行与快照blob相同的安全校验。 */
    public byte[] readLinkTarget(Path leaf) throws IOException {
        Path checked = checkedLeaf(leaf);
        if (!Files.isSymbolicLink(checked)) throw new IOException("快照节点不是符号链接");
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(Files.readSymbolicLink(checked).toString()));
        byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
        validateLinkTarget(checked, bytes);
        return bytes;
    }

    /** 返回原始目标Path供createSymbolicLink使用；根内悬空目标允许，已有祖先必须可核实。 */
    public Path validateLinkTarget(Path leaf, byte[] bytes) throws IOException {
        return validateLinkTarget(leaf, bytes, Map.of(), Map.of());
    }

    /** 冻结目标树投影；mode键为根内相对节点，null表示将删除的当前节点。 */
    public Path validateLinkTarget(Path leaf, byte[] bytes, Map<String, byte[]> projectedLinks,
                                  Map<String, String> projectedModes) throws IOException {
        return validateLinkTarget(leaf, bytes, projection(projectedLinks,projectedModes));
    }

    Path validateLinkTarget(Path leaf, byte[] bytes, Projection projection) throws IOException {
        Path checked = checkedLeaf(leaf);
        Path target = decodeTarget(bytes);
        Path resolved = target.isAbsolute() ? target : checked.getParent().resolve(target);
        verifyTargetChain(resolved, projection, new HashSet<>());
        return target;
    }

    /** 一次冻结目标树只建一个索引；Path identity按宿主文件系统决定大小写，不全局小写。 */
    Projection projection(Map<String,byte[]> links,Map<String,String> modes)throws IOException {
        Map<Path,String> keys=new java.util.HashMap<>();
        Map<String,byte[]> frozenLinks=new java.util.LinkedHashMap<>();
        Map<String,String> frozenModes=new java.util.LinkedHashMap<>(modes);
        for(String key:modes.keySet()) {
            Path raw;
            try {raw=Path.of(key);}catch(InvalidPathException invalid){throw new IOException("快照投影节点路径无效",invalid);}
            Path node=root.resolve(raw).normalize();
            if(raw.isAbsolute()||key.isBlank()||!node.startsWith(root)||node.equals(root))throw new IOException("快照投影节点越界");
            String previous=keys.putIfAbsent(node,key);
            if(previous!=null&&!previous.equals(key))throw new IOException("多个快照节点映射同一实际路径");
        }
        links.forEach((key,bytes)->frozenLinks.put(key,bytes==null?null:bytes.clone()));
        return new Projection(java.util.Map.copyOf(keys),java.util.Collections.unmodifiableMap(frozenLinks),java.util.Collections.unmodifiableMap(frozenModes));
    }
    record Projection(Map<Path,String> keys,Map<String,byte[]> links,Map<String,String> modes) { }

    /** Core恢复顺序复用同一目标拓扑解析，依赖节点必须先完成，避免制造暂态链接环。 */
    Set<String> linkDependencies(Path leaf, byte[] bytes, Map<String, byte[]> projectedLinks,
                                 Map<String, String> projectedModes) throws IOException {
        return linkDependencies(leaf,bytes,projection(projectedLinks,projectedModes));
    }
    Set<String> linkDependencies(Path leaf,byte[] bytes,Projection projection)throws IOException {
        Path checked = checkedLeaf(leaf), target = decodeTarget(bytes);
        Set<String> dependencies = new HashSet<>();
        verifyTargetChain(target.isAbsolute() ? target : checked.getParent().resolve(target),
                projection, dependencies);
        return Set.copyOf(dependencies);
    }

    private Path decodeTarget(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_LINK_TARGET_BYTES)
            throw new IOException("快照链接目标为空或超过16KiB");
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        if (text.indexOf('\0') >= 0) throw new IOException("快照链接目标包含NUL");
        final Path target;
        try { target = Path.of(text); }
        catch (InvalidPathException invalid) { throw new IOException("快照链接目标无效", invalid); }
        return target;
    }

    /** 不预先折叠..；必须按真实链接展开顺序检查，不能允许先出根再返回。 */
    private void verifyTargetChain(Path requested,Projection projection, Set<String> dependencies) throws IOException {
        verifyRoot();
        ArrayDeque<Path> remaining = targetSegments(requested);
        Path current = root;
        Path absentPrefix = null;
        int hops = 1; // 包含正在校验/恢复的叶链接本身。
        Set<String> visited = new HashSet<>();
        while (!remaining.isEmpty()) {
            Path segment = remaining.removeFirst();
            if (segment.toString().isEmpty() || segment.toString().equals(".")) continue;
            if (segment.toString().equals("..")) {
                if (current.equals(root)) throw new IOException("快照链接目标超出工作根");
                current = current.getParent(); continue;
            }
            current = current.resolve(segment);
            String key = projection.keys().get(current);
            boolean projected = key != null;
            String mode = projected ? projection.modes().get(key) : null;
            if(projected)dependencies.add(key);
            boolean absent = absentPrefix != null && current.startsWith(absentPrefix);
            boolean linked = projected ? "120000".equals(mode) : !absent && Files.isSymbolicLink(current);
            if (linked) {
                if (++hops > 40 || !visited.add(current + "\0" + remaining))
                    throw new IOException("快照链接目标循环或超过40跳");
                byte[] targetBytes;
                if (projected) targetBytes = projection.links().get(key);
                else {
                    ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(CharBuffer.wrap(Files.readSymbolicLink(current).toString()));
                    targetBytes = new byte[encoded.remaining()]; encoded.get(targetBytes);
                }
                Path target = decodeTarget(targetBytes);
                ArrayDeque<Path> expanded = targetSegments(target.isAbsolute() ? target : current.getParent().resolve(target));
                expanded.addAll(remaining); remaining = expanded; current = root; absentPrefix = null;
                continue;
            }
            if (projected) {
                // 普通目标文件投影不读当前叶；删除节点按根内悬空目标处理。
                if (mode != null && !Set.of("100644", "100755").contains(mode))
                    throw new IOException("快照链接目标投影模式无效");
                if (mode != null && !remaining.isEmpty()) throw new IOException("快照链接目标祖先不是目录");
                if (mode == null && !absent) absentPrefix = current;
                continue;
            }
            if(absent)continue;
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) throw new IOException("无法核实快照链接目标");
                absentPrefix = current;
                continue;
            }
            if (!current.toRealPath().equals(current)) throw new IOException("快照链接目标含重定向");
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                    && (!remaining.isEmpty() || !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)))
                throw new IOException("快照链接目标不是普通文件或目录");
        }
    }

    private ArrayDeque<Path> targetSegments(Path absolute) throws IOException {
        if (!absolute.isAbsolute() || !absolute.startsWith(root)) throw new IOException("快照链接目标超出工作根");
        ArrayDeque<Path> segments = new ArrayDeque<>();
        // WindowsPath.relativize会先normalize；这里必须保留原始..的逐跳含义。
        for (int i=root.getNameCount();i<absolute.getNameCount();i++)segments.add(absolute.getName(i));
        return segments;
    }

    private Path checkedLeaf(Path leaf) throws IOException {
        if (leaf == null || !leaf.toAbsolutePath().normalize().startsWith(root))
            throw new IOException("快照叶路径越界");
        return resolveLeaf(root.relativize(leaf.toAbsolutePath().normalize()).toString());
    }

    private void verifyRoot() throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)
                || !root.toRealPath().equals(root)) throw new IOException("快照工作根含链接、重定向或不可核实");
    }

    private void verifySegments(Path path, boolean directoriesOnly) throws IOException {
        verifyRoot();
        Path current = root;
        for (Path segment : root.relativize(path)) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) throw new IOException("无法核实快照路径祖先");
                continue;
            }
            if (Files.isSymbolicLink(current) || !current.toRealPath().equals(current))
                throw new IOException("快照路径含链接或重定向");
            if ((directoriesOnly || !current.equals(path)) && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("快照路径祖先不是目录");
            if (!directoriesOnly && current.equals(path) && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("快照链接目标不是普通文件或目录");
        }
    }
}
