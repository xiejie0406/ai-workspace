package com.ruoyi.aippt.application;
import com.ruoyi.aippt.configuration.AipptProperties;
import com.ruoyi.aippt.domain.AipptException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class AipptCatalogTest  {
    @TempDir Path temp;
    @Test void packagedCatalogMatchesAllManifestBytes()  {
        CatalogService catalog=new CatalogService(new AipptProperties());
        catalog.verifyManifest();
        assertEquals(4,catalog.directory("themes").size());
        assertEquals(11,catalog.directory("layouts").size());
    }
    @Test void optionalSlotDoesNotStealTheOnlyBlockNeededByRequiredSlot()  {
        CatalogService catalog=new CatalogService(new AipptProperties());
        var layout=com.alibaba.fastjson2.JSONObject.of("id","fixture","slots",com.alibaba.fastjson2.JSONArray.of(com.alibaba.fastjson2.JSONObject.of("id","optional","accepts",com.alibaba.fastjson2.JSONArray.of("text"),"required",false),com.alibaba.fastjson2.JSONObject.of("id","required","accepts",com.alibaba.fastjson2.JSONArray.of("text"),"required",true)));
        var single=com.ruoyi.aippt.domain.AipptRules.blank();
        single.put("blocks",com.alibaba.fastjson2.JSONArray.of(com.ruoyi.aippt.domain.AipptRules.defaultBlock("text")));
        var result=catalog.compatible(single,layout);
        assertTrue(result.getBooleanValue("compatible"));
        assertEquals("required",result.getJSONArray("assignments").getJSONObject(0).getString("slot_id"));
    }
    @Test void sameVersionWithChangedBytesCannotStart() throws Exception  {
        Path catalog=Files.createDirectory(temp.resolve("catalog"));
        Files.writeString(catalog.resolve("test.json"),"{}");
        Files.writeString(temp.resolve("catalog-manifest.json"),"{\"catalogVersion\":\"aippt-1-90c52568ea7d\",\"files\":[{\"path\":\"test.json\",\"size\":2,\"sha256\":\"wrong\"}]}");
        AipptProperties props=new AipptProperties();
        props.setCatalogPath(catalog.toString());
        assertThrows(AipptException.class,()->new CatalogService(props).verifyManifest());
    }
}
