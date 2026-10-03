package com.ruoyi.paiflow.domain;

/** PaiFlow业务数据；平台账号只以稳定userId关联。 */
public class SpaceRecord {
    public static final String DEFAULT_PERSONAL = "default_personal";
    /** 原NULL空间的内部表示，不能作为可协作的具名个人空间。 */
    public boolean isDefaultPersonal() { return DEFAULT_PERSONAL.equals(kind); }
    private Long id;
    private String name;
    private String description;
    private String avatarUrl;
    private Long ownerUserId;
    private Long deptId;
    private Long teamId;
    private String kind;
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
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String value) { avatarUrl = value; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long value) { ownerUserId = value; }
    public Long getDeptId() { return deptId; }
    public void setDeptId(Long value) { deptId = value; }
    public Long getTeamId() { return teamId; }
    public void setTeamId(Long value) { teamId = value; }
    public String getKind() { return kind; }
    public void setKind(String value) { kind = value; }
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
