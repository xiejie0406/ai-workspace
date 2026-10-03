package com.ruoyi.paiflow.worker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.paiflow.worker.service.WorkerServiceIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerReceiveBoundaryFilterTest {
    private static final String ROUTE = "/internal/paiflow/v1/worker/execute";
    private final WorkerServiceIdentity identity = new WorkerServiceIdentity("fixture-receive-token");
    private final WorkerReceiveBoundaryFilter filter = new WorkerReceiveBoundaryFilter(identity, new ObjectMapper());
    private MockHttpServletRequest request(String path, byte[] bytes) {
        var request = new MockHttpServletRequest("POST", path);
        request.addHeader("Authorization", "Bearer fixture-receive-token");
        request.setContent(bytes); request.setCharacterEncoding("UTF-8"); return request;
    }
    @Test void unauthorizedBodyIsRejectedBeforeAnyReadOrMvcBinding() throws Exception {
        var request = spy(request(ROUTE, "{malformed".getBytes(StandardCharsets.UTF_8)));
        request.removeHeader("Authorization");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("不能进入MVC"));
        assertEquals(401, response.getStatus());
        verify(request, never()).getInputStream();
    }
    @Test void oversizedLengthIsRejectedBeforeBodyRead() throws Exception {
        var request = spy(request(ROUTE, new byte[WorkerReceiveBoundaryFilter.MAX_BODY_BYTES + 1]));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("不能进入MVC"));
        assertEquals(413, response.getStatus()); verify(request, never()).getInputStream();
    }
    @Test void unknownLengthStillHasTheSameActualByteBudget() throws Exception {
        var request = spy(request(ROUTE, new byte[WorkerReceiveBoundaryFilter.MAX_BODY_BYTES + 1]));
        doReturn(-1L).when(request).getContentLengthLong();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("不能进入MVC"));
        assertEquals(413, response.getStatus());
    }
    @Test void validLeaseBodyReachesExistingBindingIntact() throws Exception {
        byte[] bytes = "{\"runId\":\"run-1\",\"leaseId\":\"lease-1\",\"attempt\":1}".getBytes(StandardCharsets.UTF_8);
        var response = new MockHttpServletResponse();
        filter.doFilter(request(ROUTE, bytes), response, (req, res) -> {
            assertArrayEquals(bytes, req.getInputStream().readAllBytes());
            assertEquals(bytes.length, req.getContentLength());
            assertEquals(new String(bytes, StandardCharsets.UTF_8), ((HttpServletRequest) req).getReader().readLine());
        });
        assertEquals(200, response.getStatus());
    }
    @Test void otherRoutesRemainUntouchedAndEncodedWorkerRouteCannotBypassAuthentication() throws Exception {
        var request = request("/actuator/health", new byte[0]); request.removeHeader("Authorization");
        var reached = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> reached.set(true));
        assertTrue(reached.get());
        for (String path : new String[]{ROUTE.replace("internal", "%69nternal"), "/app" + ROUTE, ROUTE.replace("worker/", "worker;path=1/")}) {
            var protectedRequest = request(path, new byte[0]); protectedRequest.removeHeader("Authorization");
            if (path.startsWith("/app/")) protectedRequest.setContextPath("/app");
            var response = new MockHttpServletResponse();
            filter.doFilter(protectedRequest, response, (req, res) -> fail("不得绕过身份检查"));
            assertEquals(401, response.getStatus());
        }
    }
}
