package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.aippt.configuration.AipptEnabled;
import com.ruoyi.aippt.configuration.AipptProperties;
import com.ruoyi.aippt.domain.AipptRules;
import com.ruoyi.aippt.infrastructure.persistence.AipptRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

/** Redis只唤醒权威DB读取；网络在事务外，有界线程、等待与重试，不另建任务真相。 */
@Service @AipptEnabled
public class RedisDeliveryService implements AutoCloseable {
    private final RedisConnectionFactory redis;
    private final AipptProperties props;
    private final AipptRepository db;
    private final TransactionTemplate transactions;
    private final RedisMessageListenerContainer subscriptions;
    private final ThreadPoolExecutor publisher = executor("aippt-redis-publish", 1);
    private final ThreadPoolExecutor lifecycle = executor("aippt-redis-lifecycle", 1);
    private final ThreadPoolExecutor subscriber = executor("aippt-redis-subscribe", 1);
    private final ThreadPoolExecutor callbacks = executor("aippt-redis-notice", 128);
    private final Map<String, Runnable> listeners = new ConcurrentHashMap<>();
    private final Semaphore waiters = new Semaphore(64);
    private final AtomicLong generation = new AtomicLong();
    private final Object signal = new Object();
    private final long publishWaitMillis;
    private volatile Future<?> subscriptionStart;
    private volatile long nextSubscriptionAttempt;
    private volatile int subscriptionFailures;
    private volatile int channels;
    private volatile boolean publishHealthy = true;
    private volatile boolean closed;
    private volatile String reason = "REDIS_STARTING";
    private final String taskChannel;
    private final String eventChannel;

    @Autowired
    public RedisDeliveryService(RedisConnectionFactory redis, AipptProperties props,
            AipptRepository db, @Qualifier("aipptTransactions") TransactionTemplate transactions) {
        this(redis, props, db, transactions, new RedisMessageListenerContainer(), Duration.ofMillis(500));
    }

    RedisDeliveryService(RedisConnectionFactory redis, AipptProperties props, AipptRepository db,
            TransactionTemplate transactions, RedisMessageListenerContainer subscriptions, Duration publishWait) {
        this.redis = redis;
        this.props = props;
        this.db = db;
        this.transactions = transactions;
        this.subscriptions = subscriptions;
        this.publishWaitMillis = publishWait.toMillis();
        if (enabled()) AipptRules.require(props.getEnvironment() != null
                && props.getEnvironment().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,31}"), "Redis环境标识未核定");
        String namespace = "aippt:" + props.getEnvironment() + ":";
        taskChannel = namespace + "tasks";
        eventChannel = namespace + "events";
        subscriptions.setConnectionFactory(redis);
        subscriptions.setAutoStartup(false);
        subscriptions.setTaskExecutor(callbacks);
        subscriptions.setSubscriptionExecutor(subscriber);
        subscriptions.setMaxSubscriptionRegistrationWaitingTime(500);
        subscriptions.setRecoveryBackoff(new FixedBackOff(5000, 1));
        subscriptions.setErrorHandler(error -> degraded("REDIS_SUBSCRIPTION_UNAVAILABLE"));
        NoticeListener listener = new NoticeListener();
        subscriptions.addMessageListener(listener, List.of(new ChannelTopic(taskChannel), new ChannelTopic(eventChannel)));
        subscriptions.afterPropertiesSet(); // 不连接；只在模块/Redis开关允许时异步start。
    }

