package com.ruoyi.paiflow.domain;

/** 中心模型草稿；凭据仅为不透明引用，历史配置另存不可变版本。 */
public class ModelRecord {
    private Long id,spaceId,ownerUserId,revision,latestVersionId;
    private Integer type,enabled,deleted;
    private String name,state,configurationJson,candidateHash,credentialVersion,receiptJson,createBy,updateBy;
    private java.util.Date createTime,updateTime;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getSpaceId(){return spaceId;} public void setSpaceId(Long v){spaceId=v;}
    public Long getOwnerUserId(){return ownerUserId;} public void setOwnerUserId(Long v){ownerUserId=v;}
    public Long getRevision(){return revision;} public void setRevision(Long v){revision=v;}
    public Long getLatestVersionId(){return latestVersionId;} public void setLatestVersionId(Long v){latestVersionId=v;}
    public Integer getType(){return type;} public void setType(Integer v){type=v;}
    public Integer getEnabled(){return enabled;} public void setEnabled(Integer v){enabled=v;}
    public Integer getDeleted(){return deleted;} public void setDeleted(Integer v){deleted=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getState(){return state;} public void setState(String v){state=v;}
    public String getConfigurationJson(){return configurationJson;} public void setConfigurationJson(String v){configurationJson=v;}
    public String getCandidateHash(){return candidateHash;} public void setCandidateHash(String v){candidateHash=v;}
    public String getCredentialVersion(){return credentialVersion;} public void setCredentialVersion(String v){credentialVersion=v;}
    public String getReceiptJson(){return receiptJson;} public void setReceiptJson(String v){receiptJson=v;}
    public String getCreateBy(){return createBy;} public void setCreateBy(String v){createBy=v;}
    public String getUpdateBy(){return updateBy;} public void setUpdateBy(String v){updateBy=v;}
    public java.util.Date getCreateTime(){return createTime;} public void setCreateTime(java.util.Date v){createTime=v;}
    public java.util.Date getUpdateTime(){return updateTime;} public void setUpdateTime(java.util.Date v){updateTime=v;}
}
