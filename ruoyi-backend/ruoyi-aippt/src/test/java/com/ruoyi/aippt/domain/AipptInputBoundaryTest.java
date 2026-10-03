package com.ruoyi.aippt.domain;

import com.alibaba.fastjson2.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AipptInputBoundaryTest {
    private JSONObject slide() { return AipptRules.blank(); }
    private JSONArray pages() {
        JSONArray pages=new JSONArray();
        for(int i=0;i<5;i++)pages.add(JSONObject.of("id",UUID.randomUUID().toString(),"title","提纲","objective","目的","key_points",JSONArray.of("要点1","要点2"),"layout_id","bullets","source_refs",new JSONArray(),"page_role","content","visual",null));
        return pages;
    }
    @Test void wrongContentTypesAreRejectedWithoutStringOrJsonCoercion() {
        JSONObject text=AipptRules.defaultBlock("text");
        text.put("text",123);
        assertThrows(AipptException.class,()->AipptRules.block(text));
        JSONObject bullets=AipptRules.defaultBlock("bullets");
        bullets.put("items",JSONArray.of(123));
        assertThrows(AipptException.class,()->AipptRules.block(bullets));
        bullets.put("items","[\"要点\"]");
        assertThrows(AipptException.class,()->AipptRules.block(bullets));
        JSONObject cards=AipptRules.defaultBlock("cards");
        cards.put("items",JSONArray.of("伪对象"));
        assertThrows(AipptException.class,()->AipptRules.block(cards));
    }
    @Test void onlyKnownTreeFieldsBooleanFlagsAndContainerRootsAreAccepted() {
        JSONObject slide=slide(),root=slide.getJSONObject("layout_tree"),leaf=root.getJSONArray("children").getJSONObject(0);
        leaf.put("unknown",null);
        assertThrows(AipptException.class,()->AipptRules.slide(slide));
        leaf.remove("unknown"); leaf.put("bleed","false");
        assertThrows(AipptException.class,()->AipptRules.slide(slide));
        leaf.remove("bleed"); root.put("preset","invalid");
        assertThrows(AipptException.class,()->AipptRules.slide(slide));
        root.remove("preset"); assertDoesNotThrow(()->AipptRules.slide(slide));
        slide.put("layout_tree",leaf);
        assertThrows(AipptException.class,()->AipptRules.slide(slide));
    }
    @Test void outlineRejectsMalformedUuidNumericTextAndUnknownNullFields() {
        JSONArray pages=pages();
        pages.getJSONObject(0).put("id","not-a-uuid");
        assertThrows(AipptException.class,()->AipptRules.outline(pages,Set.of()));
        pages.getJSONObject(0).put("id",UUID.randomUUID().toString());
        pages.getJSONObject(0).put("key_points",JSONArray.of(123,"合法要点"));
        assertThrows(AipptException.class,()->AipptRules.outline(pages,Set.of()));
        pages.getJSONObject(0).put("key_points",JSONArray.of("要点1","要点2"));
        pages.getJSONObject(0).put("unknown",null);
        assertThrows(AipptException.class,()->AipptRules.outline(pages,Set.of()));
    }
    @Test void originalExplicitDefaultsAndNullableVisualArePreserved() {
        JSONArray pages=pages();
        JSONObject page=pages.getJSONObject(0);
        page.remove("id"); page.remove("source_refs"); page.remove("page_role");
        AipptRules.outline(pages,Set.of());
        assertEquals("content",page.getString("page_role"));
        assertTrue(page.getJSONArray("source_refs").isEmpty());
        assertDoesNotThrow(()->UUID.fromString(page.getString("id")));
        assertNull(page.get("visual"));
    }
    @Test void revisionsAndPageCountCannotBeTruncatedOrParsedFromStrings() {
        JSONObject current=JSONObject.of("revision",1);
        for(Object value:List.of(1.5,"1",true))assertThrows(AipptException.class,()->AipptRules.expected(current,JSONObject.of("revision",value),"revision"));
        assertDoesNotThrow(()->AipptRules.expected(current,JSONObject.of("revision",1.0),"revision"));
        JSONObject project=JSONObject.of("title","项目","audience",null,"tone","professional","content_density","medium","layout_mode","flex","page_count",5.5);
        assertThrows(AipptException.class,()->AipptRules.project(project,true));
        project.put("page_count",5);
        assertDoesNotThrow(()->AipptRules.project(project,true));
    }
}
