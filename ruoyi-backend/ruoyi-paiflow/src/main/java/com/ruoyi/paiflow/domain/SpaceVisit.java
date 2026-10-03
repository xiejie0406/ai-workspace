package com.ruoyi.paiflow.domain;

/** PaiFlow业务数据；平台账号只以稳定userId关联。 */
public class SpaceVisit {
    private Long spaceId;
    private Long userId;
    private java.time.LocalDateTime lastVisitTime;
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long value) { spaceId = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public java.time.LocalDateTime getLastVisitTime() { return lastVisitTime; }
    public void setLastVisitTime(java.time.LocalDateTime value) { lastVisitTime = value; }
}
