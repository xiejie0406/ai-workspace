package com.ruoyi.paiflow.domain;

/** PaiFlow业务数据；平台账号只以稳定userId关联。 */
public class UserAgreement {
    private Long userId;
    private Integer userAgreement;
    private java.time.LocalDateTime agreedAt;
    private Long revision;
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public Integer getUserAgreement() { return userAgreement; }
    public void setUserAgreement(Integer value) { userAgreement = value; }
    public java.time.LocalDateTime getAgreedAt() { return agreedAt; }
    public void setAgreedAt(java.time.LocalDateTime value) { agreedAt = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }
}
