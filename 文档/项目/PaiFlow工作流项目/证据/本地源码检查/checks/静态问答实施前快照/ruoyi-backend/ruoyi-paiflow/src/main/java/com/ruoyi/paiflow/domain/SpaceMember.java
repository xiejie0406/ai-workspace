package com.ruoyi.paiflow.domain;

/** PaiFlow业务数据；平台账号只以稳定userId关联。 */
public class SpaceMember {
    private Long id;
    private Long revision;
    private java.time.LocalDateTime createTime;
    private java.time.LocalDateTime updateTime;
    private java.time.LocalDateTime lastVisitTime;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }
    public java.time.LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(java.time.LocalDateTime value) { createTime = value; }
    public java.time.LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(java.time.LocalDateTime value) { updateTime = value; }
    public java.time.LocalDateTime getLastVisitTime() { return lastVisitTime; }
    public void setLastVisitTime(java.time.LocalDateTime value) { lastVisitTime = value; }
    private Long spaceId;
    private Long userId;
    private Integer role;
    private String status;
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long value) { spaceId = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public Integer getRole() { return role; }
    public void setRole(Integer value) { role = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
}
