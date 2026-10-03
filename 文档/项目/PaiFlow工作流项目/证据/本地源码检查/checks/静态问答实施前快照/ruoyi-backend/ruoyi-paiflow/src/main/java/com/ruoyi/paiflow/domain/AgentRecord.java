package com.ruoyi.paiflow.domain;

/** 智能体元数据及其唯一工作流引用；画布与版本仍由WorkflowService持有。 */
public class AgentRecord {
    private Long id;
    private Long spaceId;
    private Long ownerUserId;
    private Long workflowId;
    private Long revision;
    private Integer version;
    private Integer botType;
    private String name;
    private String description;
    private String avatar;
    private String configJson;
    private Integer deleted;
    private String createBy;
    private String updateBy;
    private java.util.Date createTime;
    private java.util.Date updateTime;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long value) { spaceId = value; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long value) { ownerUserId = value; }
    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long value) { workflowId = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { version = value; }
    public Integer getBotType() { return botType; }
    public void setBotType(Integer value) { botType = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String value) { avatar = value; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String value) { configJson = value; }
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

