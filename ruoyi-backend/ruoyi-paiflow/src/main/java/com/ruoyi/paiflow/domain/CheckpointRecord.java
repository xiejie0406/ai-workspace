package com.ruoyi.paiflow.domain;

import java.time.LocalDateTime;

/** 中心保存原问答题面、真实用户回复与一次性消费；不建立Worker本地恢复真相。 */
public class CheckpointRecord {
    private String id,runId,leaseId,nodeId,iterationId,state,requestKey,requestHash,questionJson,resumeDataJson,replyKey,replyHash,consumeKey,consumeHash;
    private Integer attempt,ordinal,needReply;
    private Long revision;
    private LocalDateTime deadline,createTime,updateTime;
    public String getId(){return id;} public void setId(String v){id=v;}
    public String getRunId(){return runId;} public void setRunId(String v){runId=v;}
    public String getLeaseId(){return leaseId;} public void setLeaseId(String v){leaseId=v;}
    public String getNodeId(){return nodeId;} public void setNodeId(String v){nodeId=v;}
    public String getIterationId(){return iterationId;} public void setIterationId(String v){iterationId=v;}
    public String getState(){return state;} public void setState(String v){state=v;}
    public String getRequestKey(){return requestKey;} public void setRequestKey(String v){requestKey=v;}
    public String getRequestHash(){return requestHash;} public void setRequestHash(String v){requestHash=v;}
    public String getQuestionJson(){return questionJson;} public void setQuestionJson(String v){questionJson=v;}
    public String getResumeDataJson(){return resumeDataJson;} public void setResumeDataJson(String v){resumeDataJson=v;}
    public String getReplyKey(){return replyKey;} public void setReplyKey(String v){replyKey=v;}
    public String getReplyHash(){return replyHash;} public void setReplyHash(String v){replyHash=v;}
    public String getConsumeKey(){return consumeKey;} public void setConsumeKey(String v){consumeKey=v;}
    public String getConsumeHash(){return consumeHash;} public void setConsumeHash(String v){consumeHash=v;}
    public Integer getAttempt(){return attempt;} public void setAttempt(Integer v){attempt=v;}
    public Integer getOrdinal(){return ordinal;} public void setOrdinal(Integer v){ordinal=v;}
    public Integer getNeedReply(){return needReply;} public void setNeedReply(Integer v){needReply=v;}
    public Long getRevision(){return revision;} public void setRevision(Long v){revision=v;}
    public LocalDateTime getDeadline(){return deadline;} public void setDeadline(LocalDateTime v){deadline=v;}
    public LocalDateTime getCreateTime(){return createTime;} public void setCreateTime(LocalDateTime v){createTime=v;}
    public LocalDateTime getUpdateTime(){return updateTime;} public void setUpdateTime(LocalDateTime v){updateTime=v;}
}
