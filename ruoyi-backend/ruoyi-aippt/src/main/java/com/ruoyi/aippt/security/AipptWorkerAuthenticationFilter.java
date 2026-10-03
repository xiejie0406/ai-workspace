package com.ruoyi.aippt.security;
import com.ruoyi.aippt.configuration.AipptProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
/** 仅由 internal 安全链引用；不是容器 Filter Bean，不接受用户 JWT。 */
public class AipptWorkerAuthenticationFilter extends OncePerRequestFilter  {
    public record WorkerIdentity(String name)  {
    }
    private final AipptProperties props;
    public AipptWorkerAuthenticationFilter(AipptProperties p)  {
        props=p;
    }
    public static boolean keyMatches(String expected,String actual)  {
        return expected!=null&&expected.length()>=32&&actual!=null&&MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8));
    }
    protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws IOException,ServletException  {
        SecurityContextHolder.clearContext();
        if(props.isEnabled()&&props.getWorker().isEnabled()&&keyMatches(props.getWorker().getKey(),req.getHeader("X-Aippt-Worker-Key")))  {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new WorkerIdentity("aippt-worker"),null,List.of(new SimpleGrantedAuthority("AIPPT_WORKER"))));
        }
        chain.doFilter(req,res);
    }
}
