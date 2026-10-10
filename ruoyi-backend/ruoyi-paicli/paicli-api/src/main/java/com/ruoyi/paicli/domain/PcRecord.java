package com.ruoyi.paicli.domain;

import java.time.Instant;

/** 中心资源账本。可索引归属、状态、版本、关联、序号和哈希为列；类型化内容在受限 JSON 中。 */
public class PcRecord {
    private Long id, workspaceId, directoryContextId, ownerId, parentId, sessionId, runnerId;
    private String kind, state, uniqueKey, contentHash, payload;
    private long version = 1, sequenceNumber, fencingToken;
    private Instant createdAt, updatedAt, expiresAt;
    public Long getId() { return id; } public void setId(Long v) { id=v; }
    public Long getWorkspaceId() { return workspaceId; } public void setWorkspaceId(Long v) { workspaceId=v; }
    public Long getDirectoryContextId() { return directoryContextId; } public void setDirectoryContextId(Long v) { directoryContextId=v; }
    public Long getOwnerId() { return ownerId; } public void setOwnerId(Long v) { ownerId=v; }
    public Long getParentId() { return parentId; } public void setParentId(Long v) { parentId=v; }
    public Long getSessionId() { return sessionId; } public void setSessionId(Long v) { sessionId=v; }
    public Long getRunnerId() { return runnerId; } public void setRunnerId(Long v) { runnerId=v; }
    public String getKind() { return kind; } public void setKind(String v) { kind=v; }
    public String getState() { return state; } public void setState(String v) { state=v; }
    public String getUniqueKey() { return uniqueKey; } public void setUniqueKey(String v) { uniqueKey=v; }
    public String getContentHash() { return contentHash; } public void setContentHash(String v) { contentHash=v; }
    public String getPayload() { return payload; } public void setPayload(String v) { payload=v; }
    public long getVersion() { return version; } public void setVersion(long v) { version=v; }
    public long getSequenceNumber() { return sequenceNumber; } public void setSequenceNumber(long v) { sequenceNumber=v; }
    public long getFencingToken() { return fencingToken; } public void setFencingToken(long v) { fencingToken=v; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt=v; }
    public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt=v; }
    public Instant getExpiresAt() { return expiresAt; } public void setExpiresAt(Instant v) { expiresAt=v; }
}
