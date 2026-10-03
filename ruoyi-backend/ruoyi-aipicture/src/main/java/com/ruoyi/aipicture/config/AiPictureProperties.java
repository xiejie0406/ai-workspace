package com.ruoyi.aipicture.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("aipicture")
public class AiPictureProperties {
    private boolean enabled;
    private String jdbcUrl = "";
    private String username = "";
    private String password = "";
    private String storageRoot = "";
    private String contractsDirectory = "";
    private String workerUrl = "";
    private String serviceId = "aipicture-java";
    private String workerServiceId = "aipicture-worker";
    private String serviceSecret = "";
    private String publicInternalBaseUrl = "";
    private int leaseSeconds = 90;
    private int workerTimeoutSeconds = 90;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled=v; }
    public String getJdbcUrl() { return jdbcUrl; }
    public void setJdbcUrl(String v) { jdbcUrl=v; }
    public String getUsername() { return username; }
    public void setUsername(String v) { username=v; }
    public String getPassword() { return password; }
    public void setPassword(String v) { password=v; }
    public String getStorageRoot() { return storageRoot; }
    public void setStorageRoot(String v) { storageRoot=v; }
    public String getContractsDirectory() { return contractsDirectory; }
    public void setContractsDirectory(String v) { contractsDirectory=v; }
    public String getWorkerUrl() { return workerUrl; }
    public void setWorkerUrl(String v) { workerUrl=v; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String v) { serviceId=v; }
    public String getWorkerServiceId() { return workerServiceId; }
    public void setWorkerServiceId(String v) { workerServiceId=v; }
    public String getServiceSecret() { return serviceSecret; }
    public void setServiceSecret(String v) { serviceSecret=v; }
    public String getPublicInternalBaseUrl() { return publicInternalBaseUrl; }
    public void setPublicInternalBaseUrl(String v) { publicInternalBaseUrl=v; }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int v) { leaseSeconds=v; }
    public int getWorkerTimeoutSeconds() { return workerTimeoutSeconds; }
    public void setWorkerTimeoutSeconds(int v) { workerTimeoutSeconds=v; }
}
