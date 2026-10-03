package com.ruoyi.aippt.security;
import com.ruoyi.aippt.configuration.AipptProperties;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
class AipptWorkerAuthenticationTest  {
    @AfterEach void clear()  {
        SecurityContextHolder.clearContext();
    }
    @Test void userJwtCannotBeReusedAsAWorkerCredential()throws Exception  {
        AipptProperties props=new AipptProperties();
        props.setEnabled(true);
        props.getWorker().setEnabled(true);
        props.getWorker().setKey("a".repeat(32));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user",null,"ROLE_ADMIN"));
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/internal/aippt/tasks/claim");
        request.addHeader("Authorization","Bearer user-token");
        new AipptWorkerAuthenticationFilter(props).doFilter(request,new MockHttpServletResponse(),(req,res)->assertNull(SecurityContextHolder.getContext().getAuthentication()));
    }
    @Test void validWorkerKeyCreatesOnlyMachinePrincipal()throws Exception  {
        AipptProperties props=new AipptProperties();
        props.setEnabled(true);
        props.getWorker().setEnabled(true);
        props.getWorker().setKey("a".repeat(32));
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/internal/aippt/tasks/claim");
        request.addHeader("X-Aippt-Worker-Key","a".repeat(32));
        new AipptWorkerAuthenticationFilter(props).doFilter(request,new MockHttpServletResponse(),(req,res)->assertInstanceOf(AipptWorkerAuthenticationFilter.WorkerIdentity.class,SecurityContextHolder.getContext().getAuthentication().getPrincipal()));
    }
    @Test void disabledModuleAndShortSecretsNeverAuthenticate()throws Exception  {
        AipptProperties props=new AipptProperties();
        props.getWorker().setEnabled(true);
        props.getWorker().setKey("a".repeat(32));
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/internal/aippt/tasks/claim");
        request.addHeader("X-Aippt-Worker-Key","a".repeat(32));
        new AipptWorkerAuthenticationFilter(props).doFilter(request,new MockHttpServletResponse(),(req,res)->assertNull(SecurityContextHolder.getContext().getAuthentication()));
        assertFalse(AipptWorkerAuthenticationFilter.keyMatches("short","short"));
        assertFalse(AipptWorkerAuthenticationFilter.keyMatches("a".repeat(32),"b".repeat(32)));
    }
}
