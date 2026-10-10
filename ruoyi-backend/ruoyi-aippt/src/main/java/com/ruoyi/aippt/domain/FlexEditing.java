package com.ruoyi.aippt.domain;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.*;

/** 复用源项目的插入权重与固定槽位聚类规则，几何求解仍由 Worker 执行。 */
public final class FlexEditing {
    private FlexEditing() {}

    /** 原 flex_normalize.normalize(clamp_title_grow=False) 的编辑分支；不修改正文或解锁矩形。 */
    public static JSONObject normalize(JSONObject root) {
        JSONObject copy=AipptRules.copy(root);
        Set<String> ids=new HashSet<>();collectIds(copy,ids);
        return normalizeContainer(copy,1,ids);
    }

    private static void collectIds(JSONObject node,Set<String> ids) {
        ids.add(node.getString("id"));
        JSONArray children=node.getJSONArray("children");
        if(children!=null)for(Object child:children)collectIds((JSONObject)child,ids);
    }

    /** 只限制中心自动生成的ID；保留原输入校验与常规parent__overflow名称。 */
    private static String overflowId(String parentId,Set<String> ids) {
        int index=1;
        while(true) {
            String suffix="__overflow"+(index==1?"":"-"+index);
            String candidate=parentId.substring(0,Math.min(parentId.length(),100-suffix.length()))+suffix;
            if(ids.add(candidate))return candidate;
            index++;
        }
    }

    private static JSONObject normalizeContainer(JSONObject node,int depth,Set<String> ids) {
        JSONArray children=new JSONArray();
        for(Object raw:node.getJSONArray("children")) {
            JSONObject child=(JSONObject)raw;
            children.add("block".equals(child.getString("type"))?clampNode(child):normalizeContainer(child,depth+1,ids));
        }
        if(depth>=4)children=flattenChildren(children);
        if("row".equals(node.getString("type"))&&children.size()>4) {
            JSONArray tail=new JSONArray();
            for(int i=3;i<children.size();i++)tail.add(children.get(i));
            children=JSONArray.of(children.get(0),children.get(1),children.get(2),
                JSONObject.of("type","column","id",overflowId(node.getString("id"),ids),"children",tail,"gap_pt",16d,"grow",1d));
        }
        for(int i=0;i<children.size();i++)children.set(i,clampNode(children.getJSONObject(i)));
        normalizeSiblingGrows(children);
        node.put("children",children);
        node.put("grow",clamp(grow(node),spacer(node)?0:.25));
        node.put("ratios","row".equals(node.getString("type"))?normalizeRatios(node.getJSONArray("ratios"),children.size()):null);
        return node;
    }

    private static boolean spacer(JSONObject node) {
        return !"block".equals(node.getString("type"))&&node.getString("id").startsWith("spacer-");
    }

    private static boolean resizeCell(JSONObject node) {
        if(!"column".equals(node.getString("type"))||node.get("gap_pt")==null||node.getDoubleValue("gap_pt")!=0)return false;
        for(Object raw:node.getJSONArray("children"))if(spacer((JSONObject)raw))return true;
        return false;
    }

    private static JSONArray flattenChildren(JSONArray children) {
        JSONArray flat=new JSONArray();
        for(Object raw:children) {
            JSONObject child=(JSONObject)raw;
            if(!"block".equals(child.getString("type"))&&!spacer(child)&&!resizeCell(child)) {
                for(Object value:child.getJSONArray("children")) {
                    JSONObject grandchild=(JSONObject)value;
                    if(!"block".equals(grandchild.getString("type")))flat.addAll(flattenChildren(JSONArray.of(grandchild)));
                    else flat.add(clampNode(grandchild));
                }
            }
            else flat.add(child);
        }
        return flat;
    }

    private static double grow(JSONObject node) {
        return node.get("grow")==null?1:node.getDoubleValue("grow");
    }

    private static double clamp(double value,double minimum) {
        return Math.max(minimum,Math.min(4,value));
    }

    private static JSONObject clampNode(JSONObject node) {
        node.put("grow",clamp(grow(node),spacer(node)?0:.25));
        return node;
    }

    private static void normalizeSiblingGrows(JSONArray children) {
        int n=children.size();
        if(n==0)return;
        double[] grows=new double[n];
        Set<Integer> free=new LinkedHashSet<>();
        boolean hasSpacer=false;
        for(int i=0;i<n;i++) {
            JSONObject child=children.getJSONObject(i);
            boolean fixed=spacer(child);
            grows[i]=clamp(grow(child),fixed?0:.25);
            if(fixed)hasSpacer=true;else free.add(i);
        }
        for(int round=0;round<n+2&&!free.isEmpty();round++) {
            double locked=0,weight=0;
            for(int i=0;i<n;i++)if(free.contains(i))weight+=grows[i];else locked+=grows[i];
            double remaining=hasSpacer?weight:n-locked;
            if(remaining<=0) {
                for(int i:free)grows[i]=.25;
                break;
            }
            for(int i:free)grows[i]=weight<=0?remaining/free.size():grows[i]*remaining/weight;
            Set<Integer> next=new LinkedHashSet<>();
            for(int i:free) {
                double value=clamp(grows[i],.25);
                if(value!=grows[i])grows[i]=value;else next.add(i);
            }
            if(next.size()==free.size())break;
            free=next;
        }
        for(int i=0;i<n;i++)children.getJSONObject(i).put("grow",grows[i]);
    }

    private static JSONArray normalizeRatios(JSONArray ratios,int n) {
        if(n==0)return null;
        if(ratios==null||ratios.size()!=n) {
            JSONArray equal=new JSONArray();
            for(int i=0;i<n;i++)equal.add(100d/n);
            return equal;
        }
        double total=ratios.stream().mapToDouble(value->((Number)value).doubleValue()).sum();
        if(total<=0) {
            JSONArray equal=new JSONArray();
            for(int i=0;i<n;i++)equal.add(100d/n);
            return equal;
        }
        if(n==2) {
            double percentage=Math.abs(total-100)<1e-9?ratios.getDoubleValue(0):ratios.getDoubleValue(0)/total*100;
            double best=33;
            for(double token:new double[]{33,38,50,62,67})if(Math.abs(token-percentage)<Math.abs(best-percentage))best=token;
            return Math.abs(best-percentage)<=2.5?JSONArray.of(best,100-best):JSONArray.of(percentage,100-percentage);
        }
        if(Math.abs(total-100)<1e-9)return ratios;
        JSONArray normalized=new JSONArray();
        for(Object raw:ratios)normalized.add(((Number)raw).doubleValue()/total*100);
        return normalized;
    }

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
