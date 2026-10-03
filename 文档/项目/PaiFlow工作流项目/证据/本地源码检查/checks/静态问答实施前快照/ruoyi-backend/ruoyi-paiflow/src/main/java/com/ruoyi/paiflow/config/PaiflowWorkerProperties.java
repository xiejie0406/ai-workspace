package com.ruoyi.paiflow.config;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 仅中心短期Worker服务身份，未配置或过期时关闭；不保存浏览器身份。 */
@Component
@ConfigurationProperties(prefix="paiflow.workers")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class PaiflowWorkerProperties {
    private Map<String, Credential> identities=new LinkedHashMap<>();
    public Map<String, Credential> getIdentities() { return identities; }
    public void setIdentities(Map<String, Credential> value) { identities=value; }
    public static class Credential {
        private String engineProfile;
        private String tokenSha256;
        private Instant expiresAt;
        public String getEngineProfile() { return engineProfile; }
        public void setEngineProfile(String value) { engineProfile=value; }
        public String getTokenSha256() { return tokenSha256; }
        public void setTokenSha256(String value) { tokenSha256=value; }
        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant value) { expiresAt=value; }
    }
}
