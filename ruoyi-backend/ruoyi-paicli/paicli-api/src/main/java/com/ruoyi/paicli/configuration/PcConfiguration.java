package com.ruoyi.paicli.configuration;

import com.ruoyi.paicli.mapper.PcLedgerMapper;
import com.ruoyi.paicli.service.*;
import com.ruoyi.system.secret.ManagedSecretService;
import com.ruoyi.system.service.ISysUserService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.time.Clock;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="paicli",name="enabled",havingValue="true")
@EnableConfigurationProperties(PcProperties.class)
@MapperScan(basePackages="com.ruoyi.paicli.mapper",sqlSessionFactoryRef="sqlSessionFactory")
public class PcConfiguration {
    @Bean public org.springframework.boot.web.servlet.FilterRegistrationBean<PcRequestSizeFilter> pcRequestSizeFilter(){var bean=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new PcRequestSizeFilter());bean.addUrlPatterns("/paicli/*");bean.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE+20);return bean;}
    @Bean public PcJson pcJson(ObjectMapper mapper){return new PcJson(mapper);}
    @Bean public PcStore pcStore(PcLedgerMapper mapper,PcJson json){return new PcStore(mapper,json,Clock.systemUTC());}
    @Bean public PcCrypto pcCrypto(ManagedSecretService secrets,PcJson json){return new PcCrypto(secrets,json);}
    @Bean public PcAccess pcAccess(PcStore store){return new PcAccess(store);}
    @Bean public PcPersonalService pcPersonalService(PcStore s,PcAccess a,PcIdempotency i,PcProperties p,ManagedSecretService secrets,org.springframework.beans.factory.ObjectProvider<PcTerminalService> terminal){PcPersonalService personal=new PcPersonalService(s,a,i,p);personal.setSecrets(secrets);personal.setTerminalStatus(id->{PcTerminalService service=terminal.getIfAvailable();return service==null?java.util.Map.of("ready",false,"code","PC_TERMINAL_DISABLED","message","网页终端功能尚未启用"):service.readiness(id);});return personal;}
    @Bean public PcEvidence pcEvidence(PcStore store,PcProperties p){return new PcEvidence(store,p.getEventRetentionDays());}
    @Bean public PcIdempotency pcIdempotency(PcStore store,PcCrypto crypto){return new PcIdempotency(store,crypto);}
    @Bean public PcWorkspaceService pcWorkspaceService(PcStore s,PcAccess a,PcEvidence e,PcIdempotency i,ISysUserService u,PcConnectionService c){return new PcWorkspaceService(s,a,e,i,u,c);}
    @Bean public PcDeviceService pcDeviceService(PcStore s,PcAccess a,PcCrypto c,PcIdempotency i,PcEvidence e,PcProperties p,ISysUserService u,PcPersonalService personal){PcDeviceService d=new PcDeviceService(s,a,c,i,e,p,u);d.setPersonal(personal);return d;}
    @Bean public PcSessionService pcSessionService(PcStore s,PcAccess a,PcIdempotency i,PcEvidence e,ISysUserService u,ManagedSecretService secrets,PcPersonalService personal,PcArtifactService artifacts){PcSessionService sess=new PcSessionService(s,a,i,e,u,secrets);sess.setPersonal(personal);sess.setArtifacts(artifacts);return sess;}
    @Bean public PcPromptService pcPromptService(PcStore s,PcAccess a,PcIdempotency i,PcEvidence e){return new PcPromptService(s,a,i,e);}
    @Bean public PcRunnerService pcRunnerService(PcStore s,PcAccess a,PcIdempotency i,PcEvidence e,PcProperties p,PcArtifactService artifacts,ManagedSecretService secrets){return new PcRunnerService(s,a,i,e,p,artifacts,secrets);}
    @Bean public PcToolService pcToolService(PcStore s,PcAccess a,PcIdempotency i,PcEvidence e,PcCrypto c,PcRunnerService r,PcProperties p,PcArtifactService ar){return new PcToolService(s,a,i,e,c,r,p,ar);}
    @Bean public PcKnowledgeService pcKnowledgeService(PcStore s,PcAccess a,PcIdempotency i,PcEvidence e,PcSessionService sessions){return new PcKnowledgeService(s,a,i,e,sessions);}
    @Bean public PcConnectionService pcConnectionService(PcStore s,PcAccess a,PcIdempotency i,PcEvidence e,ManagedSecretService secrets,PcSessionService sessions,PcProperties p){return new PcConnectionService(s,a,i,e,secrets,sessions,p);}
    @Bean public PcRunnerContextService pcRunnerContextService(PcStore s,PcRunnerService r,PcKnowledgeService k,PcConnectionService c,PcToolService t,PcIdempotency i,PcAccess a,PcArtifactService artifacts){return new PcRunnerContextService(s,r,k,c,t,i,a,artifacts);}
    @Bean public PcArtifactService pcArtifactService(PcStore s,PcAccess a,PcIdempotency i,PcEvidence e){return new PcArtifactService(s,a,i,e);}
    @Bean public PcCommandService pcCommandService(PcStore s,PcAccess a,PcIdempotency i,PcSessionService sess,PcKnowledgeService k,PcConnectionService c,PcToolService t){return new PcCommandService(s,a,i,sess,k,c,t);}
    @Bean public PcWechatService pcWechatService(PcStore s,PcAccess a,PcIdempotency i,PcCrypto c,PcToolService t,PcSessionService ss,PcCommandService cmd,PcEvidence e,ManagedSecretService sec,PcProperties p,PcConnectionService conn){return new PcWechatService(s,a,i,c,t,ss,cmd,e,sec,p,conn);}
    @Bean public PcImportService pcImportService(PcStore s,PcAccess a,PcIdempotency i,PcEvidence e,PcArtifactService ar,ISysUserService u){return new PcImportService(s,a,i,e,ar,u);}
    @Bean public PcRunnerOutputService pcRunnerOutputService(PcStore s,PcRunnerService r,PcToolService t,PcArtifactService a,PcIdempotency i){return new PcRunnerOutputService(s,r,t,a,i);}
    @Bean(name="paicliTransactionManager") public PlatformTransactionManager paicliTransactionManager(@Qualifier("dynamicDataSource") DataSource source){return new DataSourceTransactionManager(source);}
    @Bean public ApplicationRunner paicliSchemaGuard(@Qualifier("dynamicDataSource") DataSource source,PcProperties p){return args->{p.validate();com.ruoyi.paicli.migration.PcSchemaGuard.verify(source,p.getExpectedDatabase());};}
    @Bean public PcProjectRuleService pcProjectRuleService(PcStore s,PcAccess a,PcIdempotency i,PcEvidence e,PcArtifactService ar){return new PcProjectRuleService(s,a,i,e,ar);}

}
