package com.ruoyi.paiflow.domain;

import java.time.LocalDateTime;

/** 数据资源元数据；库和表是业务逻辑资源，不创建第二业务数据库。 */
public class DataRecord {
    private Long id,spaceId,ownerUserId,dbId,revision;
    private String name,description,avatarIcon,avatarColor,fieldsJson;
    private Integer deleted;
    private LocalDateTime createTime,updateTime;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getSpaceId(){return spaceId;} public void setSpaceId(Long v){spaceId=v;}
    public Long getOwnerUserId(){return ownerUserId;} public void setOwnerUserId(Long v){ownerUserId=v;}
    public Long getDbId(){return dbId;} public void setDbId(Long v){dbId=v;}
    public Long getRevision(){return revision;} public void setRevision(Long v){revision=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public String getAvatarIcon(){return avatarIcon;} public void setAvatarIcon(String v){avatarIcon=v;}
    public String getAvatarColor(){return avatarColor;} public void setAvatarColor(String v){avatarColor=v;}
    public String getFieldsJson(){return fieldsJson;} public void setFieldsJson(String v){fieldsJson=v;}
    public Integer getDeleted(){return deleted;} public void setDeleted(Integer v){deleted=v;}
    public LocalDateTime getCreateTime(){return createTime;} public void setCreateTime(LocalDateTime v){createTime=v;}
    public LocalDateTime getUpdateTime(){return updateTime;} public void setUpdateTime(LocalDateTime v){updateTime=v;}
}
