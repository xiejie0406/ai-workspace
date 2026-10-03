package com.ruoyi.aipicture.worker;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.persistence.*;

/** 精确内部执行链的 HMAC 身份；不接受用户 JWT，不修改公开若依链。 */
public final class AiPictureWorkerAuthFilter extends OncePerRequestFilter {
    public static final String PREFIX="/aipicture/internal/v1/execution";
    private final AiPictureProperties p;private final AiPictureRepository db;
    public AiPictureWorkerAuthFilter(AiPictureProperties p,AiPictureRepository db) {this.p=p;this.db=db;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {return !request.getRequestURI().startsWith(PREFIX+"/");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        try {
            String service=request.getHeader("X-Aipicture-Service"),timestamp=request.getHeader("X-Aipicture-Timestamp"),nonce=request.getHeader("X-Aipicture-Nonce"),signature=request.getHeader("X-Aipicture-Signature");
            if(p.getServiceSecret().length()<32||!p.getWorkerServiceId().equals(service)||timestamp==null||nonce==null||signature==null||!nonce.matches("[a-zA-Z0-9_-]{16,80}")) throw new SecurityException();
            long seconds=Long.parseLong(timestamp),now=System.currentTimeMillis()/1000;
            if(Math.abs(now-seconds)>90||request.getContentLengthLong()>280L*1024*1024) throw new SecurityException();
            byte[] body=request.getInputStream().readNBytes(280*1024*1024+1);if(body.length>280L*1024*1024) throw new SecurityException();
            String expected=WorkerSignatures.sign(p.getServiceSecret(),request.getMethod(),request.getRequestURI(),timestamp,nonce,body);
            if(!WorkerSignatures.equals(expected,signature)) throw new SecurityException();
            try {db.execute("INSERT INTO aip_worker_nonce(service_id,nonce,created_at) VALUES(:service,:nonce,:now)",Json.of("service",service,"nonce",nonce,"now",System.currentTimeMillis()));}
            catch(DuplicateKeyException e) {throw new SecurityException();}
            db.execute("DELETE FROM aip_worker_nonce WHERE created_at<:before",Json.of("before",System.currentTimeMillis()-180000));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(service,null,List.of(new SimpleGrantedAuthority("ROLE_AIPICTURE_WORKER"))));
            try {chain.doFilter(new BodyRequest(request,body),response);} finally {SecurityContextHolder.clearContext();}
        } catch(SecurityException|NumberFormatException e) {
            response.setStatus(401);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":401,\"errorCode\":\"WORKER_AUTH_FAILED\",\"msg\":\"Worker 服务身份无效、过期或请求重放\"}");
        }
    }
    private static class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BodyRequest(HttpServletRequest request,byte[] body) {super(request);this.body=body;}
        @Override public ServletInputStream getInputStream() {
            var input=new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() {return input.available()==0;}
                @Override public boolean isReady() {return true;}
                @Override public void setReadListener(ReadListener listener) { }
                @Override public int read() {return input.read();}
                @Override public int read(byte[] b,int off,int len) {return input.read(b,off,len);}
            };
        }
        @Override public BufferedReader getReader() {return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
    }
}
