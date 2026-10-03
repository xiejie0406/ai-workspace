package com.ruoyi.paicli.configuration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
class PcRequestSizeFilterTest {
    @Test void advertisedOversizeIsRejectedBeforeHandler()throws Exception{var request=new MockHttpServletRequest();request.setContent(new byte[9]);var response=new MockHttpServletResponse();new PcRequestSizeFilter(8).doFilter(request,response,(a,b)->fail("不应解析超大请求"));assertEquals(413,response.getStatus());}
    @Test void chunkedOversizeIsRejectedDuringRead()throws Exception{var request=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return -1;}};request.setContent(new byte[9]);var response=new MockHttpServletResponse();new PcRequestSizeFilter(8).doFilter(request,response,(a,b)->a.getInputStream().readAllBytes());assertEquals(413,response.getStatus());}
    @Test void ExactBoundaryContinuesNormally()throws Exception{var request=new MockHttpServletRequest();request.setContent(new byte[8]);var response=new MockHttpServletResponse();new PcRequestSizeFilter(8).doFilter(request,response,(a,b)->assertEquals(8,a.getInputStream().readAllBytes().length));assertEquals(200,response.getStatus());}
}
