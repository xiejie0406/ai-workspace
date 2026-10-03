package com.ruoyi.paiflow.domain;

public class ModelFrameRecord {
    private String operationId,frameJson,frameHash;
    private Long seq;
    private Integer frameBytes;
    public String getOperationId(){return operationId;} public void setOperationId(String value){operationId=value;}
    public String getFrameJson(){return frameJson;} public void setFrameJson(String value){frameJson=value;}
    public String getFrameHash(){return frameHash;} public void setFrameHash(String value){frameHash=value;}
    public Long getSeq(){return seq;} public void setSeq(Long value){seq=value;}
    public Integer getFrameBytes(){return frameBytes;} public void setFrameBytes(Integer value){frameBytes=value;}
}
