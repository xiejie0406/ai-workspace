package com.ruoyi.paiflow.domain;

/** 提示模板/分组由中心持有；官方目录使用独立标志而非客户端UID。 */
public class PromptRecord {
    public Long id,spaceId,ownerUserId,groupId,revision;
    public String name,promptKey,payloadJson,createBy,updateBy;
    public Integer kind,official,deleted;
    public java.util.Date createTime,updateTime;
    public Long getId(){return id;} public void setId(Long value){id=value;}
}
