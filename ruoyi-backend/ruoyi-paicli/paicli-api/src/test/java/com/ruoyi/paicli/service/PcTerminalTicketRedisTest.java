package com.ruoyi.paicli.service;

import com.ruoyi.framework.config.RedisConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 显式启用才访问已有本机Redis；只写本轮随机测试key，并在finally清理这些key。 */
@EnabledIfSystemProperty(named="paicli.test.sharedRedis",matches="true")
class PcTerminalTicketRedisTest {
    private LettuceConnectionFactory factory;
    private RedisTemplate<Object,Object> template;
    private final Set<String> ownedKeys=new HashSet<>();
    @BeforeEach void connect(){factory=new LettuceConnectionFactory("127.0.0.1",6379);factory.afterPropertiesSet();template=new RedisConfig().redisTemplate(factory);}
    @AfterEach void cleanup(){try{if(template!=null&&!ownedKeys.isEmpty())template.delete(new ArrayList<Object>(ownedKeys));}finally{if(factory!=null)factory.destroy();}}
    private String key(){String value="paicli:test:terminal-ticket:"+UUID.randomUUID();ownedKeys.add(value);return value;}
    @Test void existingFastJsonSerializationAndThirtySecondTtlSurviveAtomicConsume(){
        String key=key(),value="{\"terminalId\":1,\"clientId\":\"临时测试\"}";template.opsForValue().set(key,value,Duration.ofSeconds(30));
        Long ttl=template.getExpire(key);assertNotNull(ttl);assertTrue(ttl>0&&ttl<=30);
        assertEquals(value,template.execute(PcTerminalService.CONSUME_TICKET_SCRIPT,List.of(key)));assertNull(template.execute(PcTerminalService.CONSUME_TICKET_SCRIPT,List.of(key)));assertFalse(Boolean.TRUE.equals(template.hasKey(key)));
    }
    @Test void simultaneousConsumersCanOnlyReceiveOneTicket()throws Exception{
        String key=key(),value="one-time-fixture";template.opsForValue().set(key,value,Duration.ofSeconds(30));
        ExecutorService pool=Executors.newFixedThreadPool(12);CountDownLatch gate=new CountDownLatch(1);
        try{
            List<Future<String>> calls=new ArrayList<>();for(int i=0;i<12;i++)calls.add(pool.submit(()->{gate.await();return template.execute(PcTerminalService.CONSUME_TICKET_SCRIPT,List.of(key));}));gate.countDown();
            int received=0;for(Future<String> call:calls){String result=call.get(15,TimeUnit.SECONDS);if(result!=null){assertEquals(value,result);received++;}}assertEquals(1,received);
        }finally{pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}
    }
    @Test void expiredTicketIsNotRecreatedByConsume()throws Exception{
        String key=key();template.opsForValue().set(key,"expired-fixture",Duration.ofMillis(100));Thread.sleep(400);
        assertNull(template.execute(PcTerminalService.CONSUME_TICKET_SCRIPT,List.of(key)));assertFalse(Boolean.TRUE.equals(template.hasKey(key)));
    }
}
