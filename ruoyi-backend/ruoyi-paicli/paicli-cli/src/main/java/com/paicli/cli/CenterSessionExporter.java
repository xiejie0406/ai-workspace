package com.paicli.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.runner.RunnerPrivateDirectory;
import com.paicli.runtime.center.CenterCommandGateway;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 原Markdown导出与完整中心证据包；固定截止补齐账本后才落用户私有导出目录。 */
final class CenterSessionExporter {
    private static final ObjectMapper JSON = new ObjectMapper();
    record Export(Path markdown, Path bundle, int messages, int ledgerEntries) { }
    static Export export(CenterCommandGateway gateway, String sessionId, Map<String, Object> initial, Path directory) throws IOException {
        Map<String, Object> bundle = new LinkedHashMap<>(initial);
        List<Map<String, Object>> entries = JSON.convertValue(initial.getOrDefault("ledgerEntries", List.of()), new TypeReference<List<Map<String, Object>>>() {});
        entries = new ArrayList<>(entries);
        long end = number(initial.get("endSequence"), entries.stream().mapToLong(row -> number(row.get("sequence"), 0)).max().orElse(0));
        long cursor = number(initial.get("ledgerCursor"), entries.stream().mapToLong(row -> number(row.get("sequence"), 0)).max().orElse(0));
        boolean more = Boolean.TRUE.equals(initial.get("ledgerHasMore"));
        while (more) {
            var page = gateway.readLedger(sessionId, cursor, end, 200); if (!page.successful()) throw new IOException(page.message());
            var data = page.data(); long next = number(data.get("cursor"), -1);
            if (number(data.get("endSequence"), -1) != end || next <= cursor || next > end) throw new IOException("中心账本分页截止或游标无效，未导出部分文件");
            List<Map<String, Object>> rows = JSON.convertValue(data.get("entries"), new TypeReference<List<Map<String, Object>>>() {});
            if (rows == null || rows.isEmpty()) throw new IOException("中心账本分页缺少原记录");
            entries.addAll(rows); cursor = next; more = Boolean.TRUE.equals(data.get("hasMore"));
        }
        bundle.put("ledgerEntries", entries); bundle.put("ledgerHasMore", false); bundle.put("ledgerCursor", cursor);
        List<LlmClient.Message> messages = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            Object message = entry.get("message");
            if (message == null && entry.get("_ledger") instanceof Map<?, ?> raw) message = raw.get("message");
            if (message != null) messages.add(JSON.convertValue(message, LlmClient.Message.class));
        }
        Path root = RunnerPrivateDirectory.createDirectories(directory).toRealPath();
        String name = "session-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + "-" + UUID.randomUUID();
        Path json = root.resolve(name + ".json"), markdown = root.resolve(name + ".md");
        RunnerPrivateDirectory.writeAtomic(root, json.getFileName().toString(), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(bundle));
        String rendered = Main.renderConversationExport(messages, LocalDateTime.now());
        rendered += "\n\n完整中心证据包: " + json.getFileName() + "\n原始提示词包含: " + Boolean.TRUE.equals(initial.get("rawPromptIncluded")) + "\n";
        RunnerPrivateDirectory.writeAtomic(root, markdown.getFileName().toString(), rendered.getBytes(StandardCharsets.UTF_8));
        return new Export(markdown, json, messages.size(), entries.size());
    }
    private static long number(Object value, long fallback) { return value instanceof Number number ? number.longValue() : fallback; }
}
