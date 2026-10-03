package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.aippt.domain.AipptException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** claim等待与过滤仍以H2正式租约为准；Fake通知不携带任务payload。 */
class AipptClaimWaitTest {
    private JSONObject request(AipptPersistenceFixture f) {
        return JSONObject.of("workerId", "fixture", "catalogVersion", f.catalog.version());
    }
    private JSONObject enqueue(AipptPersistenceFixture f, String type) {
        return f.tx.execute(status -> f.service.enqueue(f.project(), type, new JSONObject(), new JSONObject()));
    }

    @Test void omittedTypesAllowsAnyTaskWhileEmptyArrayDoesNotClaimOrAdvanceAttempt() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject task = enqueue(f, "quality");
        JSONObject empty = request(f); empty.put("types", new JSONArray()); empty.put("waitMillis", 10000);
        assertNull(f.leases().claim(empty));
        assertEquals("queued", f.db.get("task", task.getString("id")).getString("status"));
        assertEquals(0, f.db.get("task", task.getString("id")).getIntValue("attempt"));
        assertEquals(task.getString("id"), f.leases().claim(request(f)).getString("taskId"));
    }

    @Test void explicitTypesOnlyClaimsMatchingSevenTypeContracts() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject parse = enqueue(f, "parse_source"), quality = enqueue(f, "quality");
        JSONObject request = request(f); request.put("types", JSONArray.of("quality"));
        assertEquals(quality.getString("id"), f.leases().claim(request).getString("taskId"));
        assertEquals("queued", f.db.get("task", parse.getString("id")).getString("status"));
        assertNull(f.leases().claim(request));
    }

    @Test void typeFilterPrecedesCandidateLimitSoEarlierOtherTypesDoNotHideRequestedWork() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        List<JSONObject> earlier = new ArrayList<>();
        for (int i = 0; i < 100; i++) earlier.add(enqueue(f, "parse_source"));
        // 正式MyBatis插入顺序保证quality在原LIMIT候选窗之后；不消费其他类型来绕过筛选。
        assertEquals(100, f.db.claimable().size());
        JSONObject quality = enqueue(f, "quality");
        assertTrue(f.db.claimable().stream().noneMatch(task -> quality.getString("id").equals(task.getString("id"))));
        JSONObject request = request(f); request.put("types", JSONArray.of("quality"));
        JSONObject claimed = f.leases().claim(request);
        assertNotNull(claimed, "请求类型的第101个就绪任务不应被前100个其他类型遮蔽");
        assertEquals(quality.getString("id"), claimed.getString("taskId"));
        for (JSONObject other : earlier) {
            JSONObject current = f.db.get("task", other.getString("id"));
            assertEquals("queued", current.getString("status"));
            assertEquals(0, current.getIntValue("attempt"));
        }
    }

    @Test void strictWaitAndTypesErrorsAreRejectedBeforeDispatchDatabaseWork() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        for (Object invalid : Arrays.asList(null, true, "100", 0.5, -1, 10001)) {
            JSONObject request = request(f); request.put("waitMillis", invalid);
            assertEquals(422, assertThrows(AipptException.class, () -> f.leases().claim(request)).status());
        }
        for (Object invalid : Arrays.asList(null, true, "[]", JSONObject.of("type", "quality"), JSONArray.of("unknown"), JSONArray.of(1))) {
            JSONObject request = request(f); request.put("types", invalid);
            assertEquals(422, assertThrows(AipptException.class, () -> f.leases().claim(request)).status());
        }
        JSONObject unknown = request(f); unknown.put("environment", "other");
        assertEquals(422, assertThrows(AipptException.class, () -> f.leases().claim(unknown)).status());
        assertTrue(f.db.children("task", f.project).isEmpty());
        assertTrue(f.db.children("outbox", f.project).isEmpty());
    }

    @Test void workerIdSupports128AndInvalidBlankOr129IsRejected() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject task = enqueue(f, "quality");
        JSONObject request = request(f); request.put("workerId", "w".repeat(128)); request.put("waitMillis", 10000);
        assertEquals(task.getString("id"), f.leases().claim(request).getString("taskId"));
        for (Object invalid : List.of("", "w".repeat(129), true)) {
            JSONObject bad = request(f); bad.put("workerId", invalid);
            assertEquals(422, assertThrows(AipptException.class, () -> f.leases().claim(bad)).status());
        }
    }

    @Test void boundedWaitReturnsNullWithoutRedisAndWithoutHoldingTransaction() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject request = request(f); request.put("waitMillis", 120);
        long started = System.nanoTime();
        assertNull(f.leases().claim(request));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsed >= 80 && elapsed < 1000, "actual wait " + elapsed);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test void notificationWakesWaitingClaimThenDatabaseFencesExactlyOneAttempt() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        f.props.setEnabled(true); f.props.setEnvironment("isolated-test");
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        when(container.isListening()).thenReturn(true);
        try (RedisDeliveryService delivery = spy(new RedisDeliveryService(mock(RedisConnectionFactory.class), f.props,
                f.db, f.tx, container, Duration.ofMillis(80)))) {
            TaskLeaseService leases = f.leases(); leases.setDelivery(delivery);
            CountDownLatch waitingWithoutTransaction = new CountDownLatch(1);
            doAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                waitingWithoutTransaction.countDown();
                return call.callRealMethod();
            }).when(delivery).awaitSignal(anyLong(), anyLong());
            ExecutorService waiter = Executors.newSingleThreadExecutor();
            try {
                JSONObject request = request(f); request.put("waitMillis", 2000);
                Future<JSONObject> waiting = waiter.submit(() -> leases.claim(request));
                // 到达第一次等待后创建正式任务；通知仅促发新的DB领取，不能附带任务内容。
                assertTrue(waitingWithoutTransaction.await(2, TimeUnit.SECONDS));
                JSONObject task = enqueue(f, "quality");
                delivery.receive(new DefaultMessage("aippt:isolated-test:tasks".getBytes(StandardCharsets.UTF_8),
                        com.alibaba.fastjson2.JSON.toJSONBytes(JSONObject.of("taskId", task.getString("id")))));
                JSONObject claimed = waiting.get(700, TimeUnit.MILLISECONDS);
                assertEquals(task.getString("id"), claimed.getString("taskId"));
                assertEquals(1, claimed.getIntValue("attempt"));
                assertEquals(1, claimed.getLongValue("fencingToken"));
                assertFalse(claimed.getString("leaseToken").isBlank());
                assertNull(leases.claim(request(f)));
                assertEquals(1, f.db.get("task", task.getString("id")).getIntValue("attempt"));
            } finally { waiter.shutdownNow(); }
        }
    }

    @Test void saturatedNotificationWaitersFallBackToBoundedSleepInsteadOfDispatchBusyLoop() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        f.props.setEnabled(true); f.props.setEnvironment("isolated-test");
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        when(container.isListening()).thenReturn(true);
        try (RedisDeliveryService delivery = new RedisDeliveryService(mock(RedisConnectionFactory.class), f.props,
                f.db, f.tx, container, Duration.ofMillis(80))) {
            Semaphore slots = (Semaphore) org.springframework.test.util.ReflectionTestUtils.getField(delivery, "waiters");
            assertNotNull(slots);
            slots.acquire(64);
            com.ruoyi.aippt.infrastructure.persistence.AipptRepository db = spy(f.db);
            TaskLeaseService leases = new TaskLeaseService(db, f.mapper, f.tx, f.service, f.props);
            leases.setDelivery(delivery);
            try {
                JSONObject request = request(f); request.put("waitMillis", 150);
                long started = System.nanoTime();
                assertNull(leases.claim(request));
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertTrue(elapsed >= 100 && elapsed < 1000);
                verify(db, atMost(2)).claimable(anySet());
            } finally { slots.release(64); }
        }
    }

    @Test void closedNotificationServiceUsesSameBoundedFallbackForAlreadyWaitingClaims() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        f.props.setEnabled(true); f.props.setEnvironment("isolated-test");
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        RedisDeliveryService delivery = new RedisDeliveryService(mock(RedisConnectionFactory.class), f.props,
                f.db, f.tx, container, Duration.ofMillis(80));
        delivery.close();
        com.ruoyi.aippt.infrastructure.persistence.AipptRepository db = spy(f.db);
        TaskLeaseService leases = new TaskLeaseService(db, f.mapper, f.tx, f.service, f.props);
        leases.setDelivery(delivery);
        JSONObject request = request(f); request.put("waitMillis", 150);
        long started = System.nanoTime();
        assertNull(leases.claim(request));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsed >= 100 && elapsed < 1000);
        verify(db, atMost(2)).claimable(anySet());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
}
