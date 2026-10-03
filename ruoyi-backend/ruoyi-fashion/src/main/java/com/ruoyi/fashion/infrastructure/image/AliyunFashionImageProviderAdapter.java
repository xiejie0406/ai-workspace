package com.ruoyi.fashion.infrastructure.image;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import com.ruoyi.fashion.application.agent.run.FashionRuntimeException;
import com.ruoyi.fashion.application.image.port.FashionImageProviderPort;
import com.ruoyi.fashion.application.material.port.FashionObjectStoragePort;
import com.ruoyi.fashion.configuration.FashionAliyunImageProperties;
import com.ruoyi.fashion.configuration.FashionImageProperties;
import com.ruoyi.fashion.infrastructure.storage.FashionHashing;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 阿里云异步试衣窄适配器：只外发已核实素材临时URL，不传内部业务上下文。 */
public final class AliyunFashionImageProviderAdapter implements FashionImageProviderPort {
    private static final int JSON_LIMIT=1024*1024, IMAGE_LIMIT=5*1024*1024;
    private static final String PROVIDER="aliyun";
    private final FashionImageProperties image;
    private final FashionAliyunImageProperties properties;
    private final FashionObjectStoragePort storage;
    private final ObjectMapper mapper;
    private final AliyunImageHttpExchange http;
    private final Resolver resolver;
    @FunctionalInterface interface Resolver { InetAddress[] resolve(String host) throws Exception; }

