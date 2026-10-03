package com.ruoyi.paiflow.domain;

/** 中心成员关系数据；账号只引用平台 userId，未接通事实不造值。 */
public class TeamMember {
    private Long id;
    private Long teamId;
    private Long userId;
    private Integer role;
    private String status;
    private Long revision;
    private java.time.LocalDateTime createTime;
    private java.time.LocalDateTime updateTime;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getTeamId() { return teamId; }
    public void setTeamId(Long value) { teamId = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public Integer getRole() { return role; }
    public void setRole(Integer value) { role = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }
    public java.time.LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(java.time.LocalDateTime value) { createTime = value; }
    public java.time.LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(java.time.LocalDateTime value) { updateTime = value; }
}

