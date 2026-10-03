package com.ruoyi.paiflow.domain;

/** 中心单次模型调用记录；凭据明文不持久化，不作为SDK可自行重放的许可。 */
public class ModelOperationRecord {
    private String id;
    private String runId;
    private String leaseId;
    private String workerId;
    private String nodeId;
    private String iterationId;
    private String dslHash;
    private String resourceId;
    private String resourceVersion;
    private String provider;
    private String requestKey;
    private String requestKeyHash;
    private String requestHash;
    private String parametersJson;
    private String approvalJson;
    private String approvalRef;
    private String state;
    private String grantId;
    private String grantTokenHash;
    private String failureKind;
    private String finishReason;
    private String stopProofId;
    private String usageJson;
    private Long workflowVersionId;
    private Long spaceId;
    private Long ownerUserId;
    private Long lastSeq;
    private Long finishSeq;
    private Long responseBytes;
    private Long revision;
    private Integer attempt;
    private Integer dispatchStarted;
    private Integer grantAcquired;
    private Integer stopConfirmed;
    private java.time.LocalDateTime approvalExpiresAt;
    private java.time.LocalDateTime deadline;
    private java.time.LocalDateTime createTime;
    private java.time.LocalDateTime updateTime;
    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getRunId() { return runId; }
    public void setRunId(String value) { runId = value; }
    public String getLeaseId() { return leaseId; }
    public void setLeaseId(String value) { leaseId = value; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String value) { workerId = value; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String value) { nodeId = value; }
    public String getIterationId() { return iterationId; }
    public void setIterationId(String value) { iterationId = value; }
    public String getDslHash() { return dslHash; }
    public void setDslHash(String value) { dslHash = value; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String value) { resourceId = value; }
    public String getResourceVersion() { return resourceVersion; }
    public void setResourceVersion(String value) { resourceVersion = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String value) { requestKey = value; }
    public String getRequestKeyHash() { return requestKeyHash; }
    public void setRequestKeyHash(String value) { requestKeyHash = value; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String value) { requestHash = value; }
    public String getParametersJson() { return parametersJson; }
    public void setParametersJson(String value) { parametersJson = value; }
    public String getApprovalJson() { return approvalJson; }
    public void setApprovalJson(String value) { approvalJson = value; }
    public String getApprovalRef() { return approvalRef; }
    public void setApprovalRef(String value) { approvalRef = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public String getGrantId() { return grantId; }
    public void setGrantId(String value) { grantId = value; }
    public String getGrantTokenHash() { return grantTokenHash; }
    public void setGrantTokenHash(String value) { grantTokenHash = value; }
    public String getFailureKind() { return failureKind; }
    public void setFailureKind(String value) { failureKind = value; }
    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String value) { finishReason = value; }
    public String getStopProofId() { return stopProofId; }
    public void setStopProofId(String value) { stopProofId = value; }
    public String getUsageJson() { return usageJson; }
    public void setUsageJson(String value) { usageJson = value; }
    public Long getWorkflowVersionId() { return workflowVersionId; }
    public void setWorkflowVersionId(Long value) { workflowVersionId = value; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long value) { spaceId = value; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long value) { ownerUserId = value; }
    public Long getLastSeq() { return lastSeq; }
    public void setLastSeq(Long value) { lastSeq = value; }
    public Long getFinishSeq() { return finishSeq; }
    public void setFinishSeq(Long value) { finishSeq = value; }
    public Long getResponseBytes() { return responseBytes; }
    public void setResponseBytes(Long value) { responseBytes = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer value) { attempt = value; }
    public Integer getDispatchStarted() { return dispatchStarted; }
    public void setDispatchStarted(Integer value) { dispatchStarted = value; }
    public Integer getGrantAcquired() { return grantAcquired; }
    public void setGrantAcquired(Integer value) { grantAcquired = value; }
    public Integer getStopConfirmed() { return stopConfirmed; }
    public void setStopConfirmed(Integer value) { stopConfirmed = value; }
    public java.time.LocalDateTime getApprovalExpiresAt() { return approvalExpiresAt; }
    public void setApprovalExpiresAt(java.time.LocalDateTime value) { approvalExpiresAt = value; }
    public java.time.LocalDateTime getDeadline() { return deadline; }
    public void setDeadline(java.time.LocalDateTime value) { deadline = value; }
    public java.time.LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(java.time.LocalDateTime value) { createTime = value; }
    public java.time.LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(java.time.LocalDateTime value) { updateTime = value; }
}
