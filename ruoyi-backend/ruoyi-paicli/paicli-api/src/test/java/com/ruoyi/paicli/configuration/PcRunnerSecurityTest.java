package com.ruoyi.paicli.configuration;
import com.ruoyi.paicli.domain.*;
import com.ruoyi.paicli.service.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际Spring Security matcher/filter链的设备类型隔离，无服务器和外部认证调用。 */
class PcRunnerSecurityTest {
    AnnotationConfigWebApplicationContext context;MockMvc mvc;
    @BeforeEach void start(){context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.setEnvironment(new MockEnvironment().withProperty("paicli.enabled","true"));context.register(TestConfig.class,PcRunnerSecurityConfiguration.class);context.refresh();mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();}
    @AfterEach void close(){context.close();}
    @Test void deviceCannotClaimAndSessionCannotExchangeBootstrap()throws Exception{mvc.perform(post("/paicli/runner/v1/sessions").header("X-PaiCLI-Runner-Token","pcd_fixture")).andExpect(status().isOk());mvc.perform(post("/paicli/runner/v1/claims").header("X-PaiCLI-Runner-Token","pcd_fixture")).andExpect(status().isForbidden());mvc.perform(post("/paicli/runner/v1/mcp-context").header("X-PaiCLI-Runner-Token","pcd_fixture")).andExpect(status().isForbidden());mvc.perform(post("/paicli/runner/v1/mcp-context").header("X-PaiCLI-Runner-Token","pcs_fixture")).andExpect(status().isOk());mvc.perform(post("/paicli/runner/v1/sessions").header("X-PaiCLI-Runner-Token","pcs_fixture")).andExpect(status().isForbidden());mvc.perform(post("/paicli/runner/v1/claims").header("X-PaiCLI-Runner-Token","pcs_fixture")).andExpect(status().isOk());}
    @Test void platformTokenAndMissingCredentialDoNotBypassDeviceFilter()throws Exception{mvc.perform(post("/paicli/runner/v1/claims").header("Authorization","Bearer platform-fixture").header("X-PaiCLI-Runner-Token","pcs_fixture")).andExpect(status().isUnauthorized());mvc.perform(post("/paicli/runner/v1/claims")).andExpect(status().isUnauthorized());assertNull(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());}
    @Test void wechatCredentialOnlyWorksInsideItsChannelProtocol()throws Exception{mvc.perform(post("/paicli/runner/v1/wechat/events").header("X-PaiCLI-Runner-Token","pcw_fixture")).andExpect(status().isOk());mvc.perform(post("/paicli/runner/v1/wechat/context").header("X-PaiCLI-Runner-Token","pcw_fixture")).andExpect(status().isOk());mvc.perform(post("/paicli/runner/v1/wechat/account").header("X-PaiCLI-Runner-Token","pcw_fixture")).andExpect(status().isForbidden());mvc.perform(post("/paicli/runner/v1/claims").header("X-PaiCLI-Runner-Token","pcw_fixture")).andExpect(status().isForbidden());mvc.perform(post("/paicli/runner/v1/mcp-context").header("X-PaiCLI-Runner-Token","pcw_fixture")).andExpect(status().isForbidden());}
    @Test void moduleFalseDoesNotRegisterRunnerChain(){try(var disabled=new AnnotationConfigWebApplicationContext()){disabled.setServletContext(new MockServletContext());disabled.setEnvironment(new MockEnvironment().withProperty("paicli.enabled","false"));disabled.register(TestConfig.class,PcRunnerSecurityConfiguration.class);disabled.refresh();assertFalse(disabled.containsBean("paicliRunnerChain"));}}
    @Configuration @EnableWebMvc @EnableWebSecurity static class TestConfig {
        @Bean PcJson json(){return new PcJson(new ObjectMapper());}
        @Bean PcDeviceService devices(){PcDeviceService devices=mock(PcDeviceService.class);when(devices.authenticate(any())).thenAnswer(inv->{String token=inv.getArgument(0);if(token==null||!token.startsWith("pc"))throw new PcException(401,"PC_RUNNER_CREDENTIAL","缺少设备凭证");String kind=token.startsWith("pcd_")?"DEVICE":token.startsWith("pcw_")?"WECHAT":"SESSION";return new PcRunnerPrincipal(1,1,1,1,1,kind);});return devices;}
        @Bean Routes routes(){return new Routes();}
    }
    @RestController static class Routes {@PostMapping("/paicli/runner/v1/**") String ok(){return "ok";}}
}
