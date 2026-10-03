package com.ruoyi.paismart.infrastructure;

import com.ruoyi.paismart.application.jobs.*;
import com.ruoyi.paismart.configuration.PaismartEnabled;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 显式启用Kafka时启动；outbox发送确认后才标记，重放由MySQL租约/业务幂等去重。 */
@Component
@PaismartEnabled
public class PaismartKafkaRuntime implements SmartLifecycle {
    private static final Logger LOG=LoggerFactory.getLogger(PaismartKafkaRuntime.class);
    private final JdbcTemplate jdbc;private final JobService jobs;private final List<PaismartJobHandler> handlers;private final boolean enabled;private final String brokers;private final KafkaConnectionSettings connection;
    private volatile boolean running;private ScheduledExecutorService executor;private KafkaProducer<String,String> producer;private KafkaConsumer<String,String> consumer;
    public PaismartKafkaRuntime(@Qualifier("paismartJdbc")JdbcTemplate jdbc,JobService jobs,List<PaismartJobHandler> handlers,KafkaConnectionSettings connection,@Value("${paismart.jobs.kafka-enabled:false}")boolean enabled,@Value("${paismart.jobs.bootstrap-servers:}")String brokers){this.jdbc=jdbc;this.jobs=jobs;this.handlers=handlers;this.connection=connection;this.enabled=enabled;this.brokers=brokers;}
    @Override public boolean isAutoStartup(){return enabled;}
    @Override public boolean isRunning(){return running;}
    @Override public int getPhase(){return Integer.MAX_VALUE-100;}
    @Override public synchronized void start(){
        if(running||!enabled)return;if(brokers.isBlank()) {LOG.error("Paismart Kafka未配置；本模块消费受阻");return;}
        Properties send=new Properties();send.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,brokers);send.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class);send.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class);send.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,"true");send.put(ProducerConfig.ACKS_CONFIG,"all");send.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,"10000");send.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,"30000");send.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,"10000");
        Properties recv=new Properties();recv.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,brokers);recv.put(ConsumerConfig.GROUP_ID_CONFIG,"paismart-center-workers");recv.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);recv.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);recv.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,"false");recv.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,"1");recv.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,"3600000");recv.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
        Properties security=connection.clientProperties();send.putAll(security);recv.putAll(security);
        try{producer=new KafkaProducer<>(send);consumer=new KafkaConsumer<>(recv);}catch(RuntimeException ex){if(producer!=null)producer.close(Duration.ofSeconds(5));throw new IllegalStateException("Paismart Kafka客户端初始化失败，请核对脱敏配置");}running=true;
        executor=Executors.newScheduledThreadPool(3,r->{Thread t=new Thread(r,"paismart-kafka");t.setDaemon(true);return t;});
        executor.scheduleWithFixedDelay(this::publish,0,2,TimeUnit.SECONDS);executor.execute(this::consume);
    }
    private void publish(){if(!running)return;try{
        // 任务崩溃后通过现有outbox重新发通知，而非依赖丢失的内存执行列表。
        recoverNotifications();
        var rows=jdbc.queryForList("SELECT id,job_id,topic,payload_json FROM ps_outbox WHERE state='PENDING' AND attempts<12 AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY created_at LIMIT 20");
        for(var r:rows){try{producer.send(new ProducerRecord<>(r.get("topic").toString(),r.get("job_id").toString(),r.get("payload_json").toString())).get(30,TimeUnit.SECONDS);markPublished(r.get("id").toString());}catch(Exception ex){jdbc.update("UPDATE ps_outbox SET attempts=attempts+1,next_attempt_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(30)),r.get("id"));LOG.warn("Paismart任务通知发送失败；保留outbox待重试");}}
    }catch(RuntimeException ex){LOG.warn("Paismart outbox未就绪；暂停发送");}}
    void recoverNotifications(){
        jdbc.update("UPDATE ps_outbox SET state='PENDING',next_attempt_at=CURRENT_TIMESTAMP WHERE topic='paismart.jobs' AND state='SENT' AND next_attempt_at<=CURRENT_TIMESTAMP AND job_id IN (SELECT id FROM ps_job WHERE state='QUEUED' OR (state='RUNNING' AND lease_until<CURRENT_TIMESTAMP) OR (state='RETRY' AND next_retry_at<=CURRENT_TIMESTAMP))");
        for(String id:jdbc.queryForList("SELECT j.id FROM ps_job j JOIN ps_outbox o ON o.job_id=j.id WHERE j.state IN ('QUEUED','RETRY') AND o.topic='paismart.jobs' AND o.state='PENDING' AND o.attempts>=12 ORDER BY j.created_at LIMIT 100",String.class)) jobs.failUndeliverable(id);
        // 普通任务通知由上面的事务处理；失败回执尚未同步时不能先隐藏其重试耗尽事实。
        jdbc.update("UPDATE ps_outbox SET state='FAILED' WHERE state='PENDING' AND attempts>=12 AND (topic<>'paismart.jobs' OR job_id IN (SELECT id FROM ps_job WHERE state IN ('FAILED','CANCELED','SUCCEEDED')))");
    }
    void markPublished(String id){jdbc.update("UPDATE ps_outbox SET state='SENT',attempts=0,next_attempt_at=? WHERE id=? AND state='PENDING'",java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(60)),id);}
    private void consume(){boolean paused=false;try{consumer.subscribe(List.of("paismart.jobs"));while(running){
        double usage=heapUsage();if(!paused&&usage>=connection.highWater()){consumer.pause(consumer.assignment());paused=true;LOG.warn("Paismart内存达到高水位，暂停拉取新任务");}else if(paused&&usage<=connection.lowWater()){consumer.resume(consumer.assignment());paused=false;LOG.info("Paismart内存恢复，继续拉取任务");}
        if(paused)consumer.pause(consumer.assignment());
        for(var message:consumer.poll(Duration.ofMillis(1000))){if(paused){consumer.seek(new org.apache.kafka.common.TopicPartition(message.topic(),message.partition()),message.offset());continue;}handleNotification(message,consumer);}
    }}catch(org.apache.kafka.common.errors.WakeupException ex){if(running)LOG.warn("Paismart消费被唤醒");}catch(RuntimeException ex){LOG.error("Paismart Kafka消费停止；需恢复运行配置");}finally{running=false;if(consumer!=null)consumer.close(Duration.ofSeconds(5));}}
    boolean handleNotification(ConsumerRecord<String,String> message,Consumer<String,String> cursor){
        var partition=new org.apache.kafka.common.TopicPartition(message.topic(),message.partition());
        try{
            String id;
            try{if(message.value()==null||message.value().length()>65536)throw new IllegalArgumentException();id=Objects.toString(Jsons.map(message.value()).get("jobId"),"");if(!UUID.fromString(id).toString().equalsIgnoreCase(id))throw new IllegalArgumentException();}
            catch(RuntimeException invalid){
                // 不可恢复的格式错误先持久化脱敏死信，再确认当前记录；不保存原负载。
                String hash=notificationHash(message.value());String notificationId=UUID.nameUUIDFromBytes((message.topic()+":"+message.partition()+":"+message.offset()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                jdbc.update("INSERT INTO ps_outbox(id,job_id,topic,payload_json) VALUES(?,?,'paismart.jobs.dlq',?) ON DUPLICATE KEY UPDATE id=id",UUID.randomUUID().toString(),notificationId,Jsons.write(Map.of("notificationId",notificationId,"sourceTopic",message.topic(),"partition",message.partition(),"offset",message.offset(),"payloadHash",hash,"reason","通知格式无效")));
                cursor.commitSync(Map.of(partition,new OffsetAndMetadata(message.offset()+1)));return true;
            }
            Optional<JobService.Job> claim=jobs.claimById(id,900);
            if(claim.isPresent()){
                var job=claim.get();var selected=handlers.stream().filter(h->h.supports(job.type())).toList();
                if(selected.size()!=1)jobs.fail(job.id(),job.leaseToken(),"没有唯一匹配的业务处理器");
                else{ScheduledFuture<?> heartbeat=executor.scheduleWithFixedDelay(()->{try{jobs.heartbeat(job.id(),job.leaseToken(),900);}catch(RuntimeException stale){LOG.info("任务心跳被拒绝，jobId={}",job.id());}},60,60,TimeUnit.SECONDS);
                    try{jobs.requireLease(job.id(),job.leaseToken());var result=selected.get(0).processJob(job);jobs.complete(job.id(),job.leaseToken(),result);}
                    catch(Exception ex){LOG.warn("Paismart任务执行失败，jobId={}",job.id());jobs.fail(job.id(),job.leaseToken(),safeFailure(ex));}
                    finally{heartbeat.cancel(false);}
                }
            }
            // 仅确认当前已处理分区，不能让另一个分区的成功越过失败消息。
            cursor.commitSync(Map.of(partition,new OffsetAndMetadata(message.offset()+1)));return true;
        }catch(Exception ex){cursor.seek(partition,message.offset());LOG.warn("Paismart通知暂未完成，已回退消费位置并保留任务/outbox");return false;}
    }
    private static String notificationHash(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Objects.toString(value,"").getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private static String safeFailure(Exception ex){if(ex instanceof IllegalArgumentException||ex instanceof IllegalStateException){String m=ex.getMessage();if(m!=null&&!m.contains("http")&&!m.toLowerCase(Locale.ROOT).contains("key")&&m.length()<500)return m;}return "处理失败，请按任务编号查询脱敏诊断并重试";}
    private static double heapUsage(){Runtime runtime=Runtime.getRuntime();return (double)(runtime.totalMemory()-runtime.freeMemory())/runtime.maxMemory();}
    @Override public synchronized void stop(){running=false;if(consumer!=null)consumer.wakeup();if(executor!=null)executor.shutdown();if(producer!=null)producer.close(Duration.ofSeconds(5));}
    @Override public void stop(Runnable callback){stop();callback.run();}
}
