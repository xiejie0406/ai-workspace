import com.ruoyi.paiflow.service.ConfigInfoService;
import java.lang.reflect.InvocationTargetException;

/** 调用当前真实静态边界；不构造Service、不连接DB或URL，不改target。 */
public class PublicConfigBoundaryReview {
    public static void main(String[] args) throws Exception {
        var method=ConfigInfoService.class.getDeclaredMethod("publicContent",String.class);method.setAccessible(true);
        String[] inputs={"\"http:\\/\\/127.0.0.1\\/private\"","https://public.invalid/path?%74oken=private-fixture"};
        int mismatches=0;
        for(int i=0;i<inputs.length;i++) {
            boolean rejected=false;Integer code=null;
            try{method.invoke(null,inputs[i]);}
            catch(InvocationTargetException ex){rejected=true;if(ex.getCause() instanceof com.ruoyi.common.exception.ServiceException error)code=error.getCode();}
            if(!rejected)mismatches++;
            System.out.println("case="+(i==0?"top_level_encoded_private_url":"percent_encoded_credential_key")+", rejected="+rejected+", code="+code);
        }
        System.out.println("cases=2, mismatches="+mismatches+", databaseCalls=0, networkCalls=0, targetWrites=0");
        if(mismatches>0)System.exit(1);
    }
}
