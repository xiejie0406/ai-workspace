package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.UUID;

/** 固定租约截止的派发分页全部恢复后才调度算法；游标不前进立即停止。 */
public final class NativeContextAssembler {
    private NativeContextAssembler() { }
    public static JsonNode load(CenterHttpClient center, RunnerLease lease, JsonNode initial) throws IOException {
        if (!initial.isObject()) throw new IOException("中心上下文格式无效");
        if(PersonalDirectoryContext.textOnly(lease.claim())){
            if(!initial.path("textOnly").asBoolean())throw new IOException("中心上下文改变了纯问答无工具边界");
            for(String field:new String[]{"ownerUserId","runtimeCanonicalPath","runtimeFingerprint"})if(lease.claim().hasNonNull(field)
                &&!lease.claim().path(field).asText().equals(initial.path(field).asText()))throw new IOException("中心上下文改变了纯问答本人身份或运行目录");
        }
        if(lease.claim().hasNonNull("directoryContext")){
            JsonNode expected=lease.claim().path("directoryContext"),actual=initial.path("directoryContext");
            for(String field:new String[]{"id","ownerUserId","runnerId","path","rootFingerprint","version"})
                if(!expected.path(field).asText().equals(actual.path(field).asText()))throw new IOException("中心上下文改变了租约固定个人目录："+field);
        }
        ObjectNode context = initial.deepCopy(); long end = context.path("endSequence").asLong();
        ArrayNode history = CanonicalJson.MAPPER.createArrayNode();
        JsonNode first = context.path("history"); if (!first.isArray()) first = context.path("messages");
        if (first.isArray()) history.addAll((ArrayNode) first);
        long cursor = context.path("historyCursor").asLong(), summary = context.path("historySummaryOffset").asLong();
        boolean more = context.path("historyHasMore").asBoolean(); int pages = 0;
        while (more) {
            lease.ensureActive(); if (++pages > 10000) throw new IOException("中心历史分页超过本机装配容量，请先压缩上下文");
            ObjectNode request = lease.identity(); request.put("afterSequence", cursor); request.put("summaryOffset", summary); request.put("endSequence", end); request.put("limit", 200);
            JsonNode page = center.post("/paicli/runner/v1/history/read", request, UUID.randomUUID().toString());
            if (page.path("endSequence").asLong(-1) != end || !page.path("messages").isArray()) throw new IOException("中心历史分页截止或格式改变");
            long next = page.path("cursor").asLong(-1), nextSummary = page.path("summaryOffset").asLong(-1);
            more = page.path("hasMore").asBoolean();
            if (next < cursor || next > end || nextSummary < summary || (more && next == cursor && nextSummary == summary)) throw new IOException("中心历史分页游标未前进");
            history.addAll((ArrayNode) page.path("messages")); cursor = next; summary = nextSummary;
        }
        context.set("history", history); context.set("messages", history); context.put("historyCursor", cursor); context.put("historySummaryOffset", summary); context.put("historyHasMore", false);
        ArrayNode entries = CanonicalJson.MAPPER.createArrayNode(); if (context.path("ledgerEntries").isArray()) entries.addAll((ArrayNode) context.path("ledgerEntries"));
        cursor = context.path("ledgerCursor").asLong(); more = context.path("ledgerHasMore").asBoolean(); pages = 0;
        while (more) {
            lease.ensureActive(); if (++pages > 10000) throw new IOException("中心原账本分页超过本机装配容量，请缩小会话范围");
            ObjectNode request = lease.identity(); request.put("afterSequence", cursor); request.put("endSequence", end); request.put("limit", 200);
            JsonNode page = center.post("/paicli/runner/v1/ledger/read", request, UUID.randomUUID().toString());
            if (page.path("endSequence").asLong(-1) != end || !page.path("entries").isArray()) throw new IOException("中心原账本分页截止或格式改变");
            long next = page.path("cursor").asLong(-1); more = page.path("hasMore").asBoolean();
            if (next < cursor || next > end || (more && next == cursor)) throw new IOException("中心原账本分页游标未前进");
            entries.addAll((ArrayNode) page.path("entries")); cursor = next;
        }
        context.set("ledgerEntries", entries); context.put("ledgerCursor", cursor); context.put("ledgerHasMore", false); return context;
    }
}
