package com.ruoyi.aippt.configuration;
import java.lang.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME) @ConditionalOnProperty(prefix="aippt",name="enabled",havingValue="true") public @interface AipptEnabled  {
}
