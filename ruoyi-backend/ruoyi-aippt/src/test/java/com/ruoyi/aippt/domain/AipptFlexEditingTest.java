package com.ruoyi.aippt.domain;

import com.alibaba.fastjson2.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AipptFlexEditingTest {
    @Test void spacerIsAdoptedButUnknownAndLeafParentsAreRejected() {
        JSONObject spacer=JSONObject.of("id","spacer-test","type","column","grow",0,"children",new JSONArray());
        JSONObject leaf=FlexEditing.leaf("first","bullets");
        assertTrue(FlexEditing.insertLeaf(spacer,"spacer-test",0,leaf));
        assertTrue(spacer.getString("id").startsWith("cell-"));
        assertEquals(1,spacer.getDoubleValue("grow"));
        assertEquals("bullet",leaf.getString("text_style"));
        assertFalse(FlexEditing.insertLeaf(spacer,"missing",0,FlexEditing.leaf("second","text")));
        assertFalse(FlexEditing.insertLeaf(spacer,leaf.getString("id"),0,FlexEditing.leaf("second","text")));
    }
    @Test void insertedColumnLeafSharesNeighborGrowAndRowPreservesProportions() {
        JSONObject neighbor=FlexEditing.leaf("a","text"); neighbor.put("grow",3);
        JSONObject column=JSONObject.of("id","col","type","column","children",JSONArray.of(neighbor));
        JSONObject added=FlexEditing.leaf("b","cards");
        FlexEditing.insertLeaf(column,"col",1,added);
        assertEquals(1.5,neighbor.getDoubleValue("grow")); assertEquals(1.5,added.getDoubleValue("grow"));
        JSONObject row=JSONObject.of("id","row","type","row","children",JSONArray.of(FlexEditing.leaf("left","text"),FlexEditing.leaf("right","text")),"ratios",JSONArray.of(20,80));
        FlexEditing.insertLeaf(row,"row",1,FlexEditing.leaf("middle","text"));
        assertEquals(JSONArray.of(10.0,10.0,80.0),row.getJSONArray("ratios"));
    }
    @Test void fixedUnlockClustersRowsSortsXAndKeepsEveryOriginalAbsoluteRect() {
        JSONObject left=JSONObject.of("id","left","text_style","body","rect",JSONObject.of("x",.05,"y",.4,"w",.3,"h",.3));
        JSONObject right=JSONObject.of("id","right","text_style","body","rect",JSONObject.of("x",.5,"y",.4,"w",.45,"h",.3));
        JSONObject title=JSONObject.of("id","title","text_style","title","rect",JSONObject.of("x",.05,"y",.05,"w",.9,"h",.15));
        JSONArray blocks=JSONArray.of(JSONObject.of("id","b-right","type","text","slot_id","right"),JSONObject.of("id","b-title","type","text","slot_id","title"),JSONObject.of("id","b-left","type","text","slot_id","left"));
        JSONObject tree=FlexEditing.fromFixed(JSONObject.of("slots",JSONArray.of(right,title,left)),blocks);
        JSONArray rows=tree.getJSONArray("children");
        assertEquals(2,rows.size());
        assertEquals("b-title",rows.getJSONObject(0).getJSONArray("children").getJSONObject(0).getString("block_id"));
        JSONArray leaves=rows.getJSONObject(1).getJSONArray("children");
        assertEquals("b-left",leaves.getJSONObject(0).getString("block_id"));
        for(String coordinate:java.util.List.of("x","y","w","h")) {
            assertEquals(left.getJSONObject("rect").getDoubleValue(coordinate),leaves.getJSONObject(0).getJSONObject("rect_override").getDoubleValue(coordinate),coordinate);
            assertEquals(right.getJSONObject("rect").getDoubleValue(coordinate),leaves.getJSONObject(1).getJSONObject("rect_override").getDoubleValue(coordinate),coordinate);
        }
        assertEquals(40,rows.getJSONObject(1).getJSONArray("ratios").getDoubleValue(0),1e-9);
    }
}
