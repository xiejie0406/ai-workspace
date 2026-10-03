package com.ruoyi.paiflow.domain;

/** 中心正式运行与租约记录；Worker不持有此状态。 */
public class RunRecord {
    private String modelPermitJson;
    public String getModelPermitJson(){return modelPermitJson;}
    public void setModelPermitJson(String value){modelPermitJson=value;}
    private String id;
    private Long workflowId;
    private Long workflowVersionId;
    private Long spaceId;
    private Long ownerUserId;
    private String engineProfile;
    private String state;
    private String assignmentJson;
    private String inputHash;
    private String idempotencyKey;
    private String workerId;
    private String leaseId;
    private Integer attempt;
    private java.time.LocalDateTime leaseExpiresAt;
    private java.time.LocalDateTime executionStartedAt;
    private java.time.LocalDateTime deadline;
    private Long lastEventSeq;
    private Long revision;
    private String resultJson;
    private String resultHash;
    private String createBy;
    private java.time.LocalDateTime createTime;
    private java.time.LocalDateTime updateTime;
    public String getId() { return id; }
    public void setId(String value) { this.id = value; }
    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long value) { this.workflowId = value; }
    public Long getWorkflowVersionId() { return workflowVersionId; }
    public void setWorkflowVersionId(Long value) { this.workflowVersionId = value; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long value) { this.spaceId = value; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long value) { this.ownerUserId = value; }
    public String getEngineProfile() { return engineProfile; }
    public void setEngineProfile(String value) { this.engineProfile = value; }
    public String getState() { return state; }
    public void setState(String value) { this.state = value; }
    public String getAssignmentJson() { return assignmentJson; }
    public void setAssignmentJson(String value) { this.assignmentJson = value; }
    public String getInputHash() { return inputHash; }
    public void setInputHash(String value) { this.inputHash = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { this.idempotencyKey = value; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String value) { this.workerId = value; }
    public String getLeaseId() { return leaseId; }
    public void setLeaseId(String value) { this.leaseId = value; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer value) { this.attempt = value; }
    public java.time.LocalDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(java.time.LocalDateTime value) { this.leaseExpiresAt = value; }
    public java.time.LocalDateTime getExecutionStartedAt() { return executionStartedAt; }
    public void setExecutionStartedAt(java.time.LocalDateTime value) { this.executionStartedAt = value; }
    public java.time.LocalDateTime getDeadline() { return deadline; }
    public void setDeadline(java.time.LocalDateTime value) { this.deadline = value; }
    public Long getLastEventSeq() { return lastEventSeq; }
    public void setLastEventSeq(Long value) { this.lastEventSeq = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { this.revision = value; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String value) { this.resultJson = value; }
    public String getResultHash() { return resultHash; }
    public void setResultHash(String value) { this.resultHash = value; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String value) { this.createBy = value; }
    public java.time.LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(java.time.LocalDateTime value) { this.createTime = value; }
    public java.time.LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(java.time.LocalDateTime value) { this.updateTime = value; }
}
