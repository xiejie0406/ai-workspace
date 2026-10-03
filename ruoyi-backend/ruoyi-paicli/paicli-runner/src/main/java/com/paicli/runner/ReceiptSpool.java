package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** 本地仅保存待上传执行证据；绝不据此重新执行工具或重新裁决任务。 */
public final class ReceiptSpool {
    private static final int MAX_PENDING = 1000;
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private final Path directory;
    public ReceiptSpool(Path directory) throws IOException {
        this.directory = RunnerPrivateDirectory.createDirectories(directory);
    }
    public Path stateDirectory(){return directory.getParent();}
    public synchronized Path intent(ObjectNode identity, String callId, String toolName, String argsHash, String resourceHash) throws IOException {
        List<Path> files = pending();
        long total = 0;
        for (Path path : files) total += Files.size(path);
        if (files.size() >= MAX_PENDING || total >= MAX_BYTES) throw new IOException("待上传证据已达上限，停止执行");
        ObjectNode value = identity.deepCopy();
        value.put("phase", "PREPARED"); value.put("toolCallId", callId); value.put("toolName", toolName);
        value.put("argsHash", argsHash); value.put("resourceHash", resourceHash);
        Path path = directory.resolve(UUID.randomUUID() + ".json");
        persist(path, value);
        return path;
    }
    public synchronized void consumed(Path path, String token) throws IOException {
        ObjectNode value = (ObjectNode) read(path); value.put("phase", "CONSUMED"); value.put("permitToken", token); persist(path, value);
    }
    public synchronized void receipt(Path path, JsonNode receipt) throws IOException {
        ObjectNode value = CanonicalJson.MAPPER.createObjectNode(); value.put("phase", "RECEIPT"); value.set("receipt", receipt); persist(path, value);
    }
    public synchronized void acknowledged(Path path) throws IOException { verifyPath(path); Files.delete(path); }
    public synchronized List<Path> pending() throws IOException {
        RunnerPrivateDirectory.requireDirectory(directory);
        try (Stream<Path> files = Files.list(directory)) { return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList(); }
    }
    public JsonNode read(Path path) throws IOException { verifyPath(path); if (Files.size(path) > 4 * 1024 * 1024) throw new IOException("回执证据过大"); return CanonicalJson.MAPPER.readTree(Files.readAllBytes(path)); }
    public boolean hasUncertainExecution(String taskId) throws IOException {
        for (Path path : pending()) { JsonNode item = read(path); if (!"RECEIPT".equals(item.path("phase").asText()) && taskId.equals(item.path("taskId").asText())) return true; }
        return false;
    }
    private void verifyPath(Path path) throws IOException {
        RunnerPrivateDirectory.requireDirectory(directory);
        if (!path.toAbsolutePath().normalize().getParent().equals(directory) || Files.isSymbolicLink(path)) throw new IOException("回执路径越界");
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))RunnerPrivateDirectory.requireRegularFile(path);
    }
    private void persist(Path path, JsonNode value) throws IOException {
        verifyPath(path);
        Path temp = directory.resolve(UUID.randomUUID() + ".tmp");
        try {
            byte[] data = CanonicalJson.MAPPER.writeValueAsBytes(value);
            if (data.length > 4 * 1024 * 1024) throw new IOException("回执超过上限");
            try (FileChannel channel = RunnerPrivateDirectory.createFile(temp)) {
                java.nio.ByteBuffer bytes=java.nio.ByteBuffer.wrap(data);
                while(bytes.hasRemaining())channel.write(bytes);
                channel.force(true);
            }
            verifyPath(path);
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { throw new IOException("回执存储不支持原子替换，停止执行", unsupported); }
        } finally { Files.deleteIfExists(temp); }
    }
}
