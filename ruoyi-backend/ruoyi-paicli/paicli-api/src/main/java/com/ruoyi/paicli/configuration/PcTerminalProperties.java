package com.ruoyi.paicli.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

/** 网页终端默认关闭；仅显式启用后要求终端迁移和受控来源。 */
@ConfigurationProperties(prefix="paicli.terminal")
public class PcTerminalProperties {
    private boolean enabled;
    private List<String> allowedOrigins=List.of();
    private int userLimit=2,runnerLimit=2,replayBytes=16*1024*1024,globalLimit=32;
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public List<String> getAllowedOrigins(){return allowedOrigins;} public void setAllowedOrigins(List<String> v){allowedOrigins=v==null?List.of():List.copyOf(v);}
    public int getUserLimit(){return userLimit;} public void setUserLimit(int v){userLimit=v;}
    public int getRunnerLimit(){return runnerLimit;} public void setRunnerLimit(int v){runnerLimit=v;}
    public int getReplayBytes(){return replayBytes;} public void setReplayBytes(int v){replayBytes=v;}
    public int getGlobalLimit(){return globalLimit;} public void setGlobalLimit(int v){globalLimit=v;}
    public void validate(){if(!enabled)return;if(allowedOrigins.isEmpty()||allowedOrigins.stream().anyMatch(x->!x.matches("https?://[^/]+")||x.contains("*")))throw new IllegalStateException("网页终端须明确列出精确Origin");if(userLimit<1||userLimit>8||runnerLimit<1||runnerLimit>8||globalLimit<1||globalLimit>128||replayBytes<65536||replayBytes>16*1024*1024)throw new IllegalStateException("终端额度或回放上限无效");}
}
