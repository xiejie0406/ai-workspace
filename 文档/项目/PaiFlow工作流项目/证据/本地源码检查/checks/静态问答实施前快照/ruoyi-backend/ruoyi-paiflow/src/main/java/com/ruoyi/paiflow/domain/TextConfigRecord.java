package com.ruoyi.paiflow.domain;

/** 原文本分隔符为用户偏好，画布保存字面值；0仅表示只读系统种子。 */
public class TextConfigRecord {
    private Long id;
    private Long ownerUserId;
    private String separator;
    private String comment;
    private Long revision;
    private String createBy;
    private String updateBy;
    private java.util.Date createTime;
    private java.util.Date updateTime;
    public Long getId() { return id; }
    public void setId(Long value) { id=value; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long value) { ownerUserId=value; }
    public String getSeparator() { return separator; }
    public void setSeparator(String value) { separator=value; }
    public String getComment() { return comment; }
    public void setComment(String value) { comment=value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision=value; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String value) { createBy=value; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String value) { updateBy=value; }
    public java.util.Date getCreateTime() { return createTime; }
    public void setCreateTime(java.util.Date value) { createTime=value; }
    public java.util.Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(java.util.Date value) { updateTime=value; }
}
