package com.ruoyi.aippt.domain;
import com.alibaba.fastjson2.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProposalOperationsTest  {
    private JSONObject unlocked()  {
        JSONObject slide=AipptRules.blank();
        // 操作测试使用显式单块夹具，产品空白页另有两块默认结构回归。
        slide.getJSONArray("blocks").remove(1);
        slide.getJSONObject("layout_tree").getJSONArray("children").remove(1);
        slide.getJSONArray("blocks").getJSONObject(0).put("slot_id","title");
        slide.getJSONArray("blocks").getJSONObject(0).put("locked",false);
        return slide;
    }
    @Test void replacementKeepsIdSlotAndStyleThenUndoRestoresContent()  {
        JSONObject slide=unlocked(),block=slide.getJSONArray("blocks").getJSONObject(0);
        block.put("style",JSONObject.of("size_pt",24));
        String id=block.getString("id");
        JSONObject op=JSONObject.of("op","replace","block_id",id,"before",JSONObject.of("block_id",id,"type","text","text","新页面"),"after",JSONObject.of("block_id",id,"type","text","text","改稿"));
        ProposalOperations.apply(slide,op,false);
        AipptRules.slide(slide);
        assertEquals(id,slide.getJSONArray("blocks").getJSONObject(0).getString("id"));
        assertEquals("title",slide.getJSONArray("blocks").getJSONObject(0).getString("slot_id"));
        assertEquals(24,slide.getJSONArray("blocks").getJSONObject(0).getJSONObject("style").getIntValue("size_pt"));
        ProposalOperations.apply(slide,op,true);
        assertEquals("新页面",slide.getJSONArray("blocks").getJSONObject(0).getString("text"));
    }
    @Test void independentAddsAndUndoDoNotCopyTheWholeCandidateTree()  {
        JSONObject slide=unlocked();
        String anchor=slide.getJSONArray("blocks").getJSONObject(0).getString("id");
        JSONObject first=JSONObject.of("op","add","block_id","new-a","after_block_id",anchor,"before",null,"after",JSONObject.of("id","new-a","type","text","text","甲"));
        JSONObject second=JSONObject.of("op","add","block_id","new-b","after_block_id",anchor,"before",null,"after",JSONObject.of("id","new-b","type","text","text","乙"));
        ProposalOperations.apply(slide,first,false);
        AipptRules.slide(slide);
        assertEquals(2,slide.getJSONArray("blocks").size());
        ProposalOperations.apply(slide,second,false);
        AipptRules.slide(slide);
        ProposalOperations.apply(slide,first,true);
        AipptRules.slide(slide);
        assertTrue(slide.getJSONArray("blocks").stream().map(JSONObject.class::cast).anyMatch(b->"new-b".equals(b.getString("id"))));
        assertEquals(2,slide.getJSONArray("blocks").size());
    }
    @Test void fixedPagesCannotAcceptStructuralOperations()  {
        JSONObject slide=unlocked();
        slide.put("layout_mode","fixed");
        JSONObject op=JSONObject.of("op","add","block_id","new","after",JSONObject.of("type","text","text","新增"));
        assertThrows(AipptException.class,()->ProposalOperations.apply(slide,op,false));
    }
    @Test void deletingOneLeafPreservesUnrelatedRatiosAndIntentionalSpacer()  {
        JSONObject slide=unlocked();
        JSONObject first=slide.getJSONArray("blocks").getJSONObject(0);
        JSONObject second=AipptRules.defaultBlock("text");
        second.put("locked",false);
        JSONObject third=AipptRules.defaultBlock("text");
        third.put("locked",false);
        slide.getJSONArray("blocks").addAll(java.util.List.of(second,third));
        JSONObject spacer=JSONObject.of("id","spacer","type","column","children",new JSONArray());
        JSONObject target=JSONObject.of("id","target","type","row","children",JSONArray.of(leaf(first),leaf(second)),"ratios",JSONArray.of(2,3));
        JSONObject sibling=JSONObject.of("id","sibling","type","row","children",JSONArray.of(leaf(third),spacer),"ratios",JSONArray.of(4,1));
        slide.put("layout_tree",JSONObject.of("id","root","type","column","children",JSONArray.of(target,sibling)));
        ProposalOperations.apply(slide,JSONObject.of("op","delete","block_id",first.getString("id"),"before",first,"after",null),false);
        assertEquals(JSONArray.of(3),target.getJSONArray("ratios"));
        assertEquals(JSONArray.of(4,1),sibling.getJSONArray("ratios"));
        assertSame(spacer,sibling.getJSONArray("children").getJSONObject(1));
        AipptRules.slide(slide);
    }
    @Test void changeTypeResetsTextStyleAndStructuralOverride()  {
        JSONObject slide=unlocked(),block=slide.getJSONArray("blocks").getJSONObject(0);
        JSONObject leaf=slide.getJSONObject("layout_tree").getJSONArray("children").getJSONObject(0);
        leaf.put("rect_override",JSONObject.of("x",0,"y",0,"w",1,"h",1));
        leaf.put("text_style","title");
        ProposalOperations.apply(slide,JSONObject.of("op","change_type","block_id",block.getString("id"),"before",block,"after",JSONObject.of("type","bullets","items",JSONArray.of("要点"))),false);
        assertEquals("bullet",leaf.getString("text_style"));
        assertFalse(leaf.containsKey("rect_override"));
        AipptRules.slide(slide);
    }
    private JSONObject leaf(JSONObject block)  {
        return JSONObject.of("id",block.getString("id")+"-leaf","type","block","block_id",block.getString("id"));
    }
}
