package com.ruoyi.fashion.infrastructure.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Flow;
import javax.imageio.ImageIO;
import com.ruoyi.fashion.application.agent.run.FashionRuntimeException;
import com.ruoyi.fashion.application.material.port.FashionObjectStoragePort;
import com.ruoyi.fashion.application.material.port.StoredFashionObject;
import com.ruoyi.fashion.configuration.FashionAliyunImageProperties;
import com.ruoyi.fashion.configuration.FashionImageProperties;
import com.ruoyi.fashion.infrastructure.storage.FashionHashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 使用内存素材与注入HTTP替身；不联网、不读密钥、不访问业务库。 */
class AliyunFashionImageProviderAdapterTest {
    private static final String BUSINESS_TASK="7b93c823-c1b1-44de-923a-813b94e5711f";
    private final JsonMapper mapper=JsonMapper.builder().build();
    private FashionImageProperties image;
    private FashionAliyunImageProperties properties;
    private MemoryStorage storage;
    private FakeHttp http;
    private AliyunFashionImageProviderAdapter adapter;
    private byte[] png;
    @BeforeEach void setup() throws Exception {
        png=image();image=new FashionImageProperties();image.setProviderEnabled(true);properties=new FashionAliyunImageProperties();properties.setApiKey("test-only-secret");
        storage=new MemoryStorage();storage.objects.put("model/person.png",png);storage.objects.put("materials/top.png",png);storage.objects.put("materials/bottom.png",png);
        http=new FakeHttp();adapter=new AliyunFashionImageProviderAdapter(image,properties,storage,mapper,http,host->new InetAddress[]{InetAddress.getByAddress(new byte[]{8,8,8,8})});
    }
    @Test void safeDefaultRequiresExplicitProviderCredentialsAndTemporaryLinks() {
        image.setProviderEnabled(false);assertFalse(adapter.available());assertThrows(FashionRuntimeException.class,()->adapter.submit(BUSINESS_TASK,"model",inputs("top","bottom"),mapper.createObjectNode(),1));assertTrue(http.calls.isEmpty());
        image.setProviderEnabled(true);properties.setApiKey("");assertFalse(adapter.available());properties.setApiKey("test-only-secret");storage.links=false;assertFalse(adapter.available());
        storage.links=true;properties.setBaseUrl("https://localhost/api/v1");assertFalse(adapter.available());properties.setBaseUrl("https://dashscope.aliyuncs.com/api/v1");assertTrue(adapter.available());assertEquals(List.of("top","bottom","dress"),adapter.capabilities().supportedPositions());
    }
    @Test void externalPayloadContainsOnlySignedSupportedImagesAndFixedParameters() {
        http.responses.add(json(200,"{\"output\":{\"task_id\":\"task-1\",\"task_status\":\"PENDING\"}}"));
        var input=inputs("top","bottom");input.withArray("products").addObject().put("garment_position","original").put("object_key","private/shoe.png").put("mainSku","shoe-900");
        input.put("poolId","pool-sensitive");input.put("customerName","客户敏感信息");input.put("maxCost",99);input.put("draftVersion",3);
        var result=adapter.submit(BUSINESS_TASK,"model",input,mapper.createObjectNode().put("client_note","私密备注"),1);
        assertEquals("queued",result.status());assertEquals("task-1",result.providerTaskId());assertNull(result.actualCost());assertEquals(1,http.calls.size());
        var call=http.calls.get(0);var body=mapper.readTree(call.body());assertEquals("enable",call.headers().get("X-DashScope-Async"));assertEquals("Bearer test-only-secret",call.headers().get("Authorization"));
        assertEquals(Set.of("model","input","parameters"),body.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        assertEquals(3,body.path("input").size());assertTrue(body.path("input").path("top_garment_url").asText().startsWith("https://"));assertTrue(body.path("parameters").path("restore_face").asBoolean());assertEquals(-1,body.path("parameters").path("resolution").asInt());
        String sent=new String(call.body(),StandardCharsets.UTF_8);for(String forbidden:List.of("pool-sensitive","客户敏感信息","shoe-900","private/shoe.png","internal-id","client_note","draftVersion","object_key","maxCost"))assertFalse(sent.contains(forbidden),forbidden);
        assertEquals(Map.of("model/person.png",BUSINESS_TASK,"materials/top.png",BUSINESS_TASK,"materials/bottom.png",BUSINESS_TASK),storage.boundLinks);
        assertTrue(body.path("input").path("person_image_url").asText().contains("task="+BUSINESS_TASK));
    }
    @Test void dressUsesTopFieldAndCannotBeMixedWithOtherPositions() {
        http.responses.add(json(200,"{\"output\":{\"task_id\":\"dress-task\"}}"));var result=adapter.submit(BUSINESS_TASK,"model",inputs("dress"),mapper.createObjectNode(),1);assertEquals("queued",result.status());var input=mapper.readTree(http.calls.get(0).body()).path("input");assertTrue(input.has("top_garment_url"));assertFalse(input.has("bottom_garment_url"));
        http.calls.clear();assertEquals("failed",adapter.submit(BUSINESS_TASK,"model",inputs("dress","bottom"),mapper.createObjectNode(),1).status());assertTrue(http.calls.isEmpty());
    }
    @Test void invalidHashImageAndDuplicatePositionsAreRejectedBeforeAnyHttp() {
        assertEquals("failed",adapter.submit("unbound-key","model",inputs("top"),mapper.createObjectNode(),1).status());assertTrue(storage.boundLinks.isEmpty());assertTrue(http.calls.isEmpty());
        var input=inputs("top");input.withObject("model").put("sha256","0".repeat(64));var bad=adapter.submit(BUSINESS_TASK,"model",input,mapper.createObjectNode(),1);assertEquals("failed",bad.status());assertEquals("INPUT_INVALID",bad.errorCode());assertEquals(java.math.BigDecimal.ZERO,bad.actualCost());assertTrue(http.calls.isEmpty());
        storage.objects.put("model/person.png",new byte[]{1,2,3});assertEquals("failed",adapter.submit(BUSINESS_TASK,"model",inputs("top"),mapper.createObjectNode(),1).status());assertTrue(http.calls.isEmpty());
        storage.objects.put("model/person.png",png);assertEquals("failed",adapter.submit(BUSINESS_TASK,"model",inputs("top","top"),mapper.createObjectNode(),1).status());assertTrue(http.calls.isEmpty());assertEquals("failed",adapter.submit(BUSINESS_TASK,"model",inputs("original"),mapper.createObjectNode(),1).status());
    }
    @Test void explicitLimitRejectionDoesNotRetryOrLeakResponseSecrets() {
        http.responses.add(json(429,"{\"code\":\"Throttling.RateQuota\",\"message\":\"test-only-secret https://private/signature\"}"));var result=adapter.submit(BUSINESS_TASK,"model",inputs("top"),mapper.createObjectNode(),1);assertEquals("failed",result.status());assertFalse(result.retryable());assertEquals("Throttling.RateQuota",result.errorCode());assertFalse(result.errorMessage().contains("test-only-secret"));assertEquals(1,http.calls.size());
    }
    @Test void missingTaskIdAndTransportTimeoutRemainUnknownWithoutResubmission() {
        http.responses.add(json(200,"{\"output\":{\"task_status\":\"PENDING\"}}"));var unknown=adapter.submit(BUSINESS_TASK,"model",inputs("top"),mapper.createObjectNode(),1);assertEquals("unknown",unknown.status());assertNull(unknown.providerTaskId());assertFalse(unknown.retryable());assertEquals(1,http.calls.size());
        http.failure=new FashionRuntimeException("TIMEOUT","connection timeout",true);assertEquals("unknown",adapter.submit(BUSINESS_TASK,"model",inputs("top"),mapper.createObjectNode(),1).status());assertEquals(2,http.calls.size());
    }
    @Test void queryMapsIntermediateCancellationAndUnknownStatuses() {
        for(var entry:Map.of("PENDING","queued","PRE-PROCESSING","running","RUNNING","running","POST-PROCESSING","running","CANCELED","cancelled","UNKNOWN","unknown").entrySet()){
            http.responses.add(query(entry.getKey(),null));var result=adapter.query("task-1");assertEquals(entry.getValue(),result.status());assertEquals("task-1",result.providerTaskId());}
        http.responses.add(json(429,"{}"));assertEquals("unknown",adapter.query("task-1").status());assertThrows(FashionRuntimeException.class,()->adapter.query("../../other-task"));
    }
    @Test void successfulResultUsesPrivateHashObjectAndLeavesActualBillingUnknown() {
        http.responses.add(query("SUCCEEDED","http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/result.png?Expires=123"));http.responses.add(new AliyunImageHttpExchange.Response(200,png));var result=adapter.query("task-1");assertEquals("success",result.status());assertNull(result.actualCost());var saved=result.results().get(0);assertEquals("success",saved.path("status").asText());assertTrue(saved.path("object_key").asText().startsWith("proposal-results/aliyun/task-1/"));assertEquals(FashionHashing.sha256(png),saved.path("sha256").asText());assertEquals(256,saved.path("width").asInt());assertTrue(saved.path("billing_pending").asBoolean());assertEquals(1,saved.path("provider_usage").path("image_count").asInt());assertArrayEquals(png,storage.objects.get(saved.path("object_key").asText()));assertEquals("https",http.calls.get(1).uri().getScheme());assertFalse(http.calls.get(1).headers().containsKey("Authorization"));assertFalse(saved.toString().contains("Expires"));
    }
    @Test void untrustedDomainPrivateDnsAndRedirectNeverBecomeDownloadOrAdoptedImages() throws Exception {
        http.responses.add(query("SUCCEEDED","https://attacker.example/result.png"));assertEquals("unknown",adapter.query("task-1").status());assertEquals(1,http.calls.size());
        adapter=new AliyunFashionImageProviderAdapter(image,properties,storage,mapper,http,host->new InetAddress[]{InetAddress.getByAddress(new byte[]{127,0,0,1})});http.responses.add(query("SUCCEEDED","https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/result.png"));assertEquals("unknown",adapter.query("task-1").status());assertEquals(2,http.calls.size());
        adapter=new AliyunFashionImageProviderAdapter(image,properties,storage,mapper,http,host->new InetAddress[]{InetAddress.getByAddress(new byte[]{8,8,8,8})});http.responses.add(query("SUCCEEDED","https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/result.png"));http.responses.add(json(302,""));assertEquals("unknown",adapter.query("task-1").status());assertEquals(4,http.calls.size());assertEquals(3,storage.objects.size());
    }
    @Test void invalidOrOversizedResultIsNotStoredAsSuccessfulImage() {
        http.responses.add(query("SUCCEEDED","https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/result.png"));http.responses.add(json(200,"not an image"));assertEquals("unknown",adapter.query("task-1").status());
        http.responses.add(query("SUCCEEDED","https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/result.png"));http.responses.add(new AliyunImageHttpExchange.Response(200,new byte[5*1024*1024+1]));assertEquals("unknown",adapter.query("task-1").status());assertEquals(3,storage.objects.size());
    }
    @Test void cancellationOnlyRequestsPendingTaskAndDoesNotInventCancelledResult() {
        http.responses.add(json(200,"{\"request_id\":\"cancel-request\"}"));assertDoesNotThrow(()->adapter.cancel("task-1"));assertEquals("POST",http.calls.get(0).method());assertEquals("/api/v1/tasks/task-1/cancel",http.calls.get(0).uri().getPath());
        http.responses.add(json(400,"{\"code\":\"UnsupportedOperation\"}"));var failure=assertThrows(FashionRuntimeException.class,()->adapter.cancel("task-1"));assertEquals("CANCEL_NOT_PENDING",failure.code());assertEquals(2,http.calls.size());
    }
    @Test void boundedHttpSubscriberCancelsOversizedBodyBeforeBufferGrowth() {
        var body=new AliyunImageHttpExchange.LimitedBody(4);boolean[] cancelled={false};body.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){cancelled[0]=true;}});body.onNext(List.of(ByteBuffer.wrap(new byte[5])));assertTrue(cancelled[0]);assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
        var valid=new AliyunImageHttpExchange.LimitedBody(4);valid.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){}});valid.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2}),ByteBuffer.wrap(new byte[]{3,4})));valid.onComplete();assertArrayEquals(new byte[]{1,2,3,4},valid.getBody().toCompletableFuture().join());
    }
    private tools.jackson.databind.node.ObjectNode inputs(String...positions){var root=mapper.createObjectNode();root.putObject("model").put("objectKey","model/person.png").put("sha256",FashionHashing.sha256(png));var products=root.putArray("products");for(int i=0;i<positions.length;i++)products.addObject().put("garment_position",positions[i]).put("object_key",i==0?"materials/top.png":"materials/bottom.png").put("sha256",FashionHashing.sha256(png));return root;}
    private AliyunImageHttpExchange.Response query(String state,String url){var root=mapper.createObjectNode();var output=root.putObject("output");output.put("task_id","task-1");output.put("task_status",state);if(url!=null)output.put("image_url",url);root.putObject("usage").put("image_count",1);return json(200,root.toString());}
    private static AliyunImageHttpExchange.Response json(int status,String json){return new AliyunImageHttpExchange.Response(status,json.getBytes(StandardCharsets.UTF_8));}
    private static byte[] image() throws Exception {var image=new BufferedImage(256,256,BufferedImage.TYPE_INT_RGB);var random=new Random(7);for(int y=0;y<256;y++)for(int x=0;x<256;x++)image.setRGB(x,y,random.nextInt());var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);return output.toByteArray();}
    private static final class MemoryStorage implements FashionObjectStoragePort {
        final Map<String,byte[]> objects=new HashMap<>();boolean links=true;
        final Map<String,String> boundLinks=new LinkedHashMap<>();
        public StoredFashionObject putIfAbsent(String key,byte[] content,String type){objects.putIfAbsent(key,content.clone());return new StoredFashionObject(key,FashionHashing.sha256(content),content.length,type);}
        public byte[] read(String key){return objects.get(key);}
        public boolean supportsTemporaryReadUrl(){return links;}
        public String temporaryReadUrl(String key,Duration ttl){throw new AssertionError("不得使用未绑定任务的临时链接");}
        public String temporaryReadUrl(String key,Duration ttl,String taskId){assertEquals(Duration.ofMinutes(15),ttl);assertEquals(BUSINESS_TASK,taskId);boundLinks.put(key,taskId);return "https://media.example.test/signed?task="+taskId+"&token="+key;}
    }
    private static final class FakeHttp implements AliyunImageHttpExchange {
        record Call(String method,URI uri,Map<String,String> headers,byte[] body){}
        final List<Call> calls=new ArrayList<>();final Deque<Response> responses=new ArrayDeque<>();RuntimeException failure;
        public Response send(String method,URI uri,Map<String,String> headers,byte[] body,int limit,Duration timeout){calls.add(new Call(method,uri,headers,body));if(failure!=null)throw failure;return responses.removeFirst();}
    }
}
