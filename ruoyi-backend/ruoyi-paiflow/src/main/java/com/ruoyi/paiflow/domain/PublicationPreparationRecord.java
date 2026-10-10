package com.ruoyi.paiflow.domain;

/** 固定发布准备及真实调试证据；准备完成不表示渠道已经生效。 */
public class PublicationPreparationRecord {
    private String id;
    private String applicationId;
    private Long applicationRevision;
    private Long spaceId;
    private Long ownerUserId;
    private Long agentId;
    private Long workflowId;
    private Long workflowVersionId;
    private String dslHash;
    private String engineProfile;
    private String channel;
    private String state;
    private String debugRunId;
    private Long debugWorkflowVersionId;
    private String proofHash;
    private Long fixedSnapshotRevision;
    private Long debugSnapshotRevision;
    private String requestKey;
    private String requestHash;
    private Long revision;
    private String createBy;
    private java.util.Date createTime;
    public String getId(){return id;}
    public void setId(String value){id=value;}
    public String getApplicationId(){return applicationId;}
    public void setApplicationId(String value){applicationId=value;}
    public Long getApplicationRevision(){return applicationRevision;}
    public void setApplicationRevision(Long value){applicationRevision=value;}
    public Long getSpaceId(){return spaceId;}
    public void setSpaceId(Long value){spaceId=value;}
    public Long getOwnerUserId(){return ownerUserId;}
    public void setOwnerUserId(Long value){ownerUserId=value;}
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
    public String getChannel(){return channel;}
    public void setChannel(String value){channel=value;}
    public String getState(){return state;}
    public void setState(String value){state=value;}
    public String getDebugRunId(){return debugRunId;}
    public void setDebugRunId(String value){debugRunId=value;}
    public Long getDebugWorkflowVersionId(){return debugWorkflowVersionId;}
    public void setDebugWorkflowVersionId(Long value){debugWorkflowVersionId=value;}
    public String getProofHash(){return proofHash;}
    public void setProofHash(String value){proofHash=value;}
    public Long getFixedSnapshotRevision(){return fixedSnapshotRevision;}
    public void setFixedSnapshotRevision(Long value){fixedSnapshotRevision=value;}
    public Long getDebugSnapshotRevision(){return debugSnapshotRevision;}
    public void setDebugSnapshotRevision(Long value){debugSnapshotRevision=value;}
    public String getRequestKey(){return requestKey;}
    public void setRequestKey(String value){requestKey=value;}
    public String getRequestHash(){return requestHash;}
    public void setRequestHash(String value){requestHash=value;}
    public Long getRevision(){return revision;}
    public void setRevision(Long value){revision=value;}
    public String getCreateBy(){return createBy;}
    public void setCreateBy(String value){createBy=value;}
    public java.util.Date getCreateTime(){return createTime;}
    public void setCreateTime(java.util.Date value){createTime=value;}
}