    public AliyunFashionImageProviderAdapter(FashionImageProperties image,FashionAliyunImageProperties properties,
            FashionObjectStoragePort storage,ObjectMapper mapper) {
        this(image,properties,storage,mapper,AliyunImageHttpExchange.jdk(),InetAddress::getAllByName);
    }
    AliyunFashionImageProviderAdapter(FashionImageProperties image,FashionAliyunImageProperties properties,
            FashionObjectStoragePort storage,ObjectMapper mapper,AliyunImageHttpExchange http,Resolver resolver) {
        this.image=image;this.properties=properties;this.storage=storage;this.mapper=mapper;this.http=http;this.resolver=resolver;
    }
    @Override public boolean available() {
        try {apiBase();return image.isProviderEnabled()&&properties.getApiKey()!=null&&!properties.getApiKey().isBlank()
                &&!properties.getApiKey().contains("\r")&&!properties.getApiKey().contains("\n")
                &&"aitryon-plus".equals(properties.getModel())&&storage.supportsTemporaryReadUrl()
                &&properties.getEstimatedUnitCost()!=null&&properties.getEstimatedUnitCost().signum()>=0
                &&properties.getTimeout()!=null&&properties.getTimeout().compareTo(Duration.ofSeconds(1))>=0
                &&properties.getTimeout().compareTo(Duration.ofSeconds(120))<=0
                &&properties.getResultHosts()!=null&&!properties.getResultHosts().isEmpty()
                &&properties.getResultHosts().stream().allMatch(h->h!=null&&h.matches("[a-z0-9.-]+\\.aliyuncs\\.com"));
        } catch(RuntimeException invalid){return false;}
    }
    @Override public Capabilities capabilities(){return new Capabilities(PROVIDER,"aitryon-plus",List.of("top","bottom","dress"),true,true,properties.getEstimatedUnitCost(),"CNY");}
    @Override public ProviderResult submit(String requestKey,String imageType,JsonNode inputs,JsonNode parameters,int count) {
        requireAvailable();ObjectNode body;
        try {
            if(!"model".equals(imageType)||count!=1)throw invalid("每次试衣只支持一张模特结果");
            body=payload(requestKey,inputs);
        } catch(RuntimeException invalid) {
            // 本地拒绝发生在HTTP前，绝不生成付费请求。
            return new ProviderResult("failed",PROVIDER,null,mapper.createArrayNode(),java.math.BigDecimal.ZERO,"CNY","INPUT_INVALID",safeInputMessage(invalid),false);
        }
        try {
            var response=send("POST","/services/aigc/image2image/image-synthesis",mapper.writeValueAsBytes(body),true);
            if(response.status()>=400&&response.status()<500)return rejected(response,null);
            if(response.status()<200||response.status()>=300)return unknown(null,"PROVIDER_RESPONSE_UNKNOWN","试衣提交响应未确认；禁止自动重复提交");
            JsonNode data=mapper.readTree(response.body());String taskId=data.path("output").path("task_id").asText("");
            if(!taskId.matches("[A-Za-z0-9_-]{1,128}"))return unknown(null,"TASK_ID_MISSING","试衣服务未返回可查询任务编号，请人工对账；禁止重复付费提交");
            return result("queued",taskId,null,null,null,false);
        } catch(RuntimeException unknown) {return unknown(null,"PROVIDER_TRANSPORT_UNKNOWN","试衣提交通信未确认，请人工对账；禁止自动重复提交");}
    }
    @Override public ProviderResult query(String providerTaskId) {
        requireAvailable();validateTask(providerTaskId);
        try {
            var response=send("GET","/tasks/"+providerTaskId,null,false);
            if(response.status()==429||response.status()>=500)return unknown(providerTaskId,"PROVIDER_QUERY_RETRY","查询暂不可用，可继续查询原任务；不会重新提交试衣");
            if(response.status()<200||response.status()>=300)return unknown(providerTaskId,"PROVIDER_QUERY_REJECTED","无法确认原任务状态，请核对服务凭据或人工对账");
            JsonNode data=mapper.readTree(response.body()),output=data.path("output");
            if(!providerTaskId.equals(output.path("task_id").asText()))return unknown(providerTaskId,"TASK_ID_MISMATCH","查询响应任务编号不匹配，结果未采用");
            return switch(output.path("task_status").asText("UNKNOWN")) {
                case "PENDING"->result("queued",providerTaskId,null,null,null,false);
                case "PRE-PROCESSING","RUNNING","POST-PROCESSING"->result("running",providerTaskId,null,null,null,false);
                case "CANCELED"->result("cancelled",providerTaskId,null,null,"试衣任务已取消，实际费用仍需对账",false);
                case "FAILED"->result("failed",providerTaskId,null,safeCode(output.path("code").asText()),"阿里云试衣未完成，请核对模特与服装素材规范",false);
                case "SUCCEEDED"->download(providerTaskId,output.path("image_url").asText(),data.path("usage").path("image_count").asInt(1));
                default->unknown(providerTaskId,"PROVIDER_STATE_UNKNOWN","原任务状态未知或超过供应商查询期限，请人工对账");
            };
        } catch(RuntimeException unknown){return unknown(providerTaskId,"PROVIDER_QUERY_UNKNOWN","原任务查询或图片转存未确认，可查询原任务，禁止重新付费提交");}
    }
    @Override public void cancel(String providerTaskId) {
        requireAvailable();validateTask(providerTaskId);
        var response=send("POST","/tasks/"+providerTaskId+"/cancel",null,false);
        if(response.status()==400)throw new FashionRuntimeException("CANCEL_NOT_PENDING","供应商仅允许取消排队中的任务；请继续查询结果和费用",false);
        if(response.status()<200||response.status()>=300)throw new FashionRuntimeException("CANCEL_UNCONFIRMED","取消请求未确认；请继续查询原任务，取消不保证免除已产生费用",false);
        // 仅请求受理，最终取消以原任务查询的CANCELED状态为准。
    }
    private ObjectNode payload(String taskId,JsonNode inputs) {
        if(taskId==null||!taskId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw invalid("试衣素材链接必须绑定已确认的任务编号");
        if(inputs==null||!inputs.isObject())throw invalid("缺少已核实试衣素材");
        var body=mapper.createObjectNode();body.put("model",properties.getModel());var external=body.putObject("input");
        external.put("person_image_url",signed(taskId,inputs.path("model").path("objectKey").asText(),inputs.path("model").path("sha256").asText()));
        JsonNode products=inputs.path("products");if(!products.isArray())throw invalid("缺少已核实服装图片");
        int tops=0,bottoms=0,dresses=0;
        for(var product:products) {
            String position=product.path("garment_position").asText("");
            if("original".equals(position))continue;
            String field=switch(position){case "top"-> {tops++;yield "top_garment_url";}case "bottom"->{bottoms++;yield "bottom_garment_url";}case "dress"->{dresses++;yield "top_garment_url";}default->throw invalid("只支持上装、下装、连体服；鞋帽须设为原图");};
            external.put(field,signed(taskId,product.path("object_key").asText(),product.path("sha256").asText()));
        }
        if(tops>1||bottoms>1||dresses>1||dresses>0&&(tops>0||bottoms>0)||tops+bottoms+dresses==0)throw invalid("一次试衣使用一件上装及一件下装，或一件连体服");
        var params=body.putObject("parameters");params.put("resolution",-1);params.put("restore_face",true);
        return body;
    }
    private String signed(String taskId,String key,String hash) {
        if(key==null||!key.matches("[a-z0-9][a-z0-9/_.-]{0,510}")||hash==null||!hash.matches("[a-f0-9]{64}"))throw invalid("试衣素材引用或摘要无效");
        byte[] bytes=storage.read(key);if(!FashionHashing.sha256(bytes).equals(hash))throw invalid("试衣素材摘要不一致，请重新核对");
        inspect(bytes,true);
        String signed=storage.temporaryReadUrl(key,Duration.ofMinutes(15),taskId);validatePublicUrl(URI.create(signed));return signed;
    }
    private ProviderResult download(String taskId,String url,int count) {
        if(count!=1)return unknown(taskId,"RESULT_COUNT_INVALID","试衣返回图片数量不符合单张任务范围，结果未采用");
        URI uri=URI.create(url);String host=uri.getHost();
        if(host==null||!properties.getResultHosts().contains(host.toLowerCase(Locale.ROOT)))return unknown(taskId,"RESULT_HOST_REJECTED","试衣结果下载域名不在配置白名单，结果未采用");
        // 官方OSS示例可能为HTTP；仅对白名单OSS主机升级HTTPS，不发出明文请求。
        if("http".equalsIgnoreCase(uri.getScheme()))uri=URI.create("https"+uri.toString().substring(4));
        validatePublicUrl(uri);
        var response=http.send("GET",uri,Map.of("Accept","image/png,image/jpeg"),null,IMAGE_LIMIT,properties.getTimeout());
        if(response.status()!=200)return unknown(taskId,"RESULT_DOWNLOAD_UNCONFIRMED","图片下载未确认，请继续查询原任务");
        if(response.body().length>IMAGE_LIMIT)throw invalid("试衣结果超过5MB限制");
        var info=inspect(response.body(),false);String hash=FashionHashing.sha256(response.body());
        var stored=storage.putIfAbsent("proposal-results/aliyun/"+taskId+"/"+hash+"."+info.extension(),response.body(),info.contentType());
        var results=mapper.createArrayNode();var result=results.addObject();result.put("status","success");result.put("object_key",stored.objectKey());result.put("sha256",stored.sha256());result.put("content_type",info.contentType());result.put("width",info.width());result.put("height",info.height());result.put("billing_pending",true);result.putObject("provider_usage").put("image_count",count);
        // usage.image_count不是账单金额；实际费用保持未知等待真实对账。
        return result("success",taskId,results,null,null,false);
    }
    private Inspection inspect(byte[] bytes,boolean input) {
        if(bytes==null||bytes.length==0||bytes.length>IMAGE_LIMIT||input&&bytes.length<5*1024)throw invalid("试衣图片需5KB至5MB，请转换合规JPG或PNG");
        boolean png=bytes.length>8&&(bytes[0]&255)==137&&bytes[1]==80&&bytes[2]==78&&bytes[3]==71;
        boolean jpg=bytes.length>3&&(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255;
        if(!png&&!jpg)throw invalid("阿里云试衣当前只接入可核实的JPG、PNG；请先转换其他格式");
        try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw invalid("图片无法解码");var reader=readers.next();
            try {reader.setInput(stream,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<150||height<150||width>4096||height>4096)throw invalid("试衣图片宽高均须在150至4096像素内");
                if(reader.read(0)==null)throw invalid("图片损坏");return new Inspection(width,height,png?"png":"jpg",png?"image/png":"image/jpeg");
            } finally {reader.dispose();}
        } catch(FashionRuntimeException invalid){throw invalid;}catch(Exception invalid){throw invalid("图片损坏或无法核实");}
    }
    private AliyunImageHttpExchange.Response send(String method,String path,byte[] body,boolean async) {
        var headers=new LinkedHashMap<String,String>();headers.put("Authorization","Bearer "+properties.getApiKey());headers.put("Content-Type","application/json");if(async)headers.put("X-DashScope-Async","enable");
        return http.send(method,URI.create(apiBase()+path),headers,body,JSON_LIMIT,properties.getTimeout());
    }
    private String apiBase() {
        URI base=URI.create(properties.getBaseUrl());String host=base.getHost();
        if(!"https".equalsIgnoreCase(base.getScheme())||host==null||!("dashscope.aliyuncs.com".equals(host)||host.matches("ws-[a-zA-Z0-9-]+\\.cn-beijing\\.maas\\.aliyuncs\\.com"))||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||base.getPort()!=-1&&base.getPort()!=443||!"/api/v1".equals(base.getPath()))throw invalid("仅支持阿里云北京地域试衣API地址");
        return base.toString().replaceAll("/$","");
    }
    private void validatePublicUrl(URI uri) {
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getPort()!=-1&&uri.getPort()!=443)throw invalid("试衣临时图片地址必须是公开HTTPS地址");
        try {var addresses=resolver.resolve(uri.getHost());if(addresses==null||addresses.length==0)throw invalid("图片地址无法解析");for(var address:addresses)if(!publicAddress(address))throw invalid("试衣图片地址不能访问内网或本机");}
        catch(FashionRuntimeException invalid){throw invalid;}catch(Exception invalid){throw invalid("图片公网地址未确认");}
    }
    private static boolean publicAddress(InetAddress address) {
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return false;
        byte[] bytes=address.getAddress();if(bytes.length==4){int a=bytes[0]&255,b=bytes[1]&255;if(a==0||a>=224||a==100&&b>=64&&b<=127||a==169&&b==254)return false;}
        if(bytes.length==16&&((bytes[0]&0xfe)==0xfc))return false;
        return true;
    }
    private ProviderResult rejected(AliyunImageHttpExchange.Response response,String taskId) {
        String code="PROVIDER_REJECTED";try{code=safeCode(mapper.readTree(response.body()).path("code").asText());}catch(RuntimeException ignored){}
        String message=switch(response.status()){case 401,403->"试衣服务凭据或权限未就绪";case 429->"阿里云试衣限流，当前请求未受理；请先核对任务与预算";default->"阿里云拒绝试衣请求，请核对图片和参数要求";};
        return result("failed",taskId,null,code,message,false);
    }
    private ProviderResult result(String state,String id,JsonNode results,String code,String message,boolean retryable){return new ProviderResult(state,PROVIDER,id,results==null?mapper.createArrayNode():results,null,"CNY",code,message,retryable);}
    private ProviderResult unknown(String id,String code,String message){return result("unknown",id,null,code,message,false);}
    private void requireAvailable(){if(!available())throw new FashionRuntimeException("PROVIDER_DISABLED","阿里云试衣配置、素材临时访问或费用估计尚未就绪",false);}
    private static void validateTask(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{1,128}"))throw invalid("外部任务编号无效");}
    private static String safeCode(String code){return code!=null&&code.matches("[A-Za-z0-9_.-]{1,120}")?code:"PROVIDER_REJECTED";}
    private static String safeInputMessage(RuntimeException ex){return ex instanceof FashionRuntimeException?ex.getMessage():"试衣素材未通过本地核验，本次未调用外部服务";}
    private static FashionRuntimeException invalid(String message){return new FashionRuntimeException("PROVIDER_INPUT_INVALID",message,false);}
    private record Inspection(int width,int height,String extension,String contentType){}
}
