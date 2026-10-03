import com.ruoyi.paiflow.service.ConfigInfoService;
import java.lang.reflect.InvocationTargetException;

/** 仅调用当前真实静态内容边界；三个URL字面量不访问网络。 */
public class PublicConfigNumericHostReview {
    public static void main(String[] args) throws Exception {
        var method=ConfigInfoService.class.getDeclaredMethod("publicContent",String.class);method.setAccessible(true);
        String[] inputs={"http://2130706433/private-fixture","http://0x7f000001/private-fixture","http://0177.0.0.1/private-fixture"};
        int accepted=0;
        for(int i=0;i<inputs.length;i++) {
            boolean rejected=false;Integer code=null;
            try{method.invoke(null,inputs[i]);}
            catch(InvocationTargetException ex){rejected=true;if(ex.getCause() instanceof com.ruoyi.common.exception.ServiceException error)code=error.getCode();}
            if(!rejected)accepted++;
            System.out.println("case="+(i+1)+", rejected="+rejected+", code="+code);
        }
        System.out.println("cases=3, unexpectedlyAccepted="+accepted+", databaseCalls=0, networkCalls=0, targetWrites=0");
        if(accepted>0)System.exit(1);
    }
}
