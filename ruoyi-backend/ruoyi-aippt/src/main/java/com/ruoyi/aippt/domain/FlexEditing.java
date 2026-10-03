package com.ruoyi.aippt.domain;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.*;

/** 复用源项目的插入权重与固定槽位聚类规则，几何求解仍由 Worker 执行。 */
public final class FlexEditing {
    private FlexEditing() {}

    public static JSONObject leaf(String blockId,String type) {
        JSONObject leaf=JSONObject.of("type","block","id",UUID.randomUUID().toString(),"block_id",blockId,"grow",1);
        if("bullets".equals(type))leaf.put("text_style","bullet");
        else if(Set.of("text","cards","callout").contains(type))leaf.put("text_style","body");
        return leaf;
    }

    public static boolean insertLeaf(JSONObject root,String parentId,int index,JSONObject leaf) {
        JSONObject parent=find(root,parentId);
        if(parent==null||"block".equals(parent.getString("type")))return false;
        JSONArray children=parent.getJSONArray("children");
        AipptRules.require(children!=null&&index>=0&&index<=children.size(),"插入下标无效");
        if(parent.getString("id").startsWith("spacer-")) {
            parent.put("id","cell-"+UUID.randomUUID().toString());
            parent.put("grow",Math.max(parent.getDoubleValue("grow"),1));
        }
        if(!children.isEmpty()) {
            int neighborIndex=index>0?index-1:0;
            JSONObject neighbor=children.getJSONObject(neighborIndex);
            if("column".equals(parent.getString("type"))) {
                double source=neighbor.get("grow")==null||neighbor.getDoubleValue("grow")==0?1:neighbor.getDoubleValue("grow");
                double half=Math.max(source/2,.25);
                neighbor.put("grow",half);
                leaf.put("grow",half);
            }
            else {
                JSONArray ratios=parent.getJSONArray("ratios");
                if(ratios==null||ratios.size()!=children.size()) {
                    ratios=new JSONArray();
                    for(int i=0;i<children.size();i++)ratios.add(100d/children.size());
                }
                double half=Math.max(((Number)ratios.get(neighborIndex)).doubleValue()/2,5);
                ratios.set(neighborIndex,half);
                ratios.add(index,half);
                double total=ratios.stream().mapToDouble(value->((Number)value).doubleValue()).sum();
                for(int i=0;i<ratios.size();i++)ratios.set(i,((Number)ratios.get(i)).doubleValue()/total*100);
                parent.put("ratios",ratios);
            }
        }
        children.add(index,leaf);
        return true;
    }

    private static JSONObject find(JSONObject node,String id) {
        if(node==null||id==null)return null;
        if(id.equals(node.getString("id")))return node;
        JSONArray children=node.getJSONArray("children");
        if(children!=null)for(Object raw:children) {
            JSONObject found=find((JSONObject)raw,id);
            if(found!=null)return found;
        }
        return null;
    }

    public static JSONObject fromFixed(JSONObject layout,JSONArray blocks) {
        record Placed(JSONObject slot,JSONObject block) {}
        List<Placed> placed=new ArrayList<>();
        for(Object raw:blocks) {
            JSONObject block=(JSONObject)raw;
            JSONObject slot=layout.getJSONArray("slots").stream().map(JSONObject.class::cast)
                .filter(value->Objects.equals(value.getString("id"),block.getString("slot_id"))).findFirst()
                .orElseThrow(()->AipptException.invalid("固定槽位不存在"));
            placed.add(new Placed(slot,block));
        }
        placed.sort(Comparator.comparingDouble((Placed value)->value.slot().getJSONObject("rect").getDoubleValue("y"))
            .thenComparingDouble(value->value.slot().getJSONObject("rect").getDoubleValue("x")));
        List<List<Placed>> rows=new ArrayList<>();
        for(Placed value:placed) {
            if(rows.isEmpty()||Math.abs(value.slot().getJSONObject("rect").getDoubleValue("y")
                -rows.get(rows.size()-1).get(0).slot().getJSONObject("rect").getDoubleValue("y"))>.05) {
                rows.add(new ArrayList<>());
            }
            rows.get(rows.size()-1).add(value);
        }
        JSONArray rowNodes=new JSONArray();
        for(List<Placed> row:rows) {
            row.sort(Comparator.comparingDouble(value->value.slot().getJSONObject("rect").getDoubleValue("x")));
            JSONArray leaves=new JSONArray(),ratios=new JSONArray();
            double total=row.stream().mapToDouble(value->value.slot().getJSONObject("rect").getDoubleValue("w")).sum();
            for(Placed value:row) {
                JSONObject leaf=leaf(value.block().getString("id"),value.block().getString("type"));
                leaf.put("text_style",value.slot().getString("text_style"));
                leaf.put("rect_override",AipptRules.copy(value.slot().getJSONObject("rect")));
                leaves.add(leaf);
                ratios.add(value.slot().getJSONObject("rect").getDoubleValue("w")/total*100);
            }
            JSONObject container=JSONObject.of("type","row","id",UUID.randomUUID().toString(),"children",leaves,"grow",1,"gap_pt",16);
            if(row.size()>1)container.put("ratios",ratios);
            rowNodes.add(container);
        }
        return JSONObject.of("type","column","id",UUID.randomUUID().toString(),"children",rowNodes,"gap_pt",16,"grow",1);
    }
}
