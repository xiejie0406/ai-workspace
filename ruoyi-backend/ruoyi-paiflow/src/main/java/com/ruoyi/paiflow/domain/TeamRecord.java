package com.ruoyi.paiflow.domain;

/** 中心成员关系数据；账号只引用平台 userId，未接通事实不造值。 */
public class TeamRecord {
    private Long id;
    private String name;
    private String avatarUrl;
    private String logoUrl;
    private Long ownerUserId;
    private Integer serviceType;
    private java.time.LocalDateTime expireTime;
    private Long revision;
    private Integer deleted;
    private String createBy;
    private String updateBy;
    private java.time.LocalDateTime createTime;
    private java.time.LocalDateTime updateTime;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String value) { avatarUrl = value; }
    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String value) { logoUrl = value; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long value) { ownerUserId = value; }
    public Integer getServiceType() { return serviceType; }
    public void setServiceType(Integer value) { serviceType = value; }
    public java.time.LocalDateTime getExpireTime() { return expireTime; }
    public void setExpireTime(java.time.LocalDateTime value) { expireTime = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer value) { deleted = value; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String value) { createBy = value; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String value) { updateBy = value; }
    public java.time.LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(java.time.LocalDateTime value) { createTime = value; }
    public java.time.LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(java.time.LocalDateTime value) { updateTime = value; }
}

