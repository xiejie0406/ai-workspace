package com.ruoyi.paiflow.worker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.paiflow.worker.service.WorkerException;
import com.ruoyi.paiflow.worker.service.WorkerServiceIdentity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 独立Worker的受限租约接收路由在MVC绑定前认证/限字节；不改变其余路径。 */
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public final class WorkerReceiveBoundaryFilter extends OncePerRequestFilter {
    static final int MAX_BODY_BYTES = 16 * 1024;
    private static final String ROUTE = "/internal/paiflow/v1/worker/execute";
    private static final UrlPathHelper PATHS = new UrlPathHelper();
    private final WorkerServiceIdentity identity;
    private final ObjectMapper mapper;
    public WorkerReceiveBoundaryFilter(WorkerServiceIdentity identity, ObjectMapper mapper) {
        this.identity = identity; this.mapper = mapper;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ROUTE.equals(PATHS.getPathWithinApplication(request));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try { identity.require(request.getHeader("Authorization")); }
        catch (WorkerException denied) { reject(response, 401, denied.getCode(), denied.getMessage()); return; }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) { reject(response, 413, "REQUEST_TOO_LARGE", "租约接收正文超出允许大小"); return; }
        var bytes = new ByteArrayOutputStream(1024);
        var input = request.getInputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer, 0, Math.min(buffer.length, MAX_BODY_BYTES - bytes.size() + 1))) != -1) {
            if (count == 0) continue;
            if (bytes.size() + count > MAX_BODY_BYTES) { reject(response, 413, "REQUEST_TOO_LARGE", "租约接收正文超出允许大小"); return; }
            bytes.write(buffer, 0, count);
        }
        chain.doFilter(new BoundedRequest(request, bytes.toByteArray()), response);
    }
    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status); response.setCharacterEncoding(StandardCharsets.UTF_8.name()); response.setContentType("application/json");
        response.getWriter().write(mapper.writeValueAsString(Map.of("code", code, "message", message)));
    }
    private static final class BoundedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BoundedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] bytes, int offset, int count) { return input.read(bytes, offset, count); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    try { if (!isFinished()) listener.onDataAvailable(); if (isFinished()) listener.onAllDataRead(); }
                    catch (IOException error) { listener.onError(error); }
                }
            };
        }
        @Override public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(), encoding == null ? StandardCharsets.UTF_8.name() : encoding));
        }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
    }
}
