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
        private boolean enabled,validationAllowed,deploymentAllowed,functionCall,invocationAllowed;
        private List<Long> allowedUserIds=new ArrayList<>(),allowedSpaceIds=new ArrayList<>();
        private List<String> allowedDeploymentAddresses=new ArrayList<>();
        private String sourceProvider,runtimeProvider,domain,endpoint,credentialRef,secretAlias,secretProvider,capability,authMode,deploymentEndpoint,inferenceEndpoint;
        private List<String> allowedInferenceAddresses=new ArrayList<>();
        private int maxCalls=0,maxTokens=0,maxRequestBytes=0,timeoutSeconds=0;
        /** 运维核证的 Provider 默认最大输出，仅用于省略 max_tokens 的预算；不写入请求。 */
        private Integer providerDefaultMaxTokens;
        public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
        public boolean isValidationAllowed(){return validationAllowed;} public void setValidationAllowed(boolean v){validationAllowed=v;}
        public boolean isDeploymentAllowed(){return deploymentAllowed;} public void setDeploymentAllowed(boolean v){deploymentAllowed=v;}
        public boolean isFunctionCall(){return functionCall;} public void setFunctionCall(boolean v){functionCall=v;}
        public boolean isInvocationAllowed(){return invocationAllowed;} public void setInvocationAllowed(boolean v){invocationAllowed=v;}
        public List<Long> getAllowedUserIds(){return allowedUserIds;} public void setAllowedUserIds(List<Long> v){allowedUserIds=v==null?new ArrayList<>():new ArrayList<>(v);}
        public List<Long> getAllowedSpaceIds(){return allowedSpaceIds;} public void setAllowedSpaceIds(List<Long> v){allowedSpaceIds=v==null?new ArrayList<>():new ArrayList<>(v);}
        public List<String> getAllowedDeploymentAddresses(){return allowedDeploymentAddresses;} public void setAllowedDeploymentAddresses(List<String> v){allowedDeploymentAddresses=v==null?new ArrayList<>():new ArrayList<>(v);}
        public List<String> getAllowedInferenceAddresses(){return allowedInferenceAddresses;} public void setAllowedInferenceAddresses(List<String> v){allowedInferenceAddresses=v==null?new ArrayList<>():new ArrayList<>(v);}
        public String getInferenceEndpoint(){return inferenceEndpoint;} public void setInferenceEndpoint(String v){inferenceEndpoint=v;}
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
        public Integer getProviderDefaultMaxTokens(){return providerDefaultMaxTokens;} public void setProviderDefaultMaxTokens(Integer v){providerDefaultMaxTokens=v;}
        public int getMaxRequestBytes(){return maxRequestBytes;} public void setMaxRequestBytes(int v){maxRequestBytes=v;}
        public int getTimeoutSeconds(){return timeoutSeconds;} public void setTimeoutSeconds(int v){timeoutSeconds=v;}
    }
}
