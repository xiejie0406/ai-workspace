package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.domain.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class AipptNullPersistenceTest {
    private AipptPersistenceFixture f;
    @BeforeEach void setup() throws Exception { f=new AipptPersistenceFixture(); }
    @Test void explicitNullClearsEachOriginalNullableContentFieldThroughMyBatis() {
        for(String type:java.util.List.of("kpi","chart","callout","cards","image")) {
            JSONObject block=AipptRules.defaultBlock(type);
            String key=java.util.Map.of("kpi","note","chart","unit","callout","icon","image","credit","cards","icon").get(type);
            JSONObject owner="cards".equals(type)?block.getJSONArray("items").getJSONObject(0):block;
            owner.put(key,"原非空内容");
            JSONObject slide=f.slide(JSONArray.of(block));
            JSONObject body=AipptRules.copy(block);
            JSONObject next="cards".equals(type)?body.getJSONArray("items").getJSONObject(0):body;
            next.put(key,null);
            body.put("revision",slide.getLongValue("revision"));
            f.service.edit(f.project,slide.getString("id"),f.owner,"clear-"+type,"content",block.getString("id"),body);
            JSONObject saved=f.db.get("slide",slide.getString("id")).getJSONArray("blocks").getJSONObject(0);
            JSONObject target="cards".equals(type)?saved.getJSONArray("items").getJSONObject(0):saved;
            assertTrue(target.containsKey(key),type);
            assertNull(target.get(key),type);
            assertTrue(saved.getBooleanValue("locked"));
        }
    }
    @Test void absentAndExplicitNullCannotShareAnIdempotencyKeyAndAudienceMayBeCleared() {
        JSONObject body=JSONObject.of("input_revision",1,"audience","业务负责人");
        f.service.update(f.project,f.owner,"audience",body);
        JSONObject absent=JSONObject.of("input_revision",2,"title","修改名称");
        f.service.update(f.project,f.owner,"same",absent);
        JSONObject cleared=AipptRules.copy(absent);
        cleared.put("audience",null);
        assertThrows(AipptException.class,()->f.service.update(f.project,f.owner,"same",cleared));
        f.service.update(f.project,f.owner,"clear",JSONObject.of("input_revision",3,"audience",null));
        assertTrue(f.project().containsKey("audience"));
        assertNull(f.project().get("audience"));
    }
    @Test void themeOmittedOverridesAreKeptExplicitNullResetsAndNullableChildrenAreValid() {
        JSONObject overrides=JSONObject.of("palette",JSONObject.of("accent","#112233","ink",null),"shape",JSONObject.of("radius_pt",null,"bullet_marker",null),"text_styles",JSONObject.of("body",JSONObject.of("size_pt",null)));
        f.service.theme(f.project,f.owner,"set",JSONObject.of("theme_revision",1,"overrides",overrides));
        f.service.theme(f.project,f.owner,"same",JSONObject.of("theme_revision",2,"theme_id","ivory"));
        assertEquals(overrides,f.project().getJSONObject("theme_overrides"));
        f.service.theme(f.project,f.owner,"reset",JSONObject.of("theme_revision",3,"overrides",null));
        assertTrue(f.project().containsKey("theme_overrides"));
        assertNull(f.project().get("theme_overrides"));
        assertThrows(AipptException.class,()->f.service.theme(f.project,f.owner,"unknown",JSONObject.of("theme_revision",4,"overrides",JSONObject.of("palette",JSONObject.of("unknown",null)))));
    }
    @Test void sourceFileMetadataComesFromRegisteredAssetNotUserBody() {
        JSONObject asset=f.service.record(f.project,f.owner,"ready");
        asset.putAll(JSONObject.of("role","source","filename","材料.docx","mime","application/vnd.openxmlformats-officedocument.wordprocessingml.document","size",123,"sha256","a".repeat(64)));
        f.db.insert("asset",asset);
        JSONObject source=f.service.addSource(f.project,f.owner,"source",JSONObject.of("input_revision",1,"kind","document","asset_id",asset.getString("id"),"content_type","forged","size_bytes",999));
        JSONObject saved=f.db.get("source",source.getString("id"));
        assertEquals(asset.get("mime"),saved.get("content_type"));
        assertEquals(123,saved.getLongValue("size_bytes"));
        assertEquals(asset.get("sha256"),saved.get("sha256"));
    }
}
