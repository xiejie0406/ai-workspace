package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 保全原包且拒绝未托管凭据；只返回字段路径，不输出凭据值或静默删字段。 */
final class WorkflowJsonBoundary {
    private WorkflowJsonBoundary() {}
    static void check(Object value,String path,int maxBytes) {
        walk(value,path,0);
        RunJsonBoundary.check(value,maxBytes);
        // 草稿保全显式null；用最终落盘策略计字节，不能以省略null的序列化绕过物理预算。
        if(JSON.toJSONString(value,JSONWriter.Feature.WriteMapNullValue).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>maxBytes)
            throw new ServiceException("工作流JSON超出允许大小",413);
    }
    private static void walk(Object value,String path,int depth) {
        if(depth>48)throw new ServiceException("工作流JSON嵌套过深："+path,400);
        if(value instanceof Map<?,?> map) {
            for(var entry:map.entrySet()) {
                if(!(entry.getKey() instanceof String key))throw new ServiceException("工作流JSON字段名无效："+path,400);
                String location=path+"/"+key.replace("~","~0").replace("/","~1");
                // 由运行边界的唯一凭据字段规则判断当前key；嵌套内容在下一层定位。
                Object marker=entry.getValue()==null?null:"".equals(entry.getValue())?"":"present";
                try { RunJsonBoundary.check(Collections.singletonMap(key,marker),4096); }
                catch(ServiceException ex) { throw new ServiceException("工作流不得保存未托管凭据字段："+location+"；请先绑定受控secretRef",400); }
                walk(entry.getValue(),location,depth+1);
            }
        } else if(value instanceof List<?> list) {
            for(int i=0;i<list.size();i++)walk(list.get(i),path+"/"+i,depth+1);
        } else if(value instanceof String text) {
            // 原data/config/ext经常是JSON字符串，不能让字符串封装绕过同一字段检查。
            String trimmed=text.trim();
            if(trimmed.startsWith("{") || trimmed.startsWith("[")) {
                Object parsed;
                try { parsed=JSON.parse(trimmed); }catch(RuntimeException ignored) { return; }
                if(parsed instanceof Map<?,?> || parsed instanceof List<?>)walk(parsed,path,depth+1);
            }
        }
    }
}
