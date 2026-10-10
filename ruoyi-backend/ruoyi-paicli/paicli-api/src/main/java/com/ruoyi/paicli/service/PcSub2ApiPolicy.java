package com.ruoyi.paicli.service;

import com.ruoyi.paicli.domain.PcException;
import com.ruoyi.system.secret.ManagedSecretService;
import java.net.URI;
import java.net.InetAddress;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 本项目协议与验证证据规则；不改变共享密钥或其他项目。 */
public final class PcSub2ApiPolicy {
    private PcSub2ApiPolicy() {}
    public static boolean applies(Map<String,Object> p) { return "sub2api".equals(PcJson.text(p,"provider")); }
    public static void requireRunner(Map<String,Object> provider,Map<String,Object> runner) {
        if(!applies(provider))return;
        String capability="sub2api."+PcJson.text(provider,"wireApi")+".v1";
        if(!(runner.get("capabilities") instanceof java.util.List<?> caps)||!caps.contains(capability))
            throw new PcException(422,"PC_RUNNER_CAPABILITY","当前Runner未声明所选Sub2API协议能力，请升级Runner后重试");
    }
    public static int currentSecretVersion(ManagedSecretService secrets,Map<String,Object> provider,long workspace) {
        if(secrets==null)throw new PcException(422,"PC_MODEL_UNVERIFIED","Sub2API缺少当前托管密钥校验，不能创建模型任务");
        String ref=PcJson.text(provider,"secretRef");
        ManagedSecretService.Metadata metadata=secrets.metadata("AI",ref);
        validateMetadata(metadata,ref,workspace);return metadata.activeVersion();
    }
    public static void validateMetadata(ManagedSecretService.Metadata metadata,String ref,long workspace) {
        if(metadata==null||!ref.startsWith("ai.paicli.user."+workspace+".")||!ref.equals(metadata.alias())||!"AI".equals(metadata.kind())
                ||!"paicli".equals(metadata.projectCode())||!"ACTIVE".equals(metadata.status())
                ||!"sub2api".equals(metadata.providerCode())||!"chat".equals(metadata.capabilityCode())
                ||!Set.of("api-key","bearer").contains(metadata.authMode())||metadata.activeVersion()==null||metadata.activeVersion()<1)
            throw PcException.deny("Sub2API密钥必须匹配当前目录的AI/sub2api/chat用途和有效版本");
    }
    public static void provider(Map<String,Object> p) {
        if(!applies(p))return;
        if(!Set.of("responses","chat_completions").contains(PcJson.text(p,"wireApi")))throw PcException.invalid("Sub2API必须明确选择调用API");
        try {
            URI u=URI.create(PcJson.text(p,"baseUrl"));String path=u.getPath()==null?"":u.getPath().replaceAll("/+$","");
            if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null
                    ||path.endsWith("/responses")||path.endsWith("/chat/completions")||path.endsWith("/models"))throw new IllegalArgumentException();
            publicLiteralHost(u.getHost());
        }catch(Exception invalid){throw PcException.invalid("Sub2API须填写HTTPS API根地址，不能含本机/私网文字地址、认证信息、查询串或完整业务端点");}
        if(!PcJson.text(p,"loraId").isBlank())throw PcException.invalid("Sub2API本期不接受LoRA扩展");
    }
    /** 仅检查文字地址，不查询DNS；实例部署的DNS/出口约束须另核实。 */
    private static void publicLiteralHost(String raw)throws Exception {
        String host=raw.toLowerCase(Locale.ROOT).replaceAll("\\.+$","");
        if(host.equals("localhost")||host.endsWith(".localhost"))throw new IllegalArgumentException();
        if(host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1);
        if(host.contains(":")){
            if(host.contains("%"))throw new IllegalArgumentException();
            // 含冒号的IPv6字面量由JDK解析，绝不传域名给InetAddress。
            InetAddress address=InetAddress.getByName(host);byte[] bytes=address.getAddress();
            if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())throw new IllegalArgumentException();
            if(bytes.length==4){publicIpv4(bytes);return;}
            if((bytes[0]&0xfe)==0xfc)throw new IllegalArgumentException();
            boolean compatible=true;for(int i=0;i<12;i++)compatible&=bytes[i]==0;if(compatible)throw new IllegalArgumentException();
        }else if(host.matches("[0-9]+(?:\\.[0-9]+){3}")){
            byte[] bytes=new byte[4];String[] parts=host.split("\\.");
            for(int i=0;i<4;i++){int value=Integer.parseInt(parts[i]);if(value>255||!String.valueOf(value).equals(parts[i]))throw new IllegalArgumentException();bytes[i]=(byte)value;}publicIpv4(bytes);
        }else if(host.matches("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+)){0,3}"))throw new IllegalArgumentException();
    }
    private static void publicIpv4(byte[] bytes){
        int first=bytes[0]&255,second=bytes[1]&255;
        if(first==0||first==10||first==127||first>=224||first==169&&second==254||first==172&&second>=16&&second<=31
                ||first==192&&second==168||first==100&&second>=64&&second<=127||first==198&&(second==18||second==19))throw new IllegalArgumentException();
    }
    public static void model(Map<String,Object> p,Map<String,Object> provider) {
        if(!applies(provider))return;
        if(Boolean.TRUE.equals(p.get("supportsImages"))||Boolean.TRUE.equals(p.get("dsml"))||Boolean.TRUE.equals(p.get("reasoningHistory"))
                ||!PcJson.text(p,"template").isBlank()||!PcJson.text(p,"thinking").isBlank())throw PcException.invalid("Sub2API图片能力须由独立验证开启，不接受手工开启图片、DSML或其他厂商模板/思考扩展");
        if("chat_completions".equals(provider.get("wireApi"))&&!PcJson.text(p,"reasoningEffort").isBlank())throw PcException.invalid("Chat Completions未核实推理强度参数");
        // 普通表单不能伪造兼容证据；保存新定义后须重新验证。
        p.put("supportsImages",false);p.put("imageInput",false);p.put("supportsThinking",false);p.put("capabilityKnown",false);p.remove("verification");p.remove("imageVerification");
    }
    public static long modelDefinition(Map<String,Object> model,long rowVersion) {
        return model.get("configurationVersion") instanceof Number n?n.longValue():rowVersion;
    }
    public static boolean imagesVerified(Map<String,Object> model,long rowVersion,Map<String,Object> provider,long providerVersion,int secretVersion) {
        if(!applies(provider))return Boolean.TRUE.equals(model.get("supportsImages"));
        if(!"responses".equals(PcJson.text(provider,"wireApi"))||!verified(model,rowVersion,provider,providerVersion,secretVersion)
                ||!(model.get("imageVerification") instanceof Map<?,?> proof))return false;
        return Boolean.TRUE.equals(proof.get("images"))&&Boolean.TRUE.equals(proof.get("imageTools"))
                &&Boolean.TRUE.equals(proof.get("imageStream"))&&Boolean.TRUE.equals(proof.get("imageUsage"))
                &&String.valueOf(modelDefinition(model,rowVersion)).equals(String.valueOf(proof.get("modelDefinitionVersion")))
                &&String.valueOf(providerVersion).equals(String.valueOf(proof.get("providerVersion")))
                &&String.valueOf(secretVersion).equals(String.valueOf(proof.get("secretVersion")))
                &&PcJson.text(model,"modelId").equals(proof.get("model"))&&"responses".equals(proof.get("wireApi"));
    }
    public static boolean verified(Map<String,Object> model,long modelVersion,Map<String,Object> provider,long providerVersion,int secretVersion) {
        if(!applies(provider))return true;
        if(!(model.get("verification") instanceof Map<?,?> proof))return false;
        return Boolean.TRUE.equals(model.get("capabilityKnown"))&&Boolean.TRUE.equals(proof.get("text"))&&Boolean.TRUE.equals(proof.get("tools"))
                &&Boolean.TRUE.equals(proof.get("stream"))&&Boolean.TRUE.equals(proof.get("usage"))
                &&String.valueOf(modelDefinition(model,modelVersion)).equals(String.valueOf(proof.containsKey("modelDefinitionVersion")?proof.get("modelDefinitionVersion"):proof.get("modelVersion")))
                &&String.valueOf(providerVersion).equals(String.valueOf(proof.get("providerVersion")))
                &&String.valueOf(secretVersion).equals(String.valueOf(proof.get("secretVersion")))
                &&PcJson.text(provider,"wireApi").equals(proof.get("wireApi"))
                &&PcJson.text(model,"modelId").equals(proof.get("model"));
    }
}
