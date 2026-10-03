package com.ruoyi.paiflow.domain;

import java.time.LocalDateTime;

/** 原公开config_info九字段；私有Provider/环境配置不得进入本表。 */
public class PublicConfigRecord {
    private Long id;
    private String category;
    private String code;
    private String name;
    private String value;
    private Integer isValid;
    private String remarks;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    public Long getId(){return id;} public void setId(Long value){id=value;}
    public String getCategory(){return category;} public void setCategory(String value){category=value;}
    public String getCode(){return code;} public void setCode(String value){code=value;}
    public String getName(){return name;} public void setName(String value){name=value;}
    public String getValue(){return value;} public void setValue(String text){value=text;}
    public Integer getIsValid(){return isValid;} public void setIsValid(Integer value){isValid=value;}
    public String getRemarks(){return remarks;} public void setRemarks(String value){remarks=value;}
    public LocalDateTime getCreateTime(){return createTime;} public void setCreateTime(LocalDateTime value){createTime=value;}
    public LocalDateTime getUpdateTime(){return updateTime;} public void setUpdateTime(LocalDateTime value){updateTime=value;}
}
