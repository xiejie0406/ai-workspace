package com.ruoyi.paismart.application.intelligence;
import com.ruoyi.paismart.infrastructure.PaismartKafkaRuntime;
import com.ruoyi.paismart.infrastructure.storage.DocumentObjectStore;
import com.ruoyi.paismart.infrastructure.intelligence.*;
import com.ruoyi.system.secret.ManagedSecretService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 只测试端口替身和本地路径；不对真实依赖发探测请求。 */
class DependencyStatusServiceTest {
    MockEnvironment env;StringRedisTemplate redis;ObjectProvider<PaismartKafkaRuntime> kafka;DocumentObjectStore store;ControlledHttp http;DependencyStatusService status;
    @TempDir Path temporary;
    @BeforeEach @SuppressWarnings("unchecked") void prepare(){env=new MockEnvironment();redis=mock(StringRedisTemplate.class);kafka=mock(ObjectProvider.class);store=mock(DocumentObjectStore.class);when(store.probe()).thenReturn(Map.of("state","DISABLED","tested",false,"scope","CONFIGURED_BUCKET_HEAD","reason","未启用"));http=mock(ControlledHttp.class);status=new DependencyStatusService(env,redis,kafka,store,new IntelligenceSettings(env,mock(ManagedSecretService.class)),http);}
    Map<String,Object> component(String name){return status.components().stream().filter(row->name.equals(row.get("name"))).findFirst().orElseThrow();}
    void esConfigured(){env.setProperty("paismart.intelligence.external-calls-enabled","true");env.setProperty("paismart.intelligence.elasticsearch.endpoint","https://es.invalid");env.setProperty("paismart.intelligence.allowed-hosts","es.invalid");}
    @Test void disabledExternalSystemsNeverTriggerRequests(){assertEquals("DISABLED",component("Elasticsearch").get("state"));assertEquals("DISABLED",component("Kafka消费").get("state"));assertEquals("DISABLED",component("支付").get("state"));verifyNoInteractions(http,kafka);}
    @Test void unconfiguredApprovedEsIsNotReportedReady(){env.setProperty("paismart.intelligence.external-calls-enabled","true");assertEquals("NOT_CONFIGURED",component("Elasticsearch").get("state"));verifyNoInteractions(http);}
    @Test void redisReadyRequiresActualPongFromItsSharedConnection(){var factory=mock(RedisConnectionFactory.class);var connection=mock(RedisConnection.class);when(redis.getConnectionFactory()).thenReturn(factory);when(factory.getConnection()).thenReturn(connection);when(connection.ping()).thenReturn("PONG");var result=component("Redis");assertEquals("READY",result.get("state"));assertEquals("SHARED_REDIS_PING",result.get("scope"));verify(connection).close();}
    @Test void readFailureDoesNotLeakConnectionDetails(){var factory=mock(RedisConnectionFactory.class);when(redis.getConnectionFactory()).thenReturn(factory);when(factory.getConnection()).thenThrow(new IllegalStateException("sensitive-address-and-key"));var result=component("Redis");assertEquals("UNAVAILABLE",result.get("state"));assertFalse(result.get("reason").toString().contains("sensitive"));}
    @Test void esProbeIsBoundedGetAndStatesOnlyClusterReadEvidence(){esConfigured();when(http.json("GET","https://es.invalid/_cluster/health",null,null,10)).thenReturn(Map.of("status","yellow","timed_out",false));var result=component("Elasticsearch");assertEquals("READY",result.get("state"));assertEquals("ES_CLUSTER_READ",result.get("scope"));verify(http).json("GET","https://es.invalid/_cluster/health",null,null,10);}
    @Test void redEsAndUnknownKafkaStateRemainUnavailable(){esConfigured();when(http.json(anyString(),anyString(),any(),any(),eq(10))).thenReturn(Map.of("status","red"));assertEquals("UNAVAILABLE",component("Elasticsearch").get("state"));env.setProperty("paismart.jobs.kafka-enabled","true");env.setProperty("paismart.jobs.bootstrap-servers","broker.invalid:9092");assertEquals("UNAVAILABLE",component("Kafka消费").get("state"));}
    @Test void kafkaReadyDescribesOnlyTheExistingConsumerLoop(){env.setProperty("paismart.jobs.kafka-enabled","true");env.setProperty("paismart.jobs.bootstrap-servers","broker.invalid:9092");var runtime=mock(PaismartKafkaRuntime.class);when(kafka.getIfAvailable()).thenReturn(runtime);when(runtime.isRunning()).thenReturn(true);var result=component("Kafka消费");assertEquals("READY",result.get("state"));assertEquals("LOCAL_CONSUMER_LOOP",result.get("scope"));assertTrue(result.get("reason").toString().contains("未证明所有broker"));verify(runtime,never()).start();}
    @Test void parserReadinessOnlyChecksConfiguredPathsWithoutLaunching()throws Exception{env.setProperty("paismart.intelligence.parse.process-enabled","true");env.setProperty("paismart.intelligence.parse.worker-classpath",temporary.toString());Path cli=temporary.resolve("lit-test-placeholder.exe");Files.writeString(cli,"test placeholder");env.setProperty("paismart.intelligence.parse.liteparse-command",cli.toString());assertEquals("READY",component("Tika解析Worker").get("state"));assertEquals("CLASSPATH_EXISTENCE",component("Tika解析Worker").get("scope"));assertEquals("READY",component("LiteParse").get("state"));env.setProperty("paismart.intelligence.parse.worker-classpath",temporary+java.io.File.pathSeparator);assertEquals("NOT_CONFIGURED",component("Tika解析Worker").get("state"));}
    @Test void minioStatusIsTheAdapterProbeWithoutObjectWrite(){var row=Map.<String,Object>of("state","READY","tested",true,"scope","CONFIGURED_BUCKET_HEAD","reason","HEAD桶存在");when(store.probe()).thenReturn(row);assertEquals("READY",component("MinIO").get("state"));verify(store).probe();verify(store,never()).put(anyString(),any(),anyLong(),anyString());verify(store,never()).remove(anyString());}
}
