package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 固定预算、真实JSON与敏感字段边界；不把远端异常正文写入运行事件。 */
public final class RunJsonBoundary {
    private RunJsonBoundary() {}
    private static final Set<String> SECRET_KEYS=Set.of("password","passwd","secret","apikey","apisecret","token","accesstoken","refreshtoken","authorization","privatekey","clientsecret");
    public static void check(Object value,int maxBytes) {
        checked(value,maxBytes,Scope.PUBLIC,Domain.NONE,"");
    }
    /** 画布及固定模型协议允许Schema元数据；普通事件/输入沿用严格凭据检查。 */
    static void checkSchemaAware(Object value,int maxBytes) {
        checked(value,maxBytes,Scope.PUBLIC,Domain.WORKFLOW,"");
    }
    static void checkModel(Object value,int maxBytes) { checked(value,maxBytes,Scope.PUBLIC,Domain.MODEL,""); }
    static void checkSchema(Object value,int maxBytes) { checked(value,maxBytes,Scope.SCHEMA,Domain.NONE,""); }
    static void checkFunctions(Object value,int maxBytes) { checked(value,maxBytes,Scope.PUBLIC,Domain.MODEL,"/functions"); }
    static void checkNode(Object value,int maxBytes) { checked(value,maxBytes,Scope.PUBLIC,Domain.WORKFLOW,"/nodes/0"); }
    static void checkSparkWire(Object value,int maxBytes) { checked(value,maxBytes,Scope.PUBLIC,Domain.SPARK_WIRE,""); }
    private static void checked(Object value,int maxBytes,Scope initial,Domain domain,String path) {
        walk(value,0,initial,domain,path,null);
        if(com.alibaba.fastjson2.JSON.toJSONString(value,com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8).length>maxBytes) throw new ServiceException("运行JSON超出允许大小",413);
    }
    enum Scope { PUBLIC, SCHEMA, PROPERTY_NAMES, LITERAL }
    enum Domain { NONE, WORKFLOW, MODEL, SPARK_WIRE }
    /** 只按当前协议中的确切Schema位置进入元数据域，未知ext不能自行声明该域。 */
    static Map<?,?> workflowNode(Object value,Domain domain,String path,Map<?,?> inherited) {
        return domain==Domain.WORKFLOW && path.matches("/(?:dsl/|data/|flowData/|canvas/|execution/)?nodes/[0-9]+") && value instanceof Map<?,?> map?map:inherited;
    }
    private static boolean nodeSchema(Map<?,?> node,String key) {
        if(node==null)return false;Object data=node.get("data");
        if(data instanceof String text)try{data=com.alibaba.fastjson2.JSON.parse(text);}catch(RuntimeException ex){return false;}
        Object type=data instanceof Map<?,?> map && map.get("nodeMeta") instanceof Map<?,?> meta?meta.get("nodeType"):null;
        return ("fc_schema_params".equals(key) && "extractor-parameter".equals(type)) || ("fs_params".equals(key) && "decision-making".equals(type));
    }
    static Scope childScope(Map<?,?> parent,String key,Scope current,Domain domain,String path,Map<?,?> node) {
        if(current==Scope.PUBLIC) {
            if(domain==Domain.WORKFLOW && path.matches("/(?:dsl/|data/|flowData/|canvas/|execution/)?nodes/[0-9]+/data/nodeParam") && nodeSchema(node,key))return Scope.SCHEMA;
            if(domain==Domain.MODEL && path.matches("/(?:parameters/)?functions/[0-9]+") && "parameters".equals(key) && parent.get("name") instanceof String)return Scope.SCHEMA;
            if(domain==Domain.SPARK_WIRE && path.matches("/payload/functions/text/[0-9]+") && "parameters".equals(key) && parent.get("name") instanceof String)return Scope.SCHEMA;
        }
        if(current==Scope.LITERAL)return Scope.LITERAL;
        if(current==Scope.PROPERTY_NAMES)return Scope.SCHEMA;
        if(current==Scope.SCHEMA) {
            if(Set.of("default","const","enum","examples").contains(key))return Scope.LITERAL;
            if(Set.of("properties","patternProperties","$defs","definitions","dependentSchemas").contains(key))return Scope.PROPERTY_NAMES;
            if(Set.of("items","additionalItems","additionalProperties","unevaluatedProperties","unevaluatedItems","contains","propertyNames","allOf","anyOf","oneOf","not","if","then","else").contains(key))return Scope.SCHEMA;
        }
        return Scope.PUBLIC;
    }
    static void publicField(String key,Object value,Scope scope) {
        if(scope==Scope.LITERAL)return;
        if(scope==Scope.PROPERTY_NAMES) {
            if(!(value instanceof Map<?,?> || value instanceof Boolean))throw new ServiceException("JSON Schema属性定义必须为对象或Boolean",400);
            return;
        }
        String normalized=key.toLowerCase(java.util.Locale.ROOT).replace("_","").replace("-","");
        if(SECRET_KEYS.contains(normalized) && value!=null && !"".equals(value))throw new ServiceException("运行事件或快照不得携带凭据字段",400);
    }
    private static void walk(Object value,int depth,Scope current,Domain domain,String path,Map<?,?> inheritedNode) {
        if(domain==Domain.WORKFLOW && path.matches("/(?:resourceSnapshots/)?tool:[^/]+/openApiSchema") && value instanceof JSONObject api){ToolSchema.guardApi(api);return;}
        if(depth>48) throw new ServiceException("运行JSON嵌套过深",400);
        Map<?,?> node=workflowNode(value,domain,path,inheritedNode);
        if(value instanceof Map<?,?> map) {
            for(var entry:map.entrySet()) {
                if(!(entry.getKey() instanceof String key)) throw new ServiceException("运行JSON字段名无效",400);
                publicField(key,entry.getValue(),current);
                walk(entry.getValue(),depth+1,childScope(map,key,current,domain,path,node),domain,path+"/"+key.replace("~","~0").replace("/","~1"),node);
            }
        } else if(value instanceof List<?> list) { for(int i=0;i<list.size();i++)walk(list.get(i),depth+1,current,domain,path+"/"+i,node); }
        else if(value!=null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) throw new ServiceException("运行字段必须为JSON值",400);
        if(value instanceof Double number && !Double.isFinite(number)) throw new ServiceException("运行数值无效",400);
        if(value instanceof Float number && !Float.isFinite(number)) throw new ServiceException("运行数值无效",400);
    }
    /** 旧运行的非DSL幂等字节规则，不跟随当前工作流编译版本改变。 */
    public static String canonical(JSONObject object) {
        return encoded(object,DslCodec.V1);
    }
    /** 新运行记录明确选择的完整JSON规则；保留显式null，包括任意节点输出对象。 */
    public static String canonicalFull(JSONObject object) {
        return encoded(object,DslCodec.V2);
    }
    private static String encoded(JSONObject object,String codec) {
        return new String(Base64.getDecoder().decode(DslCodec.canonicalDslBase64(object,codec)),StandardCharsets.UTF_8);
    }
    public static String hash(JSONObject object) { return digest(canonical(object)); }
    public static String hashFull(JSONObject object) { return digest(canonicalFull(object)); }
    private static String digest(String canonical) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("运行环境缺少SHA-256",ex); }
    }
    public static void fields(JSONObject body,Set<String> required,Set<String> optional) {
        if(body==null || !body.keySet().containsAll(required) || body.keySet().stream().anyMatch(k->!required.contains(k)&&!optional.contains(k))) throw new ServiceException("运行协议字段不完整或包含未知字段",400);
    }
}
