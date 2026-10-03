package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.configuration.AipptProperties;
import com.ruoyi.aippt.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AipptThemeDefaultsTest {
    @Test void directoryExposesOriginalModelDefaultsAndRetainsActualThemeValues() {
        CatalogService catalog=new CatalogService(new AipptProperties());
        catalog.verifyManifest();
        JSONObject ivory=catalog.theme("ivory");
        assertEquals("#B4432B",ivory.getJSONObject("palette").getString("accent"));
        assertEquals("Segoe UI Emoji",ivory.getJSONObject("fonts").getJSONObject("emoji").getString("pptx_latin"));
        assertFalse(ivory.getJSONObject("text_styles").getJSONObject("body").getBooleanValue("italic"));
        JSONObject glow=ivory.getJSONArray("ambient").getJSONObject(0);
        assertEquals(.9,glow.getDoubleValue("cx"));
        assertEquals(.35,glow.getDoubleValue("strength"));
        assertTrue(glow.containsKey("scope"));assertNull(glow.get("scope"));
        assertFalse(glow.getBooleanValue("avoid_content"));
        JSONObject grid=ivory.getJSONArray("ambient").getJSONObject(1);
        assertEquals(4,grid.getIntValue("columns"));assertEquals(0,grid.getIntValue("rows"));
        assertTrue(grid.containsKey("area"));assertNull(grid.get("area"));
        catalog.verifyManifest(); // 输出适配没有修改共享目录字节。
    }
    @Test void missingAndOriginalDefaultEmojiFontsBothSaveWhileUnknownOrCustomFontsFail() throws Exception {
        AipptPersistenceFixture f=new AipptPersistenceFixture();
        JSONObject fonts=f.catalog.theme("ivory").getJSONObject("fonts");
        JSONObject omitted=AipptRules.copy(fonts); omitted.remove("emoji");
        f.service.theme(f.project,f.owner,"omitted",JSONObject.of("theme_revision",1,"overrides",JSONObject.of("fonts",omitted)));
        assertEquals(fonts,f.project().getJSONObject("theme_overrides").getJSONObject("fonts"));
        f.service.theme(f.project,f.owner,"full",JSONObject.of("theme_revision",2,"overrides",JSONObject.of("fonts",fonts)));
        JSONObject wrong=AipptRules.copy(fonts); wrong.getJSONObject("emoji").put("pptx_latin","Custom Font");
        assertThrows(AipptException.class,()->f.service.theme(f.project,f.owner,"wrong",JSONObject.of("theme_revision",3,"overrides",JSONObject.of("fonts",wrong))));
        JSONObject unknown=AipptRules.copy(fonts); unknown.put("unknown",null);
        assertThrows(AipptException.class,()->f.service.theme(f.project,f.owner,"unknown",JSONObject.of("theme_revision",3,"overrides",JSONObject.of("fonts",unknown))));
    }
}
