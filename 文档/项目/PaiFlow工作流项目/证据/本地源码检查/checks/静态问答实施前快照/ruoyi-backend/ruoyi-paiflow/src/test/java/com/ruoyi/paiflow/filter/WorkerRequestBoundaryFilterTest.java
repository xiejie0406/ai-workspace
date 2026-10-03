package com.ruoyi.paiflow.filter;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.RunProtocolLimits;
import com.ruoyi.paiflow.service.WorkerIdentityService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerRequestBoundaryFilterTest {
    private static final String ROUTE="/internal/paiflow/v1/runs/00000000-0000-0000-0000-000000000001/events";
    private WorkerIdentityService identity() {
        var identity=mock(WorkerIdentityService.class);
        when(identity.authenticate("Bearer fixture")).thenReturn(new WorkerIdentityService.Principal("worker-a","java"));return identity;
    }
    private MockHttpServletRequest request(String route,byte[] bytes) {
        var request=new MockHttpServletRequest("POST",route);request.addHeader("Authorization","Bearer fixture");
        request.setContentType("application/json");request.setCharacterEncoding("UTF-8");request.setContent(bytes);return request;
    }
    @Test void rejectedIdentityDoesNotReadOrParseMalformedBody() throws Exception {
        var identity=identity();when(identity.authenticate("Bearer fixture")).thenThrow(new ServiceException("拒绝",401));
        var request=spy(request(ROUTE,"{malformed".getBytes(StandardCharsets.UTF_8)));var response=new MockHttpServletResponse();var reached=new AtomicBoolean();
        new WorkerRequestBoundaryFilter(identity).doFilter(request,response,(req,res)->reached.set(true));
        assertEquals(401,response.getStatus());assertFalse(reached.get());verify(request,never()).getInputStream();
    }
    @Test void overBudgetContentLengthIsRejectedBeforeReading() throws Exception {
        var request=spy(request(ROUTE,new byte[RunProtocolLimits.EVENT_BYTES+1]));var response=new MockHttpServletResponse();
        new WorkerRequestBoundaryFilter(identity()).doFilter(request,response,(req,res)->fail("不应进入MVC"));
        assertEquals(413,response.getStatus());verify(request,never()).getInputStream();
    }
    @Test void unknownLengthCannotBypassActualByteLimit() throws Exception {
        var request=spy(request(ROUTE,new byte[RunProtocolLimits.EVENT_BYTES+1]));doReturn(-1L).when(request).getContentLengthLong();
        var response=new MockHttpServletResponse();new WorkerRequestBoundaryFilter(identity()).doFilter(request,response,(req,res)->fail("不应进入MVC"));
        assertEquals(413,response.getStatus());
    }
    @Test void acceptedBodyIsPassedIntactToExistingMvcBinding() throws Exception {
        byte[] bytes="{\"payload\":{\"text\":\"中文原帧\"}}".getBytes(StandardCharsets.UTF_8);var request=request(ROUTE,bytes);var response=new MockHttpServletResponse();
        new WorkerRequestBoundaryFilter(identity()).doFilter(request,response,(req,res)-> {
            assertArrayEquals(bytes,req.getInputStream().readAllBytes());assertEquals(bytes.length,req.getContentLength());
            assertEquals(new String(bytes,StandardCharsets.UTF_8),((HttpServletRequest)req).getReader().readLine());
        });assertEquals(200,response.getStatus());
    }
    @Test void platformAndSimilarlyNamedRoutesRemainUnderTheirExistingFilters() throws Exception {
        var identity=identity();var filter=new WorkerRequestBoundaryFilter(identity);var reached=new AtomicBoolean();
        for(String path:new String[]{"/paiflow/runs","/internal/paiflow/v1/runs-other/events"}) {
            filter.doFilter(request(path,new byte[0]),new MockHttpServletResponse(),(req,res)->reached.set(true));assertTrue(reached.get());
        }
        verifyNoInteractions(identity);
    }
    @Test void contextEncodedSegmentsAndPathParametersStillUseWorkerBoundary() throws Exception {
        for(String path:new String[]{ROUTE,"/app"+ROUTE,ROUTE.replace("/runs/","/runs;path=1/"),ROUTE.replace("internal","%69nternal")}) {
            var identity=identity();var request=request(path,new byte[0]);if(path.startsWith("/app/"))request.setContextPath("/app");
            new WorkerRequestBoundaryFilter(identity).doFilter(request,new MockHttpServletResponse(),(req,res)-> {});
            verify(identity).authenticate("Bearer fixture");
        }
    }
    @Test void checkpointCreationUsesQuestionBudgetRatherThanSmallControlBudget() throws Exception {
        byte[] bytes=("{\"question\":{\"type\":\"direct\",\"content\":\""+"x".repeat(300*1024)+"\"}}").getBytes(StandardCharsets.UTF_8);
        var response=new MockHttpServletResponse();new WorkerRequestBoundaryFilter(identity()).doFilter(request(ROUTE.substring(0,ROUTE.length()-"events".length())+"checkpoints",bytes),response,(req,res)->assertArrayEquals(bytes,req.getInputStream().readAllBytes()));
        assertEquals(200,response.getStatus());
    }
    @Test void modelStartAcceptsOriginalPromptWithinTwoMiBBudget() throws Exception {
        byte[] bytes=("{\"parameters\":{\"messages\":[{\"role\":\"user\",\"content\":\""+"x".repeat(300*1024)+"\"}]}}").getBytes(StandardCharsets.UTF_8);
        var response=new MockHttpServletResponse();String route=ROUTE.substring(0,ROUTE.length()-"events".length())+"operations";
        new WorkerRequestBoundaryFilter(identity()).doFilter(request(route,bytes),response,(req,res)->assertArrayEquals(bytes,req.getInputStream().readAllBytes()));assertEquals(200,response.getStatus());
    }
    @Test void modelStartUnknownLengthStillEnforcesActualTwoMiB() throws Exception {
        String route=ROUTE.substring(0,ROUTE.length()-"events".length())+"operations";var request=spy(request(route,new byte[com.ruoyi.paiflow.service.ModelOperationJson.REQUEST_BYTES+1]));doReturn(-1L).when(request).getContentLengthLong();
        var response=new MockHttpServletResponse();new WorkerRequestBoundaryFilter(identity()).doFilter(request,response,(req,res)->fail("不应进入MVC"));assertEquals(413,response.getStatus());
    }
    @Test void modelFramesAndCancelKeepSmallControlBudget() throws Exception {
        for(String suffix:new String[]{"operations/op/frames","operations/op/cancel","resources/model:1:version:2"}){
            var response=new MockHttpServletResponse();String route=ROUTE.substring(0,ROUTE.length()-"events".length())+suffix;
            new WorkerRequestBoundaryFilter(identity()).doFilter(request(route,new byte[RunProtocolLimits.CONTROL_BYTES+1]),response,(req,res)->fail("控制请求不得借用模型大正文限额"));assertEquals(413,response.getStatus());
        }
    }
}
