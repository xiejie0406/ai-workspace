package com.ruoyi.aippt.domain;

import com.alibaba.fastjson2.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AipptDefaultContentTest {
    @Test void allEightFactoriesKeepOriginalContentAndCentralManualProtection() {
        assertEquals("新文本",AipptRules.defaultBlock("text").getString("text"));
        assertEquals(JSONArray.of("要点"),AipptRules.defaultBlock("bullets").getJSONArray("items"));
        assertEquals("图片",AipptRules.defaultBlock("image").getString("alt"));
        JSONObject chart=AipptRules.defaultBlock("chart");
        assertEquals("bar",chart.getString("chart_type"));
        assertEquals("系列1",chart.getJSONArray("series").getJSONObject(0).getString("name"));
        assertEquals(JSONArray.of(3.0,5.0),chart.getJSONArray("series").getJSONObject(0).getJSONArray("values"));
        JSONObject table=AipptRules.defaultBlock("table");
        assertEquals(JSONArray.of("列1","列2"),table.getJSONArray("header"));
        assertEquals(JSONArray.of("",""),table.getJSONArray("rows").getJSONArray(0));
        assertEquals("—",AipptRules.defaultBlock("kpi").getString("value"));
        assertEquals(2,AipptRules.defaultBlock("cards").getJSONArray("items").size());
        assertEquals("要点二",AipptRules.defaultBlock("cards").getJSONArray("items").getJSONObject(1).getString("title"));
        assertEquals("补充说明或数据来源",AipptRules.defaultBlock("callout").getString("text"));
        for(String type:AipptRules.TYPES) {
            JSONObject block=AipptRules.defaultBlock(type);
            assertTrue(block.getBooleanValue("locked"));
            assertEquals(block.get("id"),block.get("slot_id"));
            assertDoesNotThrow(()->AipptRules.block(block));
        }
    }
    @Test void blankPageHasTitleAndBulletsWithOriginalGrowAndTextStyles() {
        JSONObject page=AipptRules.blank();
        AipptRules.slide(page);
        JSONArray blocks=page.getJSONArray("blocks"),leaves=page.getJSONObject("layout_tree").getJSONArray("children");
        assertEquals(2,blocks.size());
        assertEquals("新页面",blocks.getJSONObject(0).getString("text"));
        assertEquals("bullets",blocks.getJSONObject(1).getString("type"));
        assertEquals(.45,leaves.getJSONObject(0).getDoubleValue("grow"));
        assertEquals(1.5,leaves.getJSONObject(1).getDoubleValue("grow"));
        assertEquals("title",leaves.getJSONObject(0).getString("text_style"));
        assertEquals("bullet",leaves.getJSONObject(1).getString("text_style"));
        assertEquals(blocks.getJSONObject(0).get("id"),leaves.getJSONObject(0).get("block_id"));
        assertEquals(blocks.getJSONObject(1).get("id"),leaves.getJSONObject(1).get("block_id"));
    }
}
