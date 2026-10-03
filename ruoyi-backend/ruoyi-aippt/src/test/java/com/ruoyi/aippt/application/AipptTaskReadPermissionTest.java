package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.aippt.configuration.AipptProperties;
import com.ruoyi.aippt.controller.AipptController;
import com.ruoyi.aippt.controller.EventController;
import com.ruoyi.aippt.domain.AipptException;
import com.ruoyi.aippt.security.AipptWorkerAuthenticationFilter;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.framework.web.service.TokenService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际平台 LoginUser/SecurityUtils 与 H2 task/owner 读取；不启动 Web 或外部认证。 */
class AipptTaskReadPermissionTest {
    private AnnotationConfigApplicationContext context;
    @Configuration @EnableMethodSecurity(proxyTargetClass = true)
    static class MethodSecurity { }

    @AfterEach void clearAuthentication() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        if (context != null) context.close();
    }

    @Test void eachTaskRequiresItsOwnBusinessReadPermission() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        AipptController controller = controller(f);
        Map<String, String> permissions = Map.of("parse_source", "aippt:source:query", "generate_outline", "aippt:outline:query",
                "generate_slide", "aippt:deck:query", "ai_edit", "aippt:ai:edit", "relayout", "aippt:ai:edit",
                "quality", "aippt:export:check", "export", "aippt:export:download");
        for (var entry : permissions.entrySet()) {
            JSONObject task = task(f, entry.getKey(), f.project);
            login(f.owner, Set.of(entry.getValue()));
            JSONObject read = (JSONObject) controller.task(task.getString("id")).get("data");
            assertEquals(task.getString("id"), read.getString("id"));
            assertFalse(read.containsKey("payload"));
            assertFalse(read.containsKey("lease_token"));
            assertFalse(read.containsKey("owner_user_id"));
            login(f.owner, Set.of("aippt:design:query"));
            AipptException denied = assertThrows(AipptException.class, () -> controller.task(task.getString("id")), entry.getKey());
            assertEquals(403, denied.status());
            login(f.owner, Set.of("*:*:*"));
            assertDoesNotThrow(() -> controller.task(task.getString("id")));
        }
    }

    @Test void onlyTheOwnedPrivateSampleQualityAcceptsDesignPermission() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        JSONObject accepted = f.service.sampleIssues(f.owner, "ivory");
        AipptController controller = controller(f);
        login(f.owner, Set.of("aippt:design:query"));
        assertDoesNotThrow(() -> controller.task(accepted.getString("jobId")));
        JSONObject ordinary = task(f, "quality", f.project);
        assertEquals(403, assertThrows(AipptException.class, () -> controller.task(ordinary.getString("id"))).status());
        login(f.owner, Set.of("aippt:export:check"));
        assertEquals(403, assertThrows(AipptException.class, () -> controller.task(accepted.getString("jobId"))).status());
        login(43, Set.of("*:*:*"));
        assertEquals(404, assertThrows(AipptException.class, () -> controller.task(accepted.getString("jobId"))).status());
    }

    @Test void unknownTypesAndMachineIdentityCannotReadPublicTasks() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        AipptController controller = controller(f);
        JSONObject unknown = task(f, "unknown", f.project);
        login(f.owner, Set.of("*:*:*"));
        assertEquals(403, assertThrows(AipptException.class, () -> controller.task(unknown.getString("id"))).status());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AipptWorkerAuthenticationFilter.WorkerIdentity("isolated-worker"), null, List.of()));
        assertThrows(ServiceException.class, () -> controller.task(unknown.getString("id")));
    }

    @Test void taskSseUsesTheSameTypeGateBeforeRegisteringAStream() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        EventService events = mock(EventService.class);
        TokenService tokens = mock(TokenService.class);
        controller(f);
        context.registerBean(EventController.class, () -> new EventController(events, f.service, tokens));
        EventController stream = context.getBean(EventController.class);
        JSONObject parse = task(f, "parse_source", f.project);
        login(f.owner, Set.of("aippt:source:query"));
        assertDoesNotThrow(() -> stream.task(parse.getString("id"), 0, null, new MockHttpServletRequest()));
        verify(events).subscribe(eq(f.project), eq(parse.getString("id")), eq(false), eq(f.owner), eq(0L), any());
        clearInvocations(events);
        login(f.owner, Set.of("aippt:design:query"));
        assertEquals(403, assertThrows(AipptException.class, () -> stream.task(parse.getString("id"), 0, null, new MockHttpServletRequest())).status());
        login(43, Set.of("*:*:*"));
        assertEquals(404, assertThrows(AipptException.class, () -> stream.task(parse.getString("id"), 0, null, new MockHttpServletRequest())).status());
        verifyNoInteractions(events);
    }

    @Test void platformOuterGateRejectsAUserWithNoTaskReadPermission() throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        AipptController controller = controller(f);
        JSONObject parse = task(f, "parse_source", f.project);
        login(f.owner, Set.of("aippt:source:add"));
        assertThrows(AccessDeniedException.class, () -> controller.task(parse.getString("id")));
    }

    @Test void existingTaskStreamStopsBeforeSendingAfterPermissionRevocation() throws Exception {
        assertStreamStopsAfterSessionChange(false, "task");
    }

    @Test void existingTaskStreamStopsBeforeSendingAfterLoginOwnerChanges() throws Exception {
        assertStreamStopsAfterSessionChange(true, "task");
    }

    @Test void existingDeckStreamStopsAfterPermissionRevocation() throws Exception { assertStreamStopsAfterSessionChange(false, "deck"); }
    @Test void existingDeckStreamStopsAfterLoginOwnerChanges() throws Exception { assertStreamStopsAfterSessionChange(true, "deck"); }
    @Test void existingOutlineStreamStopsAfterPermissionRevocation() throws Exception { assertStreamStopsAfterSessionChange(false, "outline"); }
    @Test void existingOutlineStreamStopsAfterLoginOwnerChanges() throws Exception { assertStreamStopsAfterSessionChange(true, "outline"); }

    private void assertStreamStopsAfterSessionChange(boolean changeOwner, String kind) throws Exception {
        AipptPersistenceFixture f = new AipptPersistenceFixture();
        String type = switch (kind) { case "deck" -> "generate_slide"; case "outline" -> "generate_outline"; default -> "quality"; };
        String permission = switch (kind) { case "deck" -> "aippt:deck:query"; case "outline" -> "aippt:outline:query"; default -> "aippt:export:check"; };
        if ("outline".equals(kind)) f.outline("generating");
        JSONObject task = task(f, type, f.project);
        task.put("status", "running");
        f.db.save("task", task, task.getLongValue("revision"));
        EventService events = spy(new EventService(f.service, f.db));
        SseEmitter emitter = mock(SseEmitter.class);
        RedisDeliveryService delivery = mock(RedisDeliveryService.class);
        TokenService tokens = mock(TokenService.class);
        AtomicReference<LoginUser> session = new AtomicReference<>(new LoginUser(f.owner, 1L, new SysUser(), Set.of(permission)));
        AtomicReference<Runnable> wake = new AtomicReference<>();
        java.util.List<JSONObject> frames = new CopyOnWriteArrayList<>();
        CountDownLatch closed = new CountDownLatch(1);
        doReturn(emitter).when(events).newEmitter(anyLong());
        when(tokens.getLoginUser(any())).thenAnswer(call -> session.get());
        when(delivery.status()).thenReturn(JSONObject.of("status", "connected", "reason", "REDIS_CONNECTED"));
        when(delivery.listen(eq(f.project), any())).thenAnswer(call -> {
            wake.set(call.getArgument(1));
            return (AutoCloseable) () -> wake.set(null);
        });
        doAnswer(call -> {
            SseEmitter.SseEventBuilder builder = call.getArgument(0);
            for (var part : builder.build()) if (part.getData() instanceof JSONObject frame) frames.add(com.ruoyi.aippt.domain.AipptRules.copy(frame));
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        doAnswer(call -> { closed.countDown(); return null; }).when(emitter).completeWithError(any());
        events.setDelivery(delivery);
        try {
            controller(f);
            context.registerBean(EventController.class, () -> new EventController(events, f.service, tokens));
            EventController endpoint = context.getBean(EventController.class);
            login(f.owner, Set.of(permission));
            if ("deck".equals(kind)) endpoint.deck(f.project, 0, null, new MockHttpServletRequest());
            else if ("outline".equals(kind)) endpoint.outline(f.project, 0, null, new MockHttpServletRequest());
            else endpoint.task(task.getString("id"), 0, null, new MockHttpServletRequest());
            assertEquals(1, frames.size());
            assertEquals("snapshot", frames.get(0).getString("type"));
            session.set(new LoginUser(changeOwner ? 43L : f.owner, 1L, new SysUser(), Set.of(changeOwner ? permission : "aippt:design:query")));
            f.tx.execute(status -> {
                JSONObject stored = f.db.lock("task", task.getString("id"));
                stored.put("output", JSONObject.of("should_not_be_delivered", true));
                f.db.save("task", stored, stored.getLongValue("revision"));
                f.service.event(f.project, f.owner, "progress", JSONObject.of("task_id", task.getString("id")));
                return null;
            });
            Runnable notification = wake.get();
            assertNotNull(notification);
            notification.run();
            assertTrue(closed.await(2, TimeUnit.SECONDS), "有效 token 撤权或切换 owner 后必须结束既有流");
            assertEquals(1, frames.size(), "撤权后的持久事件与 output 不能发送给旧连接");
            assertFalse(frames.toString().contains("should_not_be_delivered"));
            var count = (java.util.concurrent.atomic.AtomicInteger) org.springframework.test.util.ReflectionTestUtils.getField(events, "connections");
            assertEquals(0, count.get());
        } finally { events.stop(); }
    }

    private static JSONObject task(AipptPersistenceFixture f, String type, String project) {
        JSONObject task = f.service.record(project, f.owner, "succeeded");
        task.putAll(JSONObject.of("type", type, "payload", JSONObject.of("private", "not-for-user"), "lease_token", "private-lease"));
        f.db.insert("task", task);
        return task;
    }

    private AipptController controller(AipptPersistenceFixture f) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "isolated-aippt-method-security", Map.of("aippt.enabled", "true")));
        context.register(MethodSecurity.class);
        context.registerBean("ss", PermissionService.class, PermissionService::new);
        context.registerBean(AipptController.class, () -> new AipptController(f.service, null, f.catalog, f.db, new AipptProperties(), null));
        context.refresh();
        return context.getBean(AipptController.class);
    }

    private static void login(long owner, Set<String> permissions) {
        LoginUser user = new LoginUser(owner, 1L, new SysUser(), permissions);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
