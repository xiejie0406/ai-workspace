import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.RunJsonBoundary;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** 独立读取现有classes；不调用Service、Mapper、数据库或外部服务。 */
public class WorkflowNullBudgetReview {
    public static void main(String[] args) throws Exception {
        String field = "b".repeat(32768) + "0";
        int tinyCode = 0;
        try { RunJsonBoundary.check(Collections.singletonMap(field, null), 4096); }
        catch (ServiceException ex) { tinyCode = ex.getCode(); }
        // 首败源码dd1981de的catch(ServiceException)将tinyCode统一包装为400。
        int oldWrappedCode = tinyCode == 0 ? 0 : 400;
        JSONObject merged = JSONObject.of("oldSection", "a".repeat(9 * 1024 * 1024));
        String prefix = "b".repeat(32768);
        for (int i = 0; i < 300; i++) merged.put(prefix + i, null);
        var check = Class.forName("com.ruoyi.paiflow.service.WorkflowJsonBoundary")
            .getDeclaredMethod("check", Object.class, String.class, int.class);
        check.setAccessible(true);
        int actualMergedCode = 0;
        try { check.invoke(null, merged, "advancedConfig", 16 * 1024 * 1024); }
        catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof ServiceException service) actualMergedCode = service.getCode();
            else throw ex;
        }
        System.out.println(JSON.toJSONString(JSONObject.of(
            "fieldLength", field.length(),
            "oldSingletonBudget", 4096,
            "actualNullSingletonBytes", JSON.toJSONString(Collections.singletonMap(field,null),JSONWriter.Feature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8).length,
            "actualTinyBudgetCode", tinyCode,
            "oldSourceCatchWouldWrapAsCode", oldWrappedCode,
            "finalMergedBytes", merged.toJSONString(JSONWriter.Feature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8).length,
            "actualFinalMergedBudget", 16 * 1024 * 1024,
            "currentBoundaryFinalMergedCode", actualMergedCode
        )));
    }
}
