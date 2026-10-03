package com.ruoyi.paiflow.domain;

/** 原画布与未知字段独立保存；正式所有权和版本来自中心。 */
public class WorkflowRecord {
    private Long id;
    private String flowId;
    private Long spaceId;
    private Long ownerUserId;
    private Long revision;
    private String name;
    private String description;
    private String canvasJson;
    private String metadataJson;
    private String advancedConfigJson;
    private String flowConfigJson;
    private String engineProfile;
    private Long latestVersionId;
    private Integer deleted;
    private String createBy;
    private String updateBy;
    private java.util.Date createTime;
    private java.util.Date updateTime;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getFlowId() { return flowId; }
    public void setFlowId(String value) { flowId = value; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long value) { spaceId = value; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long value) { ownerUserId = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getCanvasJson() { return canvasJson; }
    public void setCanvasJson(String value) { canvasJson = value; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String value) { metadataJson = value; }
    public String getAdvancedConfigJson() { return advancedConfigJson; }
    public void setAdvancedConfigJson(String value) { advancedConfigJson = value; }
    public String getFlowConfigJson() { return flowConfigJson; }
    public void setFlowConfigJson(String value) { flowConfigJson = value; }
    public String getEngineProfile() { return engineProfile; }
    public void setEngineProfile(String value) { engineProfile = value; }
    public Long getLatestVersionId() { return latestVersionId; }
    public void setLatestVersionId(Long value) { latestVersionId = value; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer value) { deleted = value; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String value) { createBy = value; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String value) { updateBy = value; }
    public java.util.Date getCreateTime() { return createTime; }
    public void setCreateTime(java.util.Date value) { createTime = value; }
    public java.util.Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(java.util.Date value) { updateTime = value; }
}
