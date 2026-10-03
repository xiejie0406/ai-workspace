package com.ruoyi.paismart.infrastructure;

import com.ruoyi.paismart.configuration.PaismartEnabled;
import com.ruoyi.system.secret.ManagedSecretService;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 使用平台托管密钥，只有显式启动消费时读取连接配置。 */
@Component
@PaismartEnabled
public class KafkaConnectionSettings {
    private final Environment env;private final ManagedSecretService secrets;
    public KafkaConnectionSettings(Environment env,ManagedSecretService secrets){this.env=env;this.secrets=secrets;}
    public Properties clientProperties(){
        Properties p=new Properties();String protocol=env.getProperty("paismart.jobs.security-protocol","SSL");
        if(!Set.of("SSL","SASL_SSL","PLAINTEXT","SASL_PLAINTEXT").contains(protocol))throw new IllegalStateException("Kafka安全协议无效");
        if(protocol.contains("PLAINTEXT")&&!env.getProperty("paismart.jobs.allow-plaintext",Boolean.class,false))throw new IllegalStateException("Kafka明文传输未获配置允许");
        p.put("security.protocol",protocol);p.put("ssl.endpoint.identification.algorithm","https");
        if(protocol.startsWith("SASL")){
            String mechanism=env.getProperty("paismart.jobs.sasl-mechanism","SCRAM-SHA-512");
            if(!Set.of("PLAIN","SCRAM-SHA-256","SCRAM-SHA-512").contains(mechanism))throw new IllegalStateException("Kafka认证机制无效");
            p.put("sasl.mechanism",mechanism);p.put("sasl.jaas.config",secret(env.getProperty("paismart.jobs.jaas-secret-ref","platform.paismart.kafka.jaas")));
        }
        String truststore=env.getProperty("paismart.jobs.truststore-location","");
        if(!truststore.isBlank()) {java.nio.file.Path file=java.nio.file.Path.of(truststore).toAbsolutePath().normalize();if(!java.nio.file.Files.isRegularFile(file))throw new IllegalStateException("Kafka信任库不存在");p.put("ssl.truststore.location",file.toString());p.put("ssl.truststore.password",secret(env.getProperty("paismart.jobs.truststore-password-ref","platform.paismart.kafka.truststore")));}
        return p;
    }
    private String secret(String ref){if(ref==null||!ref.startsWith("platform.paismart.kafka."))throw new IllegalStateException("Kafka密钥用途无效");var r=secrets.resolve("PLATFORM",ref);if(!"paismart".equals(r.metadata().projectCode()))throw new IllegalStateException("Kafka密钥项目不匹配");return r.value();}
    public double highWater(){return Math.max(.5,Math.min(.95,env.getProperty("paismart.jobs.memory-high-water",Double.class,.85)));}
    public double lowWater(){return Math.max(.2,Math.min(highWater()-.05,env.getProperty("paismart.jobs.memory-low-water",Double.class,.70)));}
}
