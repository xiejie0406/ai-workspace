package com.ruoyi.paiflow.service;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
/** 运维批准的准确工具版本/用途；默认无目标，不从HTTP动词推断副作用。 */
@Component @ConfigurationProperties(prefix="paiflow.tools.runtime")
public class ToolRuntimeProperties {
 private List<Profile> profiles=new ArrayList<>();public List<Profile> getProfiles(){return profiles;}public void setProfiles(List<Profile> v){profiles=v==null?new ArrayList<>():v;}
 public static class Profile {
  private boolean enabled;private long toolId;private String configurationHash,operationId,effectClass,endpoint,pathPattern,credentialRef,secretAlias,secretProvider,secretCapability,secretAuthMode,authenticationIn,authenticationName;
  private List<Long> allowedUserIds=new ArrayList<>(),allowedSpaceIds=new ArrayList<>();private List<String> allowedPrivateAddresses=new ArrayList<>();private int timeoutSeconds,maxCalls,maxRequestBytes,maxResponseBytes;
  public boolean isEnabled(){return enabled;}public void setEnabled(boolean v){enabled=v;}public long getToolId(){return toolId;}public void setToolId(long v){toolId=v;}
  public String getConfigurationHash(){return configurationHash;}public void setConfigurationHash(String v){configurationHash=v;}
  public String getOperationId(){return operationId;}public void setOperationId(String v){operationId=v;}
  public String getEffectClass(){return effectClass;}public void setEffectClass(String v){effectClass=v;}
  public String getEndpoint(){return endpoint;}public void setEndpoint(String v){endpoint=v;}
  public String getPathPattern(){return pathPattern;}public void setPathPattern(String v){pathPattern=v;}
  public String getCredentialRef(){return credentialRef;}public void setCredentialRef(String v){credentialRef=v;}
  public String getSecretAlias(){return secretAlias;}public void setSecretAlias(String v){secretAlias=v;}
  public String getSecretProvider(){return secretProvider;}public void setSecretProvider(String v){secretProvider=v;}
  public String getSecretCapability(){return secretCapability;}public void setSecretCapability(String v){secretCapability=v;}
  public String getSecretAuthMode(){return secretAuthMode;}public void setSecretAuthMode(String v){secretAuthMode=v;}
  public String getAuthenticationIn(){return authenticationIn;}public void setAuthenticationIn(String v){authenticationIn=v;}
  public String getAuthenticationName(){return authenticationName;}public void setAuthenticationName(String v){authenticationName=v;}
  public List<Long> getAllowedUserIds(){return allowedUserIds;}public void setAllowedUserIds(List<Long> v){allowedUserIds=v==null?new ArrayList<>():v;}
  public List<Long> getAllowedSpaceIds(){return allowedSpaceIds;}public void setAllowedSpaceIds(List<Long> v){allowedSpaceIds=v==null?new ArrayList<>():v;}
  public List<String> getAllowedPrivateAddresses(){return allowedPrivateAddresses;}public void setAllowedPrivateAddresses(List<String> v){allowedPrivateAddresses=v==null?new ArrayList<>():v;}
  public int getTimeoutSeconds(){return timeoutSeconds;}public void setTimeoutSeconds(int v){timeoutSeconds=v;}
  public int getMaxCalls(){return maxCalls;}public void setMaxCalls(int v){maxCalls=v;}
  public int getMaxRequestBytes(){return maxRequestBytes;}public void setMaxRequestBytes(int v){maxRequestBytes=v;}
  public int getMaxResponseBytes(){return maxResponseBytes;}public void setMaxResponseBytes(int v){maxResponseBytes=v;}
 }
}