    private static ThreadPoolExecutor executor(String name, int queue) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queue), runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    boolean enabled() { return props.isEnabled() && props.getRedis().isEnabled() && !closed; }

    @PostConstruct public void start() { ensureSubscription(); }

    /** 固定白名单，不输出连接地址、异常或业务内容。 */
    public JSONObject status() {
        if (!enabled()) return JSONObject.of("status", "disabled", "reason", "REDIS_DISABLED");
        boolean connected = channels == 3 && publishHealthy && subscriptions.isListening();
        return JSONObject.of("status", connected ? "connected" : "degraded", "reason", connected ? "REDIS_CONNECTED" : "REDIS_UNAVAILABLE");
    }

    private synchronized void ensureSubscription() {
        if (!enabled() || subscriptions.isListening() || System.nanoTime() < nextSubscriptionAttempt) return;
        if (subscriptionStart != null && !subscriptionStart.isDone()) return;
        try {
            subscriptionStart = lifecycle.submit(() -> {
                try {
                    subscriptions.stop();
                    if (!enabled()) return;
                    subscriptions.start();
                    if (!enabled()) subscriptions.stop();
                    else if (!subscriptions.isListening()) subscriptionFailed();
                    else subscriptionFailures = 0;
                } catch (RuntimeException failure) {
                    subscriptionFailed();
                }
            });
        } catch (RejectedExecutionException unavailable) {
            subscriptionFailed();
        }
    }

    private void subscriptionFailed() {
        int count = Math.min(6, ++subscriptionFailures);
        nextSubscriptionAttempt = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.min(60, 1L << count));
        channels = 0;
        degraded("REDIS_SUBSCRIPTION_UNAVAILABLE");
    }

    private void degraded(String code) {
        reason = code;
        wakeAll();
    }

    /** 最多20条/每轮2秒；超时网络占用被限制在一个线程和一个排队槽。 */
    @Scheduled(fixedDelay = 1000) public void sweep() {
        if (!enabled() || TransactionSynchronizationManager.isActualTransactionActive()) return;
        ensureSubscription();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        List<JSONObject> pending;
        try { pending = db.pendingDeliveryOutbox(); }
        catch (RuntimeException unavailable) { degraded("DELIVERY_DATABASE_UNAVAILABLE"); return; }
        for (JSONObject outbox : pending) {
            if (!enabled() || System.nanoTime() >= deadline) break;
            String available = outbox.getString("available_at");
            if (available != null && Instant.parse(available).isAfter(Instant.now())) continue;
            Future<?> operation = null;
            try {
                JSONObject notice = notice(outbox);
                String channel = "task_created".equals(outbox.getString("type")) ? taskChannel : eventChannel;
                operation = publisher.submit(() -> {
                    if (!enabled()) throw new IllegalStateException("delivery stopped");
                    try (RedisConnection connection = redis.getConnection()) {
                        if (!enabled()) return;
                        connection.publish(channel.getBytes(StandardCharsets.UTF_8),
                                JSON.toJSONBytes(notice));
                    }
                });
                long left = Math.min(TimeUnit.MILLISECONDS.toNanos(publishWaitMillis), deadline - System.nanoTime());
                operation.get(Math.max(0, left), TimeUnit.NANOSECONDS);
                if (!enabled()) break;
                boolean recovered = !publishHealthy;
                publishHealthy = true;
                acknowledge(outbox, true);
                receive(new DefaultMessage(channel.getBytes(StandardCharsets.UTF_8), JSON.toJSONBytes(notice)));
                if (recovered) wakeAll();
            } catch (InterruptedException interrupted) {
                if (operation != null) operation.cancel(true);
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException unavailable) {
                if (operation != null) operation.cancel(true);
                publisher.purge();
                publishHealthy = false;
                degraded("REDIS_PUBLISH_UNAVAILABLE");
                try { acknowledge(outbox, false); }
                catch (RuntimeException databaseUnavailable) { degraded("DELIVERY_DATABASE_UNAVAILABLE"); }
            }
        }
    }

    private static JSONObject notice(JSONObject outbox) {
        if ("task_created".equals(outbox.getString("type"))) {
            UUID.fromString(outbox.getString("task_id"));
            return JSONObject.of("taskId", outbox.getString("task_id"));
        }
        AipptRules.require("event_ready".equals(outbox.getString("type")), "未知派发事件");
        UUID.fromString(outbox.getString("project_id"));
        AipptRules.require(outbox.getLongValue("event_seq") > 0, "事件序号无效");
        return JSONObject.of("projectId", outbox.getString("project_id"), "eventSeq", outbox.getLongValue("event_seq"));
    }

    /** CAS只确认本次读取的outbox版本；发布与这里的短事务严格分开。 */
    private void acknowledge(JSONObject candidate, boolean published) {
        transactions.execute(status -> {
            JSONObject current = db.lock("outbox", candidate.getString("id"));
            if (current == null || !"pending".equals(current.getString("status"))
                    || current.getLongValue("revision") != candidate.getLongValue("revision")) return null;
            if (published) {
                current.put("status", "dispatched");
                current.put("dispatched_at", Instant.now().toString());
                current.remove("error_code");
            } else {
                int attempts = Math.min(100000, current.getIntValue("attempts") + 1);
                current.put("attempts", attempts);
                current.put("available_at", Instant.now().plusSeconds(Math.min(60, 1L << Math.min(6, attempts))).toString());
                current.put("error_code", "REDIS_PUBLISH_UNAVAILABLE");
            }
            db.save("outbox", current, current.getLongValue("revision"));
            return null;
        });
    }

    public long signalVersion() { return generation.get(); }

    /** 等待不持有事务或连接；最多64个等待者，DB调用方另保留每秒查询。 */
    public void awaitSignal(long observed, long waitMillis) throws InterruptedException {
        if (waitMillis <= 0) return;
        if (closed || !waiters.tryAcquire()) {
            TimeUnit.MILLISECONDS.sleep(Math.min(1000, waitMillis));
            return;
        }
        try {
            synchronized (signal) {
                if (!closed && generation.get() == observed) signal.wait(Math.min(1000, waitMillis));
            }
        } finally { waiters.release(); }
    }

    public AutoCloseable listen(String project, Runnable onNotice) {
        AipptRules.require(listeners.size() < 100, "进度唤醒订阅已达容量上限");
        String id = project + ":" + UUID.randomUUID();
        listeners.put(id, onNotice);
        return () -> listeners.remove(id);
    }

    private void wakeAll() {
        synchronized (signal) { generation.incrementAndGet(); signal.notifyAll(); }
        listeners.values().forEach(this::notifyListener);
    }

    private void notifyListener(Runnable callback) {
        try { callback.run(); } catch (RuntimeException ignored) { /* 已关闭连接不会阻断其他通知。 */ }
    }

    /** 对配置的两个channel严格验消息；未知字段/跨环境/正文都不会触发任何写入。 */
    void receive(Message message) {
        if (!enabled() || message.getBody().length > 256) return;
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        try {
            JSONObject body = JSON.parseObject(message.getBody());
            if (taskChannel.equals(channel)) {
                if (!body.keySet().equals(Set.of("taskId")) || !(body.get("taskId") instanceof String)) return;
                UUID.fromString(body.getString("taskId"));
                synchronized (signal) { generation.incrementAndGet(); signal.notifyAll(); }
            } else if (eventChannel.equals(channel)) {
                if (!body.keySet().equals(Set.of("projectId", "eventSeq")) || !(body.get("projectId") instanceof String)) return;
                UUID.fromString(body.getString("projectId"));
                Object sequence = body.get("eventSeq");
                if (!(sequence instanceof Number number) || number.longValue() <= 0
                        || number.doubleValue() != number.longValue()) return;
                String prefix = body.getString("projectId") + ":";
                listeners.forEach((id, callback) -> { if (id.startsWith(prefix)) notifyListener(callback); });
            }
        } catch (RuntimeException malformed) { /* Redis通知不可信；权威状态始终从DB取。 */ }
    }

    private class NoticeListener implements MessageListener, SubscriptionListener {
        @Override public void onMessage(Message message, byte[] pattern) { receive(message); }
        @Override public void onChannelSubscribed(byte[] channel, long count) {
            String name = new String(channel, StandardCharsets.UTF_8);
            if (taskChannel.equals(name)) channels |= 1;
            if (eventChannel.equals(name)) channels |= 2;
            reason = "REDIS_OK";
            wakeAll();
        }
        @Override public void onChannelUnsubscribed(byte[] channel, long count) {
            channels = 0;
            degraded("REDIS_SUBSCRIPTION_UNAVAILABLE");
        }
    }

    @PreDestroy @Override public void close() {
        closed = true;
        wakeAll();
        listeners.clear();
        try {
            lifecycle.submit(() -> { subscriptions.stop(); }).get(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException | RejectedExecutionException ignored) { }
        publisher.shutdownNow();
        lifecycle.shutdownNow();
        subscriber.shutdownNow();
        callbacks.shutdownNow();
    }
}
