import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.ModelOperationJson;
import java.nio.charset.StandardCharsets;

/** 实际纯JSON helper探针；不构造Service、不访问DB/SDK/网络。 */
public class ModelOperationJsonReview {
    private static int checks;
    private static void require(boolean pass,String name){if(!pass)throw new AssertionError(name);checks++;}
    private static void rejected(Runnable action,int code,String name){
        try{action.run();throw new AssertionError(name+" 未拒绝");}
        catch(ServiceException error){require(error.getCode()==code,name);}
    }
    private static JSONObject atBytes(int bytes){
        var empty=new JSONObject();empty.put("payload","");
        int overhead=ModelOperationJson.canonical(empty,bytes).getBytes(StandardCharsets.UTF_8).length;
        empty.put("payload","x".repeat(bytes-overhead));return empty;
    }
    public static void main(String[] args){
        var withNull=new JSONObject();withNull.put("iterationId",null);
        require("{\"iterationId\":null}".equals(ModelOperationJson.canonical(withNull,100)),"显式null必须保全");
        require(!ModelOperationJson.canonical(withNull,100).equals(ModelOperationJson.canonical(new JSONObject(),100)),"null不得等价缺字段");
        for(int limit:new int[]{ModelOperationJson.FRAME_BYTES,ModelOperationJson.REQUEST_BYTES}){
            var exact=atBytes(limit);require(ModelOperationJson.canonical(exact,limit).getBytes(StandardCharsets.UTF_8).length==limit,"预算边界允许");
            exact.put("payload",exact.getString("payload")+"x");rejected(()->ModelOperationJson.canonical(exact,limit),413,"预算+1拒绝");
        }
        require(ModelOperationJson.text("x".repeat(4096),4096,"key").length()==4096,"4096 ASCII标识允许");
        rejected(()->ModelOperationJson.text("x".repeat(4097),4096,"key"),400,"4097标识拒绝");
        rejected(()->ModelOperationJson.text("x\n",4096,"key"),400,"控制字符拒绝");
        rejected(()->ModelOperationJson.integer(true,0,64,"cursor"),400,"bool不得当整数");
        rejected(()->ModelOperationJson.integer(1.0,0,64,"cursor"),400,"浮点不得当整数");
        require(ModelOperationJson.integer(64,1,64,"limit")==64,"批次整数64允许");
        System.out.println("actualHelper=true checks="+checks+" failures=0 databaseCalls=0 networkCalls=0 providerCalls=0");
    }
}
