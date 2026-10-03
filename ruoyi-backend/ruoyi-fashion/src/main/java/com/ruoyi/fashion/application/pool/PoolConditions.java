package com.ruoyi.fashion.application.pool;

import java.util.*;
import com.ruoyi.common.exception.ServiceException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import static com.ruoyi.fashion.application.pool.PoolModels.*;

/** 服务端解析四组草稿及逐字段继承，隐藏组不参加校验/生成。 */
public final class PoolConditions {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public static final Set<String> SHARED=Set.of("quantity","warehouseCode","seasons","genders","brands",
            "groupPriceMin","groupPriceMax","vipPriceMin","vipPriceMax","stockFreshness","requireAllSizes","hasMainImage","imagesConfirmed");
    private PoolConditions(){}
    public static Create resolve(Create request){
        if(request==null)throw new ServiceException("选品请求不能为空");
        List<GroupFilter> supplied=request.groups()==null?List.of():request.groups();
        int count=request.activeGroupCount()==null?supplied.size():request.activeGroupCount();
        if(count<2||count>4)throw new ServiceException("请选择二组、三组或四组模式");
        List<GroupFilter> drafts=request.draftGroups();
        if(drafts==null){if(supplied.size()!=count)throw new ServiceException("活动组数量与条件不一致");drafts=new ArrayList<>(supplied);while(drafts.size()<4)drafts.add(empty());}
        if(drafts.size()!=4||drafts.stream().anyMatch(Objects::isNull))throw new ServiceException("必须保留完整四组查询草稿");
        List<Map<String,String>> inheritance=request.inheritance();
        if(inheritance==null)inheritance=List.of(Map.of(),Map.of(),Map.of(),Map.of());
        if(inheritance.size()!=4||inheritance.stream().anyMatch(Objects::isNull))throw new ServiceException("继承模式必须对应四组草稿");
        List<Map<String,String>> modes=new ArrayList<>();
        for(Map<String,String> row:inheritance){if(!SHARED.containsAll(row.keySet())||row.values().stream().anyMatch(v->!Set.of("follow","independent").contains(v==null?"":v)))throw new ServiceException("查询条件继承字段或模式无效");modes.add(Map.copyOf(row));}
        Map<String,Object> total=values(request.totalConditions()==null?empty():request.totalConditions());
        if(request.totalConditions()!=null && request.totalConditions().quantity()!=null && request.totalConditions().quantity()<1)throw new ServiceException("团购套数必须是正整数");
        List<GroupFilter> resolved=new ArrayList<>();
        for(int i=0;i<count;i++){
            Map<String,Object> data=values(drafts.get(i));
            for(var mode:modes.get(i).entrySet())if("follow".equals(mode.getValue()))data.put(mode.getKey(),total.get(mode.getKey()));
            if(data.get("unitsPerSet")==null)data.put("unitsPerSet",1);
            resolved.add(JSON.convertValue(data,GroupFilter.class));
        }
        return new Create(request.title(),List.copyOf(resolved),request.priceBasis(),request.totalMin(),request.totalMax(),count,List.copyOf(drafts),request.totalConditions(),List.copyOf(modes));
    }
    public static GroupFilter empty(){return JSON.readValue("{}",GroupFilter.class);}
    private static Map<String,Object> values(GroupFilter value){return JSON.convertValue(value,new TypeReference<LinkedHashMap<String,Object>>(){});}
}
