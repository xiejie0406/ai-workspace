package com.ruoyi.paismart.infrastructure.intelligence;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;
import com.ruoyi.system.secret.ManagedSecretService;

/** 外部调用与进程入口分别显式启用；模块打开不自动发送资料。 */
@Component
@ConditionalOnProperty(name="paismart.enabled",havingValue="true")
public class IntelligenceSettings {
    private final Environment env;
    private final ManagedSecretService secrets;
    public IntelligenceSettings(Environment env,ManagedSecretService secrets) { this.env=env;this.secrets=secrets; }
    public String text(String key,String fallback) { return env.getProperty("paismart.intelligence."+key,fallback); }
    public int number(String key,int fallback) { return env.getProperty("paismart.intelligence."+key,Integer.class,fallback); }
    public boolean enabled(String key) { return env.getProperty("paismart.intelligence."+key,Boolean.class,false); }
    public Set<String> hosts() { return Arrays.stream(text("allowed-hosts","").split(",")).map(String::trim).filter(s->!s.isBlank()).collect(Collectors.toSet()); }
    public String secret(String reference) {
        if (reference==null || !reference.matches("(ai|platform)\\.paismart\\.[a-z0-9._-]{2,100}")) throw new IllegalArgumentException("密钥引用必须归属 PaiSmart 平台托管用途");
        var resolved=secrets.resolve(reference.startsWith("ai.")?"AI":"PLATFORM",reference);
        if(!"paismart".equals(resolved.metadata().projectCode())||!"ACTIVE".equals(resolved.metadata().status()))throw new IllegalStateException("PROVIDER_UNAVAILABLE:密钥用途归属或状态不符");
        return resolved.value();
    }
    public String modelSecret(String reference,String scope,String providerCode){var m=secrets.metadata("AI",reference);if(!scope.toLowerCase(java.util.Locale.ROOT).equals(m.capabilityCode())||!providerCode.equals(m.providerCode()))throw new IllegalStateException("PROVIDER_UNAVAILABLE:密钥的供应商或能力与模型配置不符");return secret(reference);}
    public void requireExternal() { if(!enabled("external-calls-enabled")) throw new IllegalStateException("PROVIDER_UNAVAILABLE:外部调用未启用"); }
}
