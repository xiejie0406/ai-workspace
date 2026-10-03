package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.aippt.domain.AipptRules;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 捕获真实SSE帧构造，FakeRedis仅唤醒，所有业务帧仍取H2/MyBatis持久事件。 */
class AipptEventDeliveryTest {
    private class Stream implements AutoCloseable {
        final AipptPersistenceFixture f = new AipptPersistenceFixture();
        final JSONObject task = f.tx.execute(status -> f.service.enqueue(f.project(), "quality", new JSONObject(), new JSONObject()));
        final EventService events = spy(new EventService(f.service, f.db));
        final SseEmitter emitter = mock(SseEmitter.class);
        final RedisDeliveryService delivery = mock(RedisDeliveryService.class);
        final AtomicReference<JSONObject> deliveryState = new AtomicReference<>(JSONObject.of("status", "connected", "reason", "REDIS_CONNECTED"));
        final AtomicReference<Runnable> wake = new AtomicReference<>();
        final AtomicReference<Runnable> completion = new AtomicReference<>();
        final List<JSONObject> frames = new CopyOnWriteArrayList<>();
        final CountDownLatch deliveryFrame = new CountDownLatch(1), progressFrame = new CountDownLatch(1);

        Stream() throws Exception {
            doReturn(emitter).when(events).newEmitter(anyLong());
            when(delivery.status()).thenAnswer(call -> AipptRules.copy(deliveryState.get()));
            when(delivery.listen(eq(f.project), any())).thenAnswer(call -> {
                wake.set(call.getArgument(1));
                return (AutoCloseable) () -> wake.set(null);
            });
            doAnswer(call -> { completion.set(call.getArgument(0)); return null; }).when(emitter).onCompletion(any());
            doAnswer(call -> {
                SseEmitter.SseEventBuilder builder = call.getArgument(0);
                for (var part : builder.build()) if (part.getData() instanceof JSONObject frame) {
                    JSONObject saved = AipptRules.copy(frame);
                    frames.add(saved);
                    if ("delivery".equals(saved.getString("type"))) deliveryFrame.countDown();
                    if ("progress".equals(saved.getString("type"))) progressFrame.countDown();
                }
                return null;
            }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
            events.setDelivery(delivery);
        }

        void subscribe() { events.subscribe(f.project, task.getString("id"), false, f.owner, 0, () -> true); }
        void progress() {
            f.tx.execute(status -> {
                JSONObject current = f.db.lock("task", task.getString("id"));
                current.put("status", "running");
                current.put("progress", JSONObject.of("percent", 30, "message", "独立持久进度"));
                f.db.save("task", current, current.getLongValue("revision"));
                f.service.event(f.project, f.owner, "progress", JSONObject.of("task_id", current.getString("id")));
                return null;
            });
        }
        @Override public void close() {
            if (completion.get() != null) completion.get().run();
            events.stop();
        }
    }

    @Test void redisNotificationAcceleratesReadOfAuthoritativePersistedEvent() throws Exception {
        try (Stream stream = new Stream()) {
            stream.subscribe();
            assertEquals("snapshot", stream.frames.get(0).getString("type"));
            stream.progress();
            stream.wake.get().run();
            assertTrue(stream.progressFrame.await(900, TimeUnit.MILLISECONDS));
            JSONObject frame = stream.frames.stream().filter(value -> "progress".equals(value.getString("type"))).findFirst().orElseThrow();
            System.out.println("AIPPT_CAPTURED_SSE " + frame.toJSONString());
            assertEquals(2, frame.getLongValue("eventSeq"));
            assertEquals("running", frame.getString("state"));
            assertEquals(30, frame.getJSONObject("data").getJSONObject("progress").getIntValue("percent"));
            assertEquals(JSONObject.of("status", "connected", "reason", "REDIS_CONNECTED"), frame.getJSONObject("delivery"));
            assertFalse(frame.toJSONString().contains("lease_token") || frame.toJSONString().contains("owner_user_id"));
        }
    }

    @Test void deliveryChangeDoesNotAdvanceBusinessSequenceOrReplaceTaskState() throws Exception {
        try (Stream stream = new Stream()) {
            stream.subscribe();
            stream.deliveryState.set(JSONObject.of("status", "degraded", "reason", "REDIS_UNAVAILABLE"));
            stream.wake.get().run();
            assertTrue(stream.deliveryFrame.await(900, TimeUnit.MILLISECONDS));
            JSONObject frame = stream.frames.stream().filter(value -> "delivery".equals(value.getString("type"))).findFirst().orElseThrow();
            System.out.println("AIPPT_CAPTURED_SSE " + frame.toJSONString());
            assertEquals(1, frame.getLongValue("eventSeq"));
            assertFalse(frame.containsKey("state"));
            assertTrue(frame.getJSONObject("data").isEmpty());
            assertEquals(JSONObject.of("status", "degraded", "reason", "REDIS_UNAVAILABLE"), frame.getJSONObject("delivery"));
            assertEquals(1, stream.f.db.lastSequence(stream.f.project));
            assertEquals("queued", stream.f.db.get("task", stream.task.getString("id")).getString("status"));
        }
    }

    @Test void disconnectedRedisStillReceivesDatabaseProgressWithinExistingPollingFallback() throws Exception {
        try (Stream stream = new Stream()) {
            stream.deliveryState.set(JSONObject.of("status", "degraded", "reason", "REDIS_UNAVAILABLE"));
            stream.subscribe();
            stream.progress(); // 故意不触发Redis通知。
            assertTrue(stream.progressFrame.await(2, TimeUnit.SECONDS));
            JSONObject frame = stream.frames.stream().filter(value -> "progress".equals(value.getString("type"))).findFirst().orElseThrow();
            System.out.println("AIPPT_CAPTURED_SSE " + frame.toJSONString());
            assertEquals("running", frame.getString("state"));
            assertEquals(2, frame.getLongValue("eventSeq"));
            assertEquals("degraded", frame.getJSONObject("delivery").getString("status"));
        }
    }

    @Test void failedNotificationRegistrationCompletesEmitterAndReleasesConnectionCapacity() throws Exception {
        try (Stream stream = new Stream()) {
            IllegalStateException unavailable = new IllegalStateException("isolated registration failure");
            when(stream.delivery.listen(eq(stream.f.project), any())).thenThrow(unavailable);
            assertDoesNotThrow(stream::subscribe);
            verify(stream.emitter).completeWithError(unavailable);
            var count = (java.util.concurrent.atomic.AtomicInteger) org.springframework.test.util.ReflectionTestUtils.getField(stream.events, "connections");
            assertNotNull(count);
            assertEquals(0, count.get());
        }
    }
}
