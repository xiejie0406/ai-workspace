import com.ruoyi.system.secret.ManagedSecretCrypto;
import com.ruoyi.system.secret.ManagedSecretService;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** 仅首次准备PaiCLI固定用途；复用平台服务及事务，不轮换、不输出明文。 */
public final class PcControlKeyBootstrap {
    private static final String ALIAS="platform.paicli.control-key";
    public static void main(String[] args){
        if(args.length!=2||!java.util.Set.of("check","init").contains(args[0])||!"ry-vue".equals(args[1]))throw new IllegalArgumentException("check|init ry-vue");
        try{
            String url=required("RUOYI_DB_URL");URI uri=URI.create(url.substring("jdbc:".length()));
            if(!url.startsWith("jdbc:mysql://")||!java.util.Set.of("localhost","127.0.0.1").contains(uri.getHost())||uri.getPort()!=3306||!"/ry-vue".equals(uri.getPath()))throw new IllegalStateException("Approved database mismatch");
            if("init".equals(args[0])&&!"INIT_CONTROL_KEY:ry-vue".equals(System.getenv("PAICLI_CONTROL_KEY_CONFIRM")))throw new IllegalStateException("Explicit initialization confirmation missing");
            DriverManagerDataSource source=new DriverManagerDataSource(url,required("RUOYI_DB_USERNAME"),required("RUOYI_DB_PASSWORD"));source.setDriverClassName("com.mysql.cj.jdbc.Driver");
            try(var connection=source.getConnection()){if(!args[1].equals(connection.getCatalog())||!"MySQL".equals(connection.getMetaData().getDatabaseProductName()))throw new IllegalStateException("Actual database mismatch");}
            ManagedSecretService secrets=new ManagedSecretService(source,new ManagedSecretCrypto());
            var metadata=secrets.list("PLATFORM",null).stream()
                .filter(item->ALIAS.equals(item.alias())).findFirst().orElse(null);
            if(metadata==null&&"init".equals(args[0])){
                metadata=new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->{
                    byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
                    try{return secrets.put("PLATFORM",ALIAS,"paicli","paicli","control-key","aes256-gcm","PaiCLI控制凭证加密",Base64.getEncoder().encodeToString(bytes),"用户授权完整迁移：首次准备本机控制凭证固定用途",null,"paicli-local-migration");}
                    finally{Arrays.fill(bytes,(byte)0);}
                });
            }
            if(metadata==null){System.out.println("alias="+ALIAS+"; exists=false; initialized=false");return;}
            if(!"paicli".equals(metadata.projectCode())||!"ACTIVE".equals(metadata.status()))throw new IllegalStateException("Existing alias ownership or status mismatch");
            String value=secrets.require("PLATFORM",ALIAS);
            if(value.stripLeading().startsWith("{"))value=new tools.jackson.databind.ObjectMapper().readTree(value).path("keyBase64").asText();
            byte[] decoded=Base64.getDecoder().decode(value);try{if(decoded.length!=32)throw new IllegalStateException("Invalid control key length");}finally{Arrays.fill(decoded,(byte)0);}
            System.out.println("alias="+ALIAS+"; project=paicli; status=ACTIVE; version="+metadata.activeVersion()+"; valid32Bytes=true; plaintextPrinted=false");
        }catch(Exception failure){throw new IllegalStateException("PaiCLI control key operation failed ("+failure.getClass().getSimpleName()+"); details and secret values withheld");}
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Required environment missing");return value;}
}
