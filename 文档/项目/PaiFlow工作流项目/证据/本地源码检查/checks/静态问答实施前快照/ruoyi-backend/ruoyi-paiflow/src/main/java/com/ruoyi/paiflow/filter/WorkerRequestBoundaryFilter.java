package com.ruoyi.paiflow.filter;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.RunProtocolLimits;
import com.ruoyi.paiflow.service.WorkerIdentityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/** 只限内部运行入口，在MVC解析JSON前认证并限制实际读取字节；不改变共享用户认证。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+30)
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class WorkerRequestBoundaryFilter extends OncePerRequestFilter {
    private static final String PREFIX="/internal/paiflow/v1/runs";
    private static final UrlPathHelper URL_PATHS=new UrlPathHelper();
    private final WorkerIdentityService identities;
    public WorkerRequestBoundaryFilter(WorkerIdentityService identities) { this.identities=identities; }
    private static String path(HttpServletRequest request) {
        return URL_PATHS.getPathWithinApplication(request);
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String value=path(request);return !value.equals(PREFIX) && !value.startsWith(PREFIX+"/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
        throws ServletException,IOException {
        try { identities.authenticate(request.getHeader("Authorization")); }
        catch(ServiceException denied) { reject(response,401,denied.getMessage());return; }
        String route=path(request);
        int limit=route.endsWith("/events")?RunProtocolLimits.EVENT_BYTES:
            route.endsWith("/operations")?com.ruoyi.paiflow.service.ModelOperationJson.REQUEST_BYTES:
            route.endsWith("/result") || route.endsWith("/checkpoints")?RunProtocolLimits.RESULT_BYTES:RunProtocolLimits.CONTROL_BYTES;
        if(request.getContentLengthLong()>limit) { reject(response,413,"Worker请求正文超出允许大小");return; }
        // Content-Length不能约束分块或未知长度的请求，始终按实际字节最多读取limit+1。
        var output=new ByteArrayOutputStream(Math.min(limit,8192));var input=request.getInputStream();var buffer=new byte[8192];int count;
        while((count=input.read(buffer,0,Math.min(buffer.length,limit-output.size()+1)))!=-1) {
            if(count==0)continue;
            if(output.size()+count>limit) { reject(response,413,"Worker请求正文超出允许大小");return; }
            output.write(buffer,0,count);
        }
        chain.doFilter(new BoundedRequest(request,output.toByteArray()),response);
    }
    private static void reject(HttpServletResponse response,int status,String message) throws IOException {
        response.setStatus(status);response.setCharacterEncoding(StandardCharsets.UTF_8.name());response.setContentType("application/json");
        response.getWriter().write(JSON.toJSONString(AjaxResult.error(status,message)));
    }
    private static final class BoundedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BoundedRequest(HttpServletRequest request,byte[] body) { super(request);this.body=body; }
        @Override public ServletInputStream getInputStream() {
            var input=new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] value,int offset,int length) { return input.read(value,offset,length); }
                @Override public boolean isFinished() { return input.available()==0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    try { if(!isFinished())listener.onDataAvailable();if(isFinished())listener.onAllDataRead(); }
                    catch(IOException ex) { listener.onError(ex); }
                }
            };
        }
        @Override public BufferedReader getReader() throws IOException {
            String encoding=getCharacterEncoding();return new BufferedReader(new InputStreamReader(getInputStream(),encoding==null?StandardCharsets.UTF_8.name():encoding));
        }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
    }
}
