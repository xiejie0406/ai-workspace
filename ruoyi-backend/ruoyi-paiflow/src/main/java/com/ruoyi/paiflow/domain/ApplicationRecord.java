package com.ruoyi.paiflow.domain;

/** 中心应用登记及当前固定版本；外部渠道和调用凭据独立管理。 */
public class ApplicationRecord {
    private String id;
    private Long spaceId;
    private Long ownerUserId;
    private String name;
    private String description;
    private String state;
    private Long agentId;
    private Long workflowId;
    private Long workflowVersionId;
    private String dslHash;
    private String engineProfile;
    private Long revision;
    private Integer deleted;
    private String createBy;
    private String updateBy;
    private java.util.Date createTime;
    private java.util.Date updateTime;
    public String getId(){return id;}
    public void setId(String value){id=value;}
    public Long getSpaceId(){return spaceId;}
    public void setSpaceId(Long value){spaceId=value;}
    public Long getOwnerUserId(){return ownerUserId;}
    public void setOwnerUserId(Long value){ownerUserId=value;}
    public String getName(){return name;}
    public void setName(String value){name=value;}
    public String getDescription(){return description;}
    public void setDescription(String value){description=value;}
    public String getState(){return state;}
    public void setState(String value){state=value;}
    public Long getAgentId(){return agentId;}
    public void setAgentId(Long value){agentId=value;}
    public Long getWorkflowId(){return workflowId;}
    public void setWorkflowId(Long value){workflowId=value;}
    public Long getWorkflowVersionId(){return workflowVersionId;}
    public void setWorkflowVersionId(Long value){workflowVersionId=value;}
    public String getDslHash(){return dslHash;}
    public void setDslHash(String value){dslHash=value;}
    public String getEngineProfile(){return engineProfile;}
    public void setEngineProfile(String value){engineProfile=value;}
    public Long getRevision(){return revision;}
    public void setRevision(Long value){revision=value;}
    public Integer getDeleted(){return deleted;}
    public void setDeleted(Integer value){deleted=value;}
    public String getCreateBy(){return createBy;}
    public void setCreateBy(String value){createBy=value;}
    public String getUpdateBy(){return updateBy;}
    public void setUpdateBy(String value){updateBy=value;}
    public java.util.Date getCreateTime(){return createTime;}
    public void setCreateTime(java.util.Date value){createTime=value;}
    public java.util.Date getUpdateTime(){return updateTime;}
    public void setUpdateTime(java.util.Date value){updateTime=value;}
}
