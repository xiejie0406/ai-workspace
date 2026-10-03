package com.ruoyi.paiflow.domain;

/** 中心成员关系数据；账号只引用平台 userId，未接通事实不造值。 */
public class MembershipApplication {
    private Long id;
    private Long teamId;
    private Long spaceId;
    private Long applicantUserId;
    private Integer status;
    private Long auditUserId;
    private Long revision;
    private java.time.LocalDateTime auditTime;
    private java.time.LocalDateTime createTime;
    private java.time.LocalDateTime updateTime;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getTeamId() { return teamId; }
    public void setTeamId(Long value) { teamId = value; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long value) { spaceId = value; }
    public Long getApplicantUserId() { return applicantUserId; }
    public void setApplicantUserId(Long value) { applicantUserId = value; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer value) { status = value; }
    public Long getAuditUserId() { return auditUserId; }
    public void setAuditUserId(Long value) { auditUserId = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }
    public java.time.LocalDateTime getAuditTime() { return auditTime; }
    public void setAuditTime(java.time.LocalDateTime value) { auditTime = value; }
    public java.time.LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(java.time.LocalDateTime value) { createTime = value; }
    public java.time.LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(java.time.LocalDateTime value) { updateTime = value; }
}

