package com.ruoyi.paismart.billing;
import com.ruoyi.paismart.configuration.PaismartConfiguration;
import com.ruoyi.paismart.application.billing.QuotaService;
import com.ruoyi.paismart.application.jobs.JobService;
import com.ruoyi.paismart.infrastructure.PaismartKafkaRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
class DisabledModuleTest {
    @Test void disabledModuleNeedsNoDataSourceOrRedisOrBroker(){try(var context=new AnnotationConfigApplicationContext()){context.register(PaismartConfiguration.class,QuotaService.class,JobService.class,PaismartKafkaRuntime.class);context.refresh();assertTrue(context.getBeansOfType(QuotaService.class).isEmpty());assertTrue(context.getBeansOfType(JobService.class).isEmpty());assertTrue(context.getBeansOfType(PaismartKafkaRuntime.class).isEmpty());assertFalse(context.containsBean("paismartJdbc"));}}
    @Test void entireBusinessPackageStaysUnloadedWithoutEnabledFlag(){try(var context=new AnnotationConfigApplicationContext()){context.scan("com.ruoyi.paismart");context.refresh();assertTrue(Arrays.stream(context.getBeanDefinitionNames()).noneMatch(name->{String type=context.getBeanFactory().getBeanDefinition(name).getBeanClassName();return type!=null&&type.startsWith("com.ruoyi.paismart.");}));assertFalse(context.containsBean("paismartJdbc"));}}
}
