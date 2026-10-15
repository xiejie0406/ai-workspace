package com.ruoyi.paicli.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="paicli")
public class PcProperties {
    private boolean enabled;
    private String expectedDatabase="";
    private int leaseSeconds=60, heartbeatSeconds=15, deviceSessionSeconds=900, approvalSeconds=900, eventRetentionDays=90;
    private boolean formalEvaluationPaused=true;
    private java.util.List<String> sub2ApiHttpAllowedRoots=java.util.List.of();
    public java.util.List<String> getSub2ApiHttpAllowedRoots(){return sub2ApiHttpAllowedRoots;}
    public void setSub2ApiHttpAllowedRoots(java.util.List<String> roots){sub2ApiHttpAllowedRoots=roots==null?java.util.List.of():java.util.List.copyOf(roots);}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public String getExpectedDatabase(){return expectedDatabase;} public void setExpectedDatabase(String v){expectedDatabase=v;}
    public int getLeaseSeconds(){return leaseSeconds;} public void setLeaseSeconds(int v){leaseSeconds=v;}
    public int getHeartbeatSeconds(){return heartbeatSeconds;} public void setHeartbeatSeconds(int v){heartbeatSeconds=v;}
    public int getDeviceSessionSeconds(){return deviceSessionSeconds;} public void setDeviceSessionSeconds(int v){deviceSessionSeconds=v;}
    public int getApprovalSeconds(){return approvalSeconds;} public void setApprovalSeconds(int v){approvalSeconds=v;}
    public int getEventRetentionDays(){return eventRetentionDays;} public void setEventRetentionDays(int v){eventRetentionDays=v;}
    public boolean isFormalEvaluationPaused(){return formalEvaluationPaused;} public void setFormalEvaluationPaused(boolean v){formalEvaluationPaused=v;}
    public void validate(){
        com.ruoyi.paicli.service.PcSub2ApiPolicy.validateHttpAllowedRoots(sub2ApiHttpAllowedRoots);
        if(expectedDatabase==null||!expectedDatabase.matches("[A-Za-z0-9_-]+"))throw new IllegalStateException("启用PaiCLI须明确paicli.expected-database，不能自动创建数据库");
        if(heartbeatSeconds<1||leaseSeconds<heartbeatSeconds*2||leaseSeconds>600||deviceSessionSeconds<leaseSeconds||deviceSessionSeconds>86400||approvalSeconds<30||approvalSeconds>3600||eventRetentionDays<1||eventRetentionDays>365)throw new IllegalStateException("PaiCLI租约、心跳或保留时间配置非法");
    }
}
