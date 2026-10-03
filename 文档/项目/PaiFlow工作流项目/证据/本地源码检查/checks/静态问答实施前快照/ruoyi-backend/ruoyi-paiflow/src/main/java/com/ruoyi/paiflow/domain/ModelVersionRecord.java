package com.ruoyi.paiflow.domain;

/** 只插入不更新的模型快照；停用/逻辑删除不破坏已有版本。 */
public class ModelVersionRecord {
    private Long id,modelId,spaceId,ownerUserId,revision;
    private Integer enabled,type;
    private String state,configurationJson,candidateHash,credentialVersion,receiptJson,createBy;
    private java.util.Date createTime;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getModelId(){return modelId;} public void setModelId(Long v){modelId=v;}
    public Long getSpaceId(){return spaceId;} public void setSpaceId(Long v){spaceId=v;}
    public Long getOwnerUserId(){return ownerUserId;} public void setOwnerUserId(Long v){ownerUserId=v;}
    public Long getRevision(){return revision;} public void setRevision(Long v){revision=v;}
    public Integer getEnabled(){return enabled;} public void setEnabled(Integer v){enabled=v;}
    public Integer getType(){return type;} public void setType(Integer v){type=v;}
    public String getState(){return state;} public void setState(String v){state=v;}
    public String getConfigurationJson(){return configurationJson;} public void setConfigurationJson(String v){configurationJson=v;}
    public String getCandidateHash(){return candidateHash;} public void setCandidateHash(String v){candidateHash=v;}
    public String getCredentialVersion(){return credentialVersion;} public void setCredentialVersion(String v){credentialVersion=v;}
    public String getReceiptJson(){return receiptJson;} public void setReceiptJson(String v){receiptJson=v;}
    public String getCreateBy(){return createBy;} public void setCreateBy(String v){createBy=v;}
    public java.util.Date getCreateTime(){return createTime;} public void setCreateTime(java.util.Date v){createTime=v;}
}
