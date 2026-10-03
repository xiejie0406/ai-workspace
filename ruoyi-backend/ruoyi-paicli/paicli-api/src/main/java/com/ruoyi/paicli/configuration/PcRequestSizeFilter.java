package com.ruoyi.paicli.configuration;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** 在JSON解析之前限制Content-Length及chunked读取，不让协议上限仅依赖序列化或代理。 */
public class PcRequestSizeFilter extends OncePerRequestFilter {
    public static final long MAX_BYTES=64L*1024*1024;
    private final long maximum;
    public PcRequestSizeFilter(){this(MAX_BYTES);}
    PcRequestSizeFilter(long limit){maximum=limit;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        if(request.getContentLengthLong()>maximum){reject(response);return;}
        HttpServletRequest wrapped=new HttpServletRequestWrapper(request){
            private ServletInputStream stream;
            @Override public java.io.BufferedReader getReader()throws IOException{return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),getCharacterEncoding()==null?java.nio.charset.StandardCharsets.UTF_8:java.nio.charset.Charset.forName(getCharacterEncoding())));}
            @Override public ServletInputStream getInputStream()throws IOException{if(stream==null){ServletInputStream original=super.getInputStream();stream=new ServletInputStream(){long consumed;
                private void count(int bytes)throws IOException{if(bytes>0&&(consumed+=bytes)>maximum)throw new LimitExceeded();}
                @Override public int read()throws IOException{int result=original.read();count(result<0?0:1);return result;}
                @Override public int read(byte[] bytes,int offset,int length)throws IOException{int result=original.read(bytes,offset,length);count(result);return result;}
                @Override public boolean isFinished(){return original.isFinished();}
                @Override public boolean isReady(){return original.isReady();}
                @Override public void setReadListener(ReadListener listener){original.setReadListener(listener);}
            };}return stream;}
        };
        try{chain.doFilter(wrapped,response);}catch(LimitExceeded e){reject(response);}catch(ServletException e){Throwable cause=e;while(cause!=null&&!(cause instanceof LimitExceeded))cause=cause.getCause();if(cause instanceof LimitExceeded)reject(response);else throw e;}
    }
    private void reject(HttpServletResponse response)throws IOException{if(response.isCommitted())return;response.resetBuffer();response.setStatus(413);response.setCharacterEncoding("UTF-8");response.setContentType("application/json");response.getWriter().write("{\"code\":413,\"msg\":\"PaiCLI请求超过64MB协议上限\"}");}
    private static final class LimitExceeded extends IOException {}
}
