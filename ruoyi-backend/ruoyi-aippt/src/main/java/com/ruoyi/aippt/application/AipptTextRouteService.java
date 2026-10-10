package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.aippt.configuration.AipptEnabled;
import com.ruoyi.aippt.configuration.AipptProperties;
import com.ruoyi.aippt.domain.AipptException;
import com.ruoyi.aippt.domain.AipptRules;
import com.ruoyi.system.secret.ManagedSecretService;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 中心固定路由及托管用途；快照只保存非秘密元数据。 */
@Service @AipptEnabled
public class AipptTextRouteService {
    public static final Set<String> TEXT_TYPES=Set.of("generate_outline","generate_slide","ai_edit","relayout");
    private final AipptProperties props;
    private final ManagedSecretService secrets;

    public AipptTextRouteService(AipptProperties props,ManagedSecretService secrets) {
        this.props=props;
        this.secrets=secrets;
    }

    public JSONObject snapshot() {
        var p=props.getProvider();
        if(!p.isEnabled()||!p.isTextEnabled())throw unavailable();
        URI base=baseUri(p.getBaseUrl());
        AipptRules.require(Set.of("chat_completions","responses").contains(p.getWireApi()),"文本调用协议必须显式选择");
        AipptRules.require(p.getModel()!=null&&p.getModel().matches("[A-Za-z0-9][A-Za-z0-9._:/@-]{0,199}"),"文本模型ID无效");
        AipptRules.require(p.getSecretRef()!=null&&p.getSecretRef().matches("ai\\.aippt\\.[A-Za-z0-9._:-]{1,110}"),"文本密钥必须属于本项目固定用途");
        AipptRules.require(p.getMaxCalls()>=1&&p.getMaxCalls()<=8&&p.getMaxOutputTokens()>=256&&p.getMaxOutputTokens()<=32768,"文本调用预算超限");
        AipptRules.require(p.getTimeoutSeconds()>=1&&p.getTimeoutSeconds()<=80&&p.getMaxResponseBytes()>=1024&&p.getMaxResponseBytes()<=4*1024*1024,"文本传输预算超限");
        AipptRules.require(Set.of("max_tokens","max_completion_tokens").contains(p.getOutputTokenParameter()),"Chat输出参数须显式选择受支持值");
        List<String> hosts=p.getAllowedHosts();
        AipptRules.require(hosts!=null&&!hosts.isEmpty()&&hosts.size()<=16,"文本出口必须配置精确域名白名单");
        AipptRules.require(hosts.stream().allMatch(AipptTextRouteService::validHost)&&hosts.contains(base.getHost()),"文本出口不在精确域名白名单");
        ManagedSecretService.Metadata m;
        try { m=secrets.metadata("AI",p.getSecretRef()); }
        catch(RuntimeException failure) { throw unavailable(); }
        validateMetadata(m,p.getSecretRef());
        JSONObject route=JSONObject.of("baseUrl",base.toString(),"wireApi",p.getWireApi(),"model",p.getModel(),
            "secretRef",p.getSecretRef(),"secretVersion",m.activeVersion(),"secretRowVersion",m.rowVersion(),
            "maxCalls",p.getMaxCalls(),"maxOutputTokens",p.getMaxOutputTokens(),"timeoutSeconds",p.getTimeoutSeconds(),
            "maxResponseBytes",p.getMaxResponseBytes(),"outputTokenParameter",p.getOutputTokenParameter(),
            "allowedHosts",hosts.stream().sorted().distinct().toList());
        route.put("routeVersion",AipptRules.hash(JSON.toJSONString(route,JSONWriter.Feature.MapSortField)));
        return route;
    }

    public void assertCurrent(JSONObject frozen) {
        if(frozen==null||!Objects.equals(frozen.getString("routeVersion"),snapshot().getString("routeVersion")))
            throw changed();
    }

    public String resolve(JSONObject frozen) {
        // 先核对当前元数据归属，才解密固定用途，不能跨项目解密后再判断。
        assertCurrent(frozen);
        ManagedSecretService.Resolved r;
        try { r=secrets.resolve("AI",frozen.getString("secretRef")); }
        catch(RuntimeException failure) { throw unavailable(); }
        validateMetadata(r.metadata(),frozen.getString("secretRef"));
        if(r.metadata().activeVersion()!=frozen.getIntValue("secretVersion")||r.metadata().rowVersion()!=frozen.getLongValue("secretRowVersion"))throw changed();
        if(r.value()==null||r.value().isBlank()||r.value().length()>16384||r.value().chars().anyMatch(c->c<=32||c>=127))throw unavailable();
        return r.value();
    }

    private static void validateMetadata(ManagedSecretService.Metadata m,String alias) {
        if(!Objects.equals(alias,m.alias())||!"AI".equals(m.kind())||!"aippt".equals(m.projectCode())
            ||!"sub2api".equals(m.providerCode())||!"text".equals(m.capabilityCode())
            ||!Set.of("api-key","bearer").contains(m.authMode())||!"ACTIVE".equals(m.status())
            ||m.activeVersion()==null||m.activeVersion()<1)throw unavailable();
    }

    static boolean validHost(String host) {
        return host!=null&&host.length()<=253&&host.equals(host.toLowerCase(java.util.Locale.ROOT))
            &&host.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")
            &&!host.matches("[0-9.]+")&&!host.endsWith(".localhost")&&!host.endsWith(".local");
    }

    public static URI baseUri(String value) {
        try {
            URI uri=URI.create(value);
            String path=uri.getRawPath();
            AipptRules.require("https".equals(uri.getScheme())&&validHost(uri.getHost())
                &&(uri.getPort()==-1||uri.getPort()==443)&&uri.getRawUserInfo()==null&&uri.getRawQuery()==null&&uri.getRawFragment()==null
                &&("".equals(path)||"/".equals(path)||"/v1".equals(path)||"/v1/".equals(path)),"文本根地址须为白名单HTTPS域名的根路径或/v1");
            return URI.create("https://"+uri.getHost()+"/v1");
        } catch(AipptException failure) { throw failure; }
        catch(RuntimeException failure) { throw AipptException.invalid("文本根地址无效"); }
    }

    public static AipptException changed() { return new AipptException(409,"TEXT_ROUTE_CHANGED","文本路由或托管密钥版本已变化，停止原任务后续调用"); }
    public static AipptException unavailable() { return new AipptException(503,"TEXT_PROVIDER_UNAVAILABLE","文本通道未启用或本项目托管用途未就绪"); }
}
