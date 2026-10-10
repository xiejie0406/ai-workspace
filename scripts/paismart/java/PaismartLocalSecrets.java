import com.ruoyi.system.secret.ManagedSecretCrypto;
import com.ruoyi.system.secret.ManagedSecretService;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** 已批准本机首次配置工具：仅本项目固定用途；复用平台规则、加密与审计，绝不回显值。 */
public final class PaismartLocalSecrets {
    private record Spec(String kind,String alias,String provider,String capability,String mode,String name,String env) {}
    private static final List<Spec> SPECS = List.of(
        new Spec("PLATFORM","platform.paismart.minio.access-key","minio","object-store","access-key","PaiSmart本机对象访问标识","PAISMART_MINIO_ACCESS_KEY"),
        new Spec("PLATFORM","platform.paismart.minio.secret-key","minio","object-store","secret-key","PaiSmart本机对象访问密钥","PAISMART_MINIO_SECRET_KEY"),
        new Spec("PLATFORM","platform.paismart.elasticsearch.api-key","elasticsearch","search","api-key","PaiSmart本机检索APIKey","PAISMART_ES_API_KEY"),
        new Spec("AI","ai.paismart.sub2api.llm","sub2api","llm","bearer","PaiSmart文字模型","PAISMART_LLM_KEY"),
        new Spec("AI","ai.paismart.aliyun.embedding","aliyun","embedding","bearer","PaiSmart阿里云向量模型","PAISMART_EMBEDDING_KEY")
    );
    public static void main(String[] args) {
        String url=required("RUOYI_DB_URL");
        if(!url.matches("jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):3306/ry-vue(?:\\?.*)?"))throw new IllegalArgumentException("只支持已批准本机原库");
        var ds=new DriverManagerDataSource(url,required("RUOYI_DB_USERNAME"),required("RUOYI_DB_PASSWORD"));
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        var service=new ManagedSecretService(ds,new ManagedSecretCrypto());
        if(args.length==2&&args[0].equals("connections")){
            connections(service,args[1]);
            // 所有只读检查已结束并输出；退出此一次性工具，避免SDK空闲线程延迟日常入口。
            System.out.flush();System.exit(0);return;
        }
        if(args.length==1&&args[0].equals("readiness")){
            readiness(new JdbcTemplate(ds),service);return;
        }
        if(args.length!=1||!args[0].equals("register"))throw new IllegalArgumentException("仅支持register/readiness");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.executeWithoutResult(status->{
            for(var spec:SPECS){
                String value=System.getenv(spec.env());if(value==null||value.isBlank())continue;
                var existing=service.list(spec.kind(),"paismart").stream().filter(m->m.alias().equals(spec.alias())).findFirst();
                if(existing.isPresent()){
                    var resolved=service.resolve(spec.kind(),spec.alias());
                    if(!resolved.metadata().providerCode().equals(spec.provider())||!resolved.metadata().capabilityCode().equals(spec.capability())||!resolved.metadata().authMode().equals(spec.mode()))throw new IllegalStateException("现有用途元数据不同，禁止自动改写："+spec.alias());
                    if(!MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8),resolved.value().getBytes(StandardCharsets.UTF_8)))throw new IllegalStateException("现有用途值与本机配置不同，禁止自动轮换："+spec.alias());
                    System.out.println("REUSED "+spec.alias()+" version="+resolved.metadata().activeVersion());
                }else{
                    var saved=service.put(spec.kind(),spec.alias(),"paismart",spec.provider(),spec.capability(),spec.mode(),spec.name(),value,"用户批准本机完整链路首次配置",null,"paismart-local-setup");
                    System.out.println("REGISTERED "+saved.alias()+" version="+saved.activeVersion());
                }
            }
        });
    }
    private static void connections(ManagedSecretService secrets,String path){
        var properties=new java.util.Properties();
        try(var input=java.nio.file.Files.newInputStream(java.nio.file.Path.of(path))){properties.load(input);}catch(java.io.IOException e){throw new IllegalStateException("本机连接配置不可读");}
        var environment=new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.PropertiesPropertySource("local-connections",properties));
        var settings=new com.ruoyi.paismart.infrastructure.intelligence.IntelligenceSettings(environment,secrets);
        var objects=new com.ruoyi.paismart.infrastructure.storage.MinioDocumentObjectStore(
            environment.getRequiredProperty("paismart.object-store.endpoint"),environment.getRequiredProperty("paismart.object-store.bucket"),
            "platform.paismart.minio.access-key","platform.paismart.minio.secret-key",true,false,true,secrets);
        var minio=objects.probe();
        boolean esReady;
        try(var http=new com.ruoyi.paismart.infrastructure.intelligence.ControlledHttp(settings)){
            String ref=settings.text("elasticsearch.secret-ref","");
            if(!"platform.paismart.elasticsearch.api-key".equals(ref))throw new IllegalStateException("ES本项目托管用途缺失");
            var health=http.json("GET",settings.text("elasticsearch.endpoint","")+"/_cluster/health","ApiKey "+settings.secret(ref),null,10);
            esReady=java.util.Set.of("green","yellow").contains(String.valueOf(health.get("status")))&&!Boolean.TRUE.equals(health.get("timed_out"));
        }
        System.out.println(new ObjectMapper().writeValueAsString(java.util.Map.of("minio",minio,"elasticsearchReady",esReady,"scope","原托管用途解密、生产MinIO桶HEAD、生产ControlledHttp及Java私有TLS的ES健康；不写对象或索引、不调用模型")));
        if(!"READY".equals(minio.get("state"))||!esReady)throw new IllegalStateException("本机生产连接探测未就绪");
    }
    private static void readiness(JdbcTemplate db,ManagedSecretService secrets){
        var result=new LinkedHashMap<String,Object>();result.put("database",db.queryForObject("SELECT DATABASE()",String.class));
        var purpose=new LinkedHashMap<String,Boolean>();
        for(var spec:SPECS){boolean ready=false;try{var r=secrets.resolve(spec.kind(),spec.alias());var m=r.metadata();ready=m.projectCode().equals("paismart")&&m.providerCode().equals(spec.provider())&&m.capabilityCode().equals(spec.capability())&&m.authMode().equals(spec.mode())&&!r.value().isBlank();}catch(RuntimeException unavailable){/* 只回报是否就绪，不回显凭据或数据库异常。 */}purpose.put(spec.alias(),ready);}
        result.put("managedPurposes",purpose);
        var providers=new ArrayList<java.util.Map<String,Object>>();
        var settings=new com.ruoyi.paismart.infrastructure.intelligence.IntelligenceSettings(new org.springframework.core.env.StandardEnvironment(),secrets);
        try(var http=new com.ruoyi.paismart.infrastructure.intelligence.ControlledHttp(settings)){
            var service=new com.ruoyi.paismart.application.intelligence.ProviderService(db,null,http,settings,null);
            for(var row:service.listAll()){
                var safe=new LinkedHashMap<String,Object>();
                for(var key:List.of("id","scope","name","endpoint","model","dimension","secretRef","enabled","active","rowVersion","configured","compatibilityValid"))if(row.containsKey(key))safe.put(key,row.get(key));
                var config=(java.util.Map<?,?>)row.get("config");safe.put("providerCode",config.get("providerCode"));providers.add(safe);
            }
        }
        result.put("providers",providers);
        result.put("pendingJobs",db.queryForObject("SELECT COUNT(*) FROM ps_job WHERE state IN ('QUEUED','RETRY','RUNNING')",Long.class));
        result.put("pendingJobTypes",db.queryForList("SELECT type,COUNT(*) AS total FROM ps_job WHERE state IN ('QUEUED','RETRY','RUNNING') GROUP BY type"));
        result.put("legacyPendingTextJobs",db.queryForObject("SELECT COUNT(*) FROM ps_job WHERE state IN ('QUEUED','RETRY','RUNNING') AND type IN ('DOCUMENT_PROCESS','DOCUMENT_PARSE','DOCUMENT_REINDEX','REINDEX','CONFLICT_DETECT') AND COALESCE(JSON_EXTRACT(payload_json,'$._textProviderPolicy'),0)<>1",Long.class));
        result.put("activeIndexes",db.queryForObject("SELECT COUNT(*) FROM ps_index_revision WHERE state='ACTIVE'",Long.class));
        result.put("documents",db.queryForObject("SELECT COUNT(*) FROM ps_document",Long.class));
        result.put("quotaPolicies",db.queryForObject("SELECT COUNT(*) FROM ps_quota_policy",Long.class));
        result.put("tokenAccounts",db.queryForObject("SELECT COUNT(*) FROM ps_token_account",Long.class));
        result.put("availableTokens",db.queryForObject("SELECT COALESCE(SUM(available),0) FROM ps_token_account",Long.class));
        result.put("menus",db.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE create_by='paismart-migration-v1'",Long.class));
        result.put("modelCallsTested",false);
        System.out.println(new ObjectMapper().writeValueAsString(result));
    }
    private static String required(String name){var value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("缺少启动环境："+name);return value;}
}
