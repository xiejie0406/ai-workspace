package com.ruoyi.paiflow.domain;

/** 中心成员关系数据；账号只引用平台 userId，未接通事实不造值。 */
public class MembershipInvitation {
    private Long id;
    private Integer type;
    private Long spaceId;
    private Long teamId;
    private Long ownerUserId;
    private Long inviteeUserId;
    private Integer role;
    private Long inviterUserId;
    private Integer status;
    private Long revision;
    private String notificationStatus;
    private java.time.LocalDateTime expireTime;
    private java.time.LocalDateTime createTime;
    private java.time.LocalDateTime updateTime;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Integer getType() { return type; }
    public void setType(Integer value) { type = value; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long value) { spaceId = value; }
    public Long getTeamId() { return teamId; }
    public void setTeamId(Long value) { teamId = value; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long value) { ownerUserId = value; }
    public Long getInviteeUserId() { return inviteeUserId; }
    public void setInviteeUserId(Long value) { inviteeUserId = value; }
    public Integer getRole() { return role; }
    public void setRole(Integer value) { role = value; }
    public Long getInviterUserId() { return inviterUserId; }
    public void setInviterUserId(Long value) { inviterUserId = value; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer value) { status = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }
    public String getNotificationStatus() { return notificationStatus; }
    public void setNotificationStatus(String value) { notificationStatus = value; }
    public java.time.LocalDateTime getExpireTime() { return expireTime; }
    public void setExpireTime(java.time.LocalDateTime value) { expireTime = value; }
    public java.time.LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(java.time.LocalDateTime value) { createTime = value; }
    public java.time.LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(java.time.LocalDateTime value) { updateTime = value; }
}

