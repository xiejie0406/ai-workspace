package com.ruoyi.paiflow.domain;

/** 原画布与未知字段独立保存；正式所有权和版本来自中心。 */
public class WorkflowVersionRecord {
    private Long id;
    private Long workflowId;
    private Long spaceId;
    private Long ownerUserId;
    private Long snapshotRevision;
    private String flowId;
    private String versionNum;
    private String name;
    private String description;
    private String canvasJson;
    private String executionJson;
    private String resourceSnapshotsJson;
    private String executionCodec;
    private String dslHash;
    private String canonicalDslBase64;
    private String metadataJson;
    private String advancedConfigJson;
    private String flowConfigJson;
    private String engineProfile;
    private String createBy;
    private java.util.Date createTime;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long value) { workflowId = value; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long value) { spaceId = value; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long value) { ownerUserId = value; }
    public Long getSnapshotRevision() { return snapshotRevision; }
    public void setSnapshotRevision(Long value) { snapshotRevision = value; }
    public String getFlowId() { return flowId; }
    public void setFlowId(String value) { flowId = value; }
    public String getVersionNum() { return versionNum; }
    public void setVersionNum(String value) { versionNum = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getCanvasJson() { return canvasJson; }
    public void setCanvasJson(String value) { canvasJson = value; }
    public String getExecutionJson() { return executionJson; }
    public void setExecutionJson(String value) { executionJson = value; }
    public String getResourceSnapshotsJson() { return resourceSnapshotsJson; }
    public void setResourceSnapshotsJson(String value) { resourceSnapshotsJson = value; }
    public String getExecutionCodec() { return executionCodec; }
    public void setExecutionCodec(String value) { executionCodec = value; }
    public String getDslHash() { return dslHash; }
    public void setDslHash(String value) { dslHash = value; }
    public String getCanonicalDslBase64() { return canonicalDslBase64; }
    public void setCanonicalDslBase64(String value) { canonicalDslBase64 = value; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String value) { metadataJson = value; }
    public String getAdvancedConfigJson() { return advancedConfigJson; }
    public void setAdvancedConfigJson(String value) { advancedConfigJson = value; }
    public String getFlowConfigJson() { return flowConfigJson; }
    public void setFlowConfigJson(String value) { flowConfigJson = value; }
    public String getEngineProfile() { return engineProfile; }
    public void setEngineProfile(String value) { engineProfile = value; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String value) { createBy = value; }
    public java.util.Date getCreateTime() { return createTime; }
    public void setCreateTime(java.util.Date value) { createTime = value; }
}
