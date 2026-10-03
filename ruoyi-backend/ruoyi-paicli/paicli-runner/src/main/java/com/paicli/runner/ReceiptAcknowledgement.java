package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/** 只有中心确认同一执行批次已持久化，才能删除本机证据。 */
final class ReceiptAcknowledgement {
    private ReceiptAcknowledgement() {}

    static void require(JsonNode acknowledgement, JsonNode receipt) throws IOException {
        if (acknowledgement == null || !acknowledgement.path("accepted").isBoolean()
                || !acknowledgement.path("accepted").booleanValue()
                || !acknowledgement.path("acknowledged").isBoolean()
                || !acknowledgement.path("acknowledged").booleanValue()
                || !acknowledgement.path("archived").isBoolean()) {
            throw new IOException("中心未明确确认回执持久化，保留本机证据");
        }
        for (String field : new String[]{"taskId", "toolCallId", "leaseId", "fencingToken"}) {
            if (receipt.path(field).asText().isBlank()
                    || !receipt.path(field).asText().equals(acknowledgement.path(field).asText())) {
                throw new IOException("中心回执确认执行批次不符：" + field);
            }
        }
        String expected = acknowledgement.path("archived").booleanValue()
                ? "LATE_ARCHIVED" : receipt.path("status").asText();
        if (!expected.equals(acknowledgement.path("status").asText())) {
            throw new IOException("中心回执确认状态不符，保留本机证据");
        }
    }
}
