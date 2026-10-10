package com.paicli.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.runner.RunnerPrivateDirectory;
import com.paicli.runtime.center.CenterCommandGateway;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
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
        ExportBudget budget=new ExportBudget(gateway.browserHosted());
        budget.add(initial);
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
            budget.add(rows); entries.addAll(rows); cursor = next; more = Boolean.TRUE.equals(data.get("hasMore"));
        }
        bundle.put("ledgerEntries", entries); bundle.put("ledgerHasMore", false); bundle.put("ledgerCursor", cursor);
        Map<String,Object> completeSections=new LinkedHashMap<>();
        if(initial.get("sections") instanceof Map<?,?> sections)for(var section:sections.entrySet()){
            String key=String.valueOf(section.getKey());
            if(!(section.getValue() instanceof Map<?,?> meta))throw new IOException("中心导出集合元数据非法");
            List<Map<String,Object>> values=new ArrayList<>(JSON.convertValue(initial.getOrDefault(key,List.of()),new TypeReference<List<Map<String,Object>>>(){}));
            long sectionEnd=number(meta.get("endId"),-1),sectionCursor=number(meta.get("cursor"),-1);
            boolean sectionMore=Boolean.TRUE.equals(meta.get("hasMore"));
            if(sectionEnd<0||sectionCursor<0||sectionCursor>sectionEnd)throw new IOException("导出集合截止范围非法");
            while(sectionMore){
                var page=gateway.readExportSection(sessionId,key,sectionCursor,sectionEnd,200);
                if(!page.successful())throw new IOException(page.message());
                long next=number(page.data().get("cursor"),-1);
                if(number(page.data().get("endId"),-1)!=sectionEnd||next<=sectionCursor||next>sectionEnd)throw new IOException("导出集合分页未推进或截止改变");
                List<Map<String,Object>> rows=JSON.convertValue(page.data().get("entries"),new TypeReference<List<Map<String,Object>>>(){});
                if(rows==null||rows.isEmpty())throw new IOException("导出集合缺少原记录");
                budget.add(rows);values.addAll(rows);sectionCursor=next;sectionMore=Boolean.TRUE.equals(page.data().get("hasMore"));
            }
            bundle.put(key,values);Map<String,Object> completed=new LinkedHashMap<>();meta.forEach((k,v)->completed.put(String.valueOf(k),v));
            completed.put("hasMore",false);completed.put("cursor",String.valueOf(sectionCursor));completeSections.put(key,completed);
        }
        if(!completeSections.isEmpty())bundle.put("sections",completeSections);
        List<LlmClient.Message> messages = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            Object message = entry.get("message");
            if (message == null && entry.get("_ledger") instanceof Map<?, ?> raw) message = raw.get("message");
            if (message != null) messages.add(JSON.convertValue(message, LlmClient.Message.class));
        }
        Path root = RunnerPrivateDirectory.createDirectories(directory).toRealPath();
        long retainedBytes=0;int retainedFiles=0;
        if(gateway.browserHosted())try(var files=Files.list(root)){
            for(Path file:files.toList()){RunnerPrivateDirectory.requireRegularFile(file);retainedBytes+=Files.size(file);retainedFiles++;}
            if(retainedFiles+2>32||retainedBytes>64L*1024*1024)throw new IOException("终端导出临时文件达到限额，请处理之前未发布的导出后重试");
        }
        String name = "session-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + "-" + UUID.randomUUID();
        Path json = root.resolve(name + ".json"), markdown = root.resolve(name + ".md");
        byte[] jsonBytes=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(bundle);
        String rendered = Main.renderConversationExport(messages, LocalDateTime.now());
        rendered += "\n\n完整中心证据包: " + json.getFileName() + "\n原始提示词包含: " + Boolean.TRUE.equals(initial.get("rawPromptIncluded")) + "\n";
        byte[] markdownBytes=rendered.getBytes(StandardCharsets.UTF_8);
        if(gateway.browserHosted()&&(jsonBytes.length>16*1024*1024||markdownBytes.length>16*1024*1024
                ||(long)jsonBytes.length+markdownBytes.length>24L*1024*1024
                ||retainedBytes+jsonBytes.length+markdownBytes.length>64L*1024*1024))
            throw new IOException("完整导出超出终端文件限额，未生成或发布部分内容");
        RunnerPrivateDirectory.writeAtomic(root, json.getFileName().toString(), jsonBytes);
        try {RunnerPrivateDirectory.writeAtomic(root, markdown.getFileName().toString(), markdownBytes);}
        catch(IOException failure){RunnerPrivateDirectory.requireRegularFile(json);Files.delete(json);throw failure;}
        return new Export(markdown, json, messages.size(), entries.size());
    }
    /** 累计原记录字节上限，分页仍完整读取；超过上限明确失败，不能截断后宣称完整。 */
    private static final class ExportBudget {
        private final boolean bounded;private long bytes;
        ExportBudget(boolean bounded){this.bounded=bounded;}
        void add(Object value)throws IOException{if(bounded){bytes+=JSON.writeValueAsBytes(value).length;if(bytes>24L*1024*1024)throw new IOException("完整导出记录超出终端批次限额，未生成部分文件");}}
    }
    private static long number(Object value, long fallback) { if(value==null)return fallback;try{return Long.parseLong(String.valueOf(value));}catch(NumberFormatException invalid){return fallback;} }
}
