package com.ruoyi.paiflow.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 运维已批准的精确模型用途目录；空配置和未启用条目均不授予调用。无明文密钥字段。 */
@Component
@ConfigurationProperties(prefix="paiflow.model-runtime")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class ModelRuntimeProperties {
    private List<Profile> profiles=new ArrayList<>();
    public List<Profile> getProfiles(){return profiles;}
    public void setProfiles(List<Profile> value){profiles=value==null?new ArrayList<>():value;}
    public static class Profile {
        private boolean enabled,validationAllowed,deploymentAllowed,functionCall;
        private String sourceProvider,runtimeProvider,domain,endpoint,credentialRef,secretAlias,secretProvider,capability,authMode,deploymentEndpoint;
        private int maxCalls=0,maxTokens=0,maxRequestBytes=0,timeoutSeconds=0;
        public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
        public boolean isValidationAllowed(){return validationAllowed;} public void setValidationAllowed(boolean v){validationAllowed=v;}
        public boolean isDeploymentAllowed(){return deploymentAllowed;} public void setDeploymentAllowed(boolean v){deploymentAllowed=v;}
        public boolean isFunctionCall(){return functionCall;} public void setFunctionCall(boolean v){functionCall=v;}
        public String getSourceProvider(){return sourceProvider;} public void setSourceProvider(String v){sourceProvider=v;}
        public String getRuntimeProvider(){return runtimeProvider;} public void setRuntimeProvider(String v){runtimeProvider=v;}
        public String getDomain(){return domain;} public void setDomain(String v){domain=v;}
        public String getEndpoint(){return endpoint;} public void setEndpoint(String v){endpoint=v;}
        public String getCredentialRef(){return credentialRef;} public void setCredentialRef(String v){credentialRef=v;}
        public String getSecretAlias(){return secretAlias;} public void setSecretAlias(String v){secretAlias=v;}
        public String getSecretProvider(){return secretProvider;} public void setSecretProvider(String v){secretProvider=v;}
        public String getCapability(){return capability;} public void setCapability(String v){capability=v;}
        public String getAuthMode(){return authMode;} public void setAuthMode(String v){authMode=v;}
        public String getDeploymentEndpoint(){return deploymentEndpoint;} public void setDeploymentEndpoint(String v){deploymentEndpoint=v;}
        public int getMaxCalls(){return maxCalls;} public void setMaxCalls(int v){maxCalls=v;}
        public int getMaxTokens(){return maxTokens;} public void setMaxTokens(int v){maxTokens=v;}
        public int getMaxRequestBytes(){return maxRequestBytes;} public void setMaxRequestBytes(int v){maxRequestBytes=v;}
        public int getTimeoutSeconds(){return timeoutSeconds;} public void setTimeoutSeconds(int v){timeoutSeconds=v;}
    }
}
