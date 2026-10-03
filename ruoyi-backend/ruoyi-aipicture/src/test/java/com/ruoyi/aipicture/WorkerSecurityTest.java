package com.ruoyi.aipicture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.persistence.*;
import com.ruoyi.aipicture.worker.*;

class WorkerSecurityTest {
    AiPictureProperties p;AiPictureRepository db;AiPictureWorkerAuthFilter filter;
    @BeforeEach void setup() {p=new AiPictureProperties();p.setServiceSecret("01234567890123456789012345678901");db=mock(AiPictureRepository.class);filter=new AiPictureWorkerAuthFilter(p,db);}
    MockHttpServletRequest request(String timestamp,String nonce,String signature,byte[] body) {
        var request=new MockHttpServletRequest("POST",AiPictureWorkerAuthFilter.PREFIX+"/claim");request.setContent(body);request.addHeader("X-Aipicture-Service","aipicture-worker");request.addHeader("X-Aipicture-Timestamp",timestamp);request.addHeader("X-Aipicture-Nonce",nonce);request.addHeader("X-Aipicture-Signature",signature);return request;
    }
    @Test void validSignatureReadsSameBodyAndOnlyWorkerRoleIsGranted() throws Exception {
        byte[] body="{\"worker_id\":\"test\"}".getBytes(StandardCharsets.UTF_8);String ts=String.valueOf(System.currentTimeMillis()/1000),nonce=UUID.randomUUID().toString();var response=new MockHttpServletResponse();
        filter.doFilter(request(ts,nonce,WorkerSignatures.sign(p.getServiceSecret(),"POST",AiPictureWorkerAuthFilter.PREFIX+"/claim",ts,nonce,body),body),response,(req,res)->{assertArrayEquals(body,req.getInputStream().readAllBytes());assertEquals("aipicture-worker",SecurityContextHolder.getContext().getAuthentication().getName());assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_AIPICTURE_WORKER")));});
        assertEquals(200,response.getStatus());assertNull(SecurityContextHolder.getContext().getAuthentication());verify(db).execute(startsWith("INSERT INTO aip_worker_nonce"),anyMap());
    }
    @Test void tamperOrTimestampSkewNeverReachController() throws Exception {
        String ts=String.valueOf(System.currentTimeMillis()/1000),nonce=UUID.randomUUID().toString();byte[] signed="{}".getBytes();byte[] tampered="{\"owner\":1}".getBytes();var response=new MockHttpServletResponse();filter.doFilter(request(ts,nonce,WorkerSignatures.sign(p.getServiceSecret(),"POST",AiPictureWorkerAuthFilter.PREFIX+"/claim",ts,nonce,signed),tampered),response,(req,res)->fail("不应进入Controller"));assertEquals(401,response.getStatus());
        ts=String.valueOf(System.currentTimeMillis()/1000-100);response=new MockHttpServletResponse();filter.doFilter(request(ts,nonce,WorkerSignatures.sign(p.getServiceSecret(),"POST",AiPictureWorkerAuthFilter.PREFIX+"/claim",ts,nonce,signed),signed),response,(req,res)->fail("不应进入Controller"));assertEquals(401,response.getStatus());verifyNoInteractions(db);
    }
    @Test void replayIsRejectedByDatabaseUniqueNonce() throws Exception {
        doThrow(new DuplicateKeyException("nonce")).when(db).execute(startsWith("INSERT INTO aip_worker_nonce"),anyMap());String ts=String.valueOf(System.currentTimeMillis()/1000),nonce=UUID.randomUUID().toString();byte[] body="{}".getBytes();var response=new MockHttpServletResponse();filter.doFilter(request(ts,nonce,WorkerSignatures.sign(p.getServiceSecret(),"POST",AiPictureWorkerAuthFilter.PREFIX+"/claim",ts,nonce,body),body),response,(req,res)->fail("不应进入Controller"));assertEquals(401,response.getStatus());
    }
}
