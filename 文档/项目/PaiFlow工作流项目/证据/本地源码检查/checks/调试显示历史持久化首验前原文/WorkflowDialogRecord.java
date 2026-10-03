package com.ruoyi.paiflow.domain;

import java.util.Date;

/** 调试显示历史；运行与版本绑定由中心生成，不承担Run或检查点状态真相。 */
public class WorkflowDialogRecord {
    private Long id;
    private Long spaceId;
    private Long ownerUserId;
    private Long workflowId;
    private Long workflowVersionId;
    private String runId;
    private Long eventWatermark;
    private String question;
    private String answer;
    private String questionItem;
    private String answerItem;
    private String data;
    private String sid;
    private String chatId;
    private Integer type;
    private Boolean deleted = Boolean.FALSE;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long value) { spaceId = value; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long value) { ownerUserId = value; }
    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long value) { workflowId = value; }
    public Long getWorkflowVersionId() { return workflowVersionId; }
    public void setWorkflowVersionId(Long value) { workflowVersionId = value; }
    public String getRunId() { return runId; }
    public void setRunId(String value) { runId = value; }
    public Long getEventWatermark() { return eventWatermark; }
    public void setEventWatermark(Long value) { eventWatermark = value; }
    public String getQuestion() { return question; }
    public void setQuestion(String value) { question = value; }
    public String getAnswer() { return answer; }
    public void setAnswer(String value) { answer = value; }
    public String getQuestionItem() { return questionItem; }
    public void setQuestionItem(String value) { questionItem = value; }
    public String getAnswerItem() { return answerItem; }
    public void setAnswerItem(String value) { answerItem = value; }
    public String getData() { return data; }
    public void setData(String value) { data = value; }
    public String getSid() { return sid; }
    public void setSid(String value) { sid = value; }
    public String getChatId() { return chatId; }
    public void setChatId(String value) { chatId = value; }
    public Integer getType() { return type; }
    public void setType(Integer value) { type = value; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean value) { deleted = value; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { createTime = value; }
}
