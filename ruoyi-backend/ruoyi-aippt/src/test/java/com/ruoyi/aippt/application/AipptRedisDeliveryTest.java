package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.aippt.configuration.AipptProperties;
import com.ruoyi.aippt.domain.AipptException;
import com.ruoyi.aippt.infrastructure.persistence.AipptRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真H2/MyBatis outbox配合FakeRedis；不连任何Redis/MySQL或外部服务。 */
class AipptRedisDeliveryTest {
    private record Setup(AipptPersistenceFixture f, RedisConnectionFactory redis, RedisConnection connection,
            RedisMessageListenerContainer container, RedisDeliveryService service) implements AutoCloseable {
        @Override public void close() { service.close(); }
    }

    private Setup setup(boolean enabled) throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        f.props.setEnabled(enabled);
        f.props.setEnvironment("isolated-test");
        RedisConnectionFactory redis = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(redis.getConnection()).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return connection;
        });
        when(connection.publish(any(), any())).thenReturn(1L);
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        when(container.isListening()).thenReturn(true);
        RedisDeliveryService service = new RedisDeliveryService(redis, f.props, f.db, f.tx,
                container, Duration.ofMillis(80));
        return new Setup(f, redis, connection, container, service);
    }

    private JSONObject task(AipptPersistenceFixture f) {
        return f.tx.execute(status -> f.service.enqueue(f.project(), "quality",
                JSONObject.of("sensitive_text", "正文不可广播"), new JSONObject()));
    }

    private void readySubscriptions(Setup s) {
        ArgumentCaptor<MessageListener> listener = ArgumentCaptor.forClass(MessageListener.class);
        verify(s.container).addMessageListener(listener.capture(), anyCollection());
        SubscriptionListener callback = (SubscriptionListener) listener.getValue();
        callback.onChannelSubscribed("aippt:isolated-test:tasks".getBytes(StandardCharsets.UTF_8), 1);
        callback.onChannelSubscribed("aippt:isolated-test:events".getBytes(StandardCharsets.UTF_8), 2);
    }

    @Test void moduleOrDeliveryDisabledDoesNotConnectOrConsumePersistentNotices() throws Exception {
        try (Setup s = setup(false)) {
            task(s.f);
            s.service.start(); s.service.sweep();
            verify(s.redis, never()).getConnection();
            verify(s.container, never()).start();
            assertTrue(s.f.db.pendingDeliveryOutbox().stream().allMatch(row -> "pending".equals(row.getString("status"))));
            assertEquals(JSONObject.of("status", "disabled", "reason", "REDIS_DISABLED"), s.service.status());
            s.f.props.setEnabled(true); s.f.props.getRedis().setEnabled(false);
            s.service.sweep();
            verify(s.redis, never()).getConnection();
        }
    }

    @Test void successfulPublishUsesOnlyConfiguredNamespaceAndIdsThenCasAcknowledges() throws Exception {
        try (Setup s = setup(true)) {
            JSONObject task = task(s.f);
            JSONObject cleanup = s.f.service.record(s.f.project, s.f.owner, "pending");
            cleanup.put("type", "asset_cleanup");
            s.f.db.insert("outbox", cleanup);
            readySubscriptions(s);
            s.service.sweep();
            assertTrue(s.f.db.pendingDeliveryOutbox().isEmpty());
            assertEquals("pending", s.f.db.get("outbox", cleanup.getString("id")).getString("status"));
            ArgumentCaptor<byte[]> channels = ArgumentCaptor.forClass(byte[].class);
            ArgumentCaptor<byte[]> messages = ArgumentCaptor.forClass(byte[].class);
            verify(s.connection, times(2)).publish(channels.capture(), messages.capture());
            boolean foundTask = false;
            for (int i = 0; i < messages.getAllValues().size(); i++) {
                String channel = new String(channels.getAllValues().get(i), StandardCharsets.UTF_8);
                String bytes = new String(messages.getAllValues().get(i), StandardCharsets.UTF_8);
                JSONObject notice = JSON.parseObject(bytes);
                System.out.println("AIPPT_CAPTURED_REDIS " + JSONObject.of("channel", channel, "body", notice).toJSONString());
                assertTrue(channel.startsWith("aippt:isolated-test:"));
                assertFalse(bytes.contains("正文") || bytes.contains("owner") || bytes.contains("payload") || bytes.contains("lease"));
                if (channel.endsWith(":tasks")) {
                    foundTask = true;
                    assertEquals(Set.of("taskId"), notice.keySet());
                    assertEquals(task.getString("id"), notice.getString("taskId"));
                } else {
                    assertEquals(Set.of("projectId", "eventSeq"), notice.keySet());
                    assertEquals(s.f.project, notice.getString("projectId"));
                    assertEquals(1, notice.getLongValue("eventSeq"));
                }
            }
            assertTrue(foundTask);
            assertEquals(JSONObject.of("status", "connected", "reason", "REDIS_CONNECTED"), s.service.status());
        }
    }

    @Test void connectionFailureLeavesOutboxPendingWithFixedCodeAndRetryDispatchesSameRows() throws Exception {
        try (Setup s = setup(true)) {
            task(s.f);
            when(s.redis.getConnection()).thenThrow(new RedisConnectionFailureException("isolated failure"));
            s.service.sweep();
            assertEquals(2, s.f.db.pendingDeliveryOutbox().size());
            for (JSONObject row : s.f.db.pendingDeliveryOutbox()) {
                assertEquals("pending", row.getString("status"));
                assertEquals(1, row.getIntValue("attempts"));
                assertEquals("REDIS_PUBLISH_UNAVAILABLE", row.getString("error_code"));
                assertTrue(Instant.parse(row.getString("available_at")).isAfter(Instant.now()));
                row.put("available_at", Instant.now().minusSeconds(1).toString());
                s.f.db.save("outbox", row, row.getLongValue("revision"));
            }
            assertEquals(JSONObject.of("status", "degraded", "reason", "REDIS_UNAVAILABLE"), s.service.status());
            doReturn(s.connection).when(s.redis).getConnection();
            s.service.sweep();
            assertTrue(s.f.db.pendingDeliveryOutbox().isEmpty());
        }
    }

    @Test void blockingPublisherHasBoundedWaitAndDoesNotHoldDatabaseTransaction() throws Exception {
        try (Setup s = setup(true)) {
            task(s.f);
            when(s.connection.publish(any(), any())).thenAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                Thread.sleep(10000);
                return 1L;
            });
            long started = System.nanoTime();
            s.service.sweep();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000);
            assertEquals(2, s.f.db.pendingDeliveryOutbox().size());
            assertTrue(s.f.db.pendingDeliveryOutbox().stream().allMatch(row -> row.getIntValue("attempts") == 1));
        }
    }

    @Test void crossEnvironmentOrExtraPayloadCannotWakeReadersAndProjectWakeIsScoped() throws Exception {
        try (Setup s = setup(true)) {
            AtomicInteger own = new AtomicInteger(), other = new AtomicInteger();
            try (var first = s.service.listen(s.f.project, own::incrementAndGet);
                 var second = s.service.listen(UUID.randomUUID().toString(), other::incrementAndGet)) {
                JSONObject event = JSONObject.of("projectId", s.f.project, "eventSeq", 2);
                s.service.receive(new DefaultMessage("aippt:other:events".getBytes(StandardCharsets.UTF_8), JSON.toJSONBytes(event)));
                assertEquals(0, own.get());
                event.put("payload", "不得广播业务正文");
                s.service.receive(new DefaultMessage("aippt:isolated-test:events".getBytes(StandardCharsets.UTF_8), JSON.toJSONBytes(event)));
                assertEquals(0, own.get());
                event.remove("payload");
                s.service.receive(new DefaultMessage("aippt:isolated-test:events".getBytes(StandardCharsets.UTF_8), JSON.toJSONBytes(event)));
                assertEquals(1, own.get());
                assertEquals(0, other.get());
            }
        }
    }

    @Test void databaseAcknowledgementFailureAllowsRepeatPublishButNeverDuplicateClaim() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        f.props.setEnabled(true); f.props.setEnvironment("isolated-test");
        JSONObject task = task(f);
        AipptRepository db = spy(f.db);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            JSONObject row = call.getArgument(1);
            if ("task_created".equals(row.getString("type")) && "dispatched".equals(row.getString("status"))
                    && attempts.getAndIncrement() == 0) throw new IllegalStateException("isolated acknowledgement failure");
            return call.callRealMethod();
        }).when(db).save(eq("outbox"), any(), anyLong());
        RedisConnectionFactory redis = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(redis.getConnection()).thenReturn(connection);
        when(connection.publish(any(), any())).thenReturn(1L);
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        when(container.isListening()).thenReturn(true);
        try (RedisDeliveryService delivery = new RedisDeliveryService(redis, f.props, db, f.tx, container, Duration.ofMillis(80))) {
            delivery.sweep();
            assertEquals(1, f.db.pendingDeliveryOutbox().size());
            JSONObject pending = f.db.pendingDeliveryOutbox().get(0);
            assertEquals("task_created", pending.getString("type"));
            pending.put("available_at", Instant.now().minusSeconds(1).toString());
            f.db.save("outbox", pending, pending.getLongValue("revision"));
            delivery.sweep();
            assertTrue(f.db.pendingDeliveryOutbox().isEmpty());
            verify(connection, times(3)).publish(any(), any());
            JSONObject request = JSONObject.of("workerId", "fixture", "catalogVersion", f.catalog.version());
            assertEquals(task.getString("id"), f.leases().claim(request).getString("taskId"));
            assertNull(f.leases().claim(request));
            assertEquals(1, f.db.get("task", task.getString("id")).getIntValue("attempt"));
        }
    }

    @Test void subscriptionConnectionFailureDoesNotBlockStartupOrExposeProviderError() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        f.props.setEnabled(true); f.props.setEnvironment("isolated-test");
        RedisConnectionFactory redis = mock(RedisConnectionFactory.class);
        CountDownLatch attempted = new CountDownLatch(1);
        when(redis.getConnection()).thenAnswer(call -> {
            attempted.countDown();
            throw new RedisConnectionFailureException("isolated unavailable");
        });
        try (RedisDeliveryService service = new RedisDeliveryService(redis, f.props, f.db, f.tx)) {
            long started = System.nanoTime();
            service.start();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500);
            assertTrue(attempted.await(2, TimeUnit.SECONDS));
            assertEquals(Set.of("status", "reason"), service.status().keySet());
            assertEquals("degraded", service.status().getString("status"));
            assertEquals("REDIS_UNAVAILABLE", service.status().getString("reason"));
        }
    }

    @Test void enabledRedisRequiresExplicitValidEnvironmentButDisabledConfigurationDoesNot() {
        AipptProperties props = new AipptProperties();
        assertDoesNotThrow(props::validateEnabledConfiguration);
        props.setEnabled(true);
        assertThrows(AipptException.class, props::validateEnabledConfiguration);
        props.setEnvironment("aippt:other");
        assertThrows(AipptException.class, props::validateEnabledConfiguration);
        props.setEnvironment("reviewed-channel_1");
        assertDoesNotThrow(props::validateEnabledConfiguration);
        props.getRedis().setEnabled(false); props.setEnvironment("");
        assertDoesNotThrow(props::validateEnabledConfiguration);
    }
}
