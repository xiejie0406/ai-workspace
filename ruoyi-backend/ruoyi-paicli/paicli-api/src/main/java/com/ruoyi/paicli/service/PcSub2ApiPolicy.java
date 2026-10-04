package com.ruoyi.paicli.service;

import com.ruoyi.paicli.domain.PcException;
import com.ruoyi.paicli.domain.PcJson;
import java.net.URI;
import java.util.Map;
import java.util.Set;

/** 本项目协议与验证证据规则；不改变共享密钥或其他项目。 */
public final class PcSub2ApiPolicy {
    private PcSub2ApiPolicy() {}
    public static boolean applies(Map<String,Object> p) { return "sub2api".equals(PcJson.text(p,"provider")); }
    public static void provider(Map<String,Object> p) {
        if(!applies(p))return;
        if(!Set.of("responses","chat_completions").contains(PcJson.text(p,"wireApi")))throw PcException.invalid("Sub2API必须明确选择调用API");
        try {
            URI u=URI.create(PcJson.text(p,"baseUrl"));String path=u.getPath()==null?"":u.getPath().replaceAll("/+$","");
            if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null
                    ||path.endsWith("/responses")||path.endsWith("/chat/completions")||path.endsWith("/models"))throw new IllegalArgumentException();
        }catch(Exception invalid){throw PcException.invalid("Sub2API须填写HTTPS API根地址，不能含认证信息、查询串或完整业务端点");}
        if(!PcJson.text(p,"loraId").isBlank())throw PcException.invalid("Sub2API本期不接受LoRA扩展");
    }
    public static void model(Map<String,Object> p,Map<String,Object> provider) {
        if(!applies(provider))return;
        if(Boolean.TRUE.equals(p.get("supportsImages"))||Boolean.TRUE.equals(p.get("dsml"))||Boolean.TRUE.equals(p.get("reasoningHistory"))
                ||!PcJson.text(p,"template").isBlank()||!PcJson.text(p,"thinking").isBlank())throw PcException.invalid("Sub2API本期只支持文本，不接受图片、DSML或其他厂商模板/思考扩展");
        if("chat_completions".equals(provider.get("wireApi"))&&!PcJson.text(p,"reasoningEffort").isBlank())throw PcException.invalid("Chat Completions未核实推理强度参数");
        // 普通表单不能伪造兼容证据；保存新定义后须重新验证。
        p.put("supportsImages",false);p.put("imageInput",false);p.put("supportsThinking",false);p.put("capabilityKnown",false);p.remove("verification");
    }
    public static boolean verified(Map<String,Object> model,long modelVersion,Map<String,Object> provider,long providerVersion,int secretVersion) {
        if(!applies(provider))return true;
        if(!(model.get("verification") instanceof Map<?,?> proof))return false;
        return Boolean.TRUE.equals(model.get("capabilityKnown"))&&Boolean.TRUE.equals(proof.get("text"))&&Boolean.TRUE.equals(proof.get("tools"))
                &&Boolean.TRUE.equals(proof.get("stream"))&&Boolean.TRUE.equals(proof.get("usage"))
                &&String.valueOf(modelVersion).equals(String.valueOf(proof.get("modelVersion")))
                &&String.valueOf(providerVersion).equals(String.valueOf(proof.get("providerVersion")))
                &&String.valueOf(secretVersion).equals(String.valueOf(proof.get("secretVersion")))
                &&PcJson.text(provider,"wireApi").equals(proof.get("wireApi"))
                &&PcJson.text(model,"modelId").equals(proof.get("model"));
    }
}
