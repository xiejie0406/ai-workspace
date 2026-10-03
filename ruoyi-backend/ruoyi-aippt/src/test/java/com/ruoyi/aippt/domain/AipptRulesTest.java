package com.ruoyi.aippt.domain;
import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.application.TaskLeaseService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AipptRulesTest  {
    private JSONObject slide()  {
        return AipptRules.blank();
    }
    @Test void allEightDefaultBlocksSatisfyTheSharedContentRules()  {
        for(String type:List.of("text","bullets","image","chart","table","kpi","cards","callout")) assertDoesNotThrow(()->AipptRules.block(AipptRules.defaultBlock(type)),type);
    }
    @Test void densityChangesFingerprintButThemeAndPageCountDoNot()  {
        JSONObject project = JSONObject.of("title", "事实", "audience", "同事", "tone", "plain", "content_density", "medium", "page_count", 10, "theme_id", "ivory");
        JSONObject source = JSONObject.of("id", "stable-source", "parse_status", "ready", "parser_version", "1", "sections", JSONArray.of(JSONObject.of("id", "stable-ref", "text", "源文字")));
        String original = AipptRules.fingerprint(project, List.of(source));
        project.put("theme_id", "obsidian");
        project.put("page_count", 20);
        assertEquals(original, AipptRules.fingerprint(project, List.of(source)));
        project.put("content_density", "detailed");
        assertNotEquals(original, AipptRules.fingerprint(project, List.of(source)));
    }
    @Test void fingerprintCanonicalizesNestedPropertyOrderButTracksSourceText()  {
        JSONObject project=JSONObject.of("title","测试","audience","同事","tone","plain","content_density","medium");
        JSONObject a=JSONObject.of("id","s","parse_status","ready","sections",JSONArray.of(JSONObject.of("id","r","text","正文","locator",JSONObject.of("page",1,"offset",2))));
        JSONObject b=JSONObject.of("sections",JSONArray.of(JSONObject.of("locator",JSONObject.of("offset",2,"page",1),"text","正文","id","r")),"parse_status","ready","id","s");
        assertEquals(AipptRules.fingerprint(project,List.of(a)),AipptRules.fingerprint(project,List.of(b)));
        b.getJSONArray("sections").getJSONObject(0).put("text","修改正文");
        assertNotEquals(AipptRules.fingerprint(project,List.of(a)),AipptRules.fingerprint(project,List.of(b)));
    }
    @Test void snapshotsCannotUnlockUnchangedManualBlocks()  {
        JSONObject before = slide();
        JSONObject next = AipptRules.copy(before);
        next.getJSONArray("blocks").getJSONObject(0).put("locked", false);
        AipptRules.protect(before, next, false);
        assertTrue(next.getJSONArray("blocks").getJSONObject(0).getBooleanValue("locked"));
    }
    @Test void aiCannotDeleteOrOverwriteManualBlocks()  {
        JSONObject before = slide(), next = AipptRules.copy(before);
        next.getJSONArray("blocks").getJSONObject(0).put("text", "模型覆盖");
        assertThrows(AipptException.class, () -> AipptRules.protect(before, next, true));
        next.getJSONArray("blocks").clear();
        assertThrows(AipptException.class, () -> AipptRules.protect(before, next, true));
    }
    @Test void treeMustHaveTheSameUniqueBlockIds()  {
        JSONObject slide = slide();
        AipptRules.slide(slide);
        slide.getJSONObject("layout_tree").getJSONArray("children").getJSONObject(0).put("block_id", "unknown");
        assertThrows(AipptException.class, () -> AipptRules.slide(slide));
    }
    @Test void chartRejectsDimensionMismatchAndNonFiniteValues()  {
        JSONObject chart = JSONObject.of("id", "b", "slot_id", "chart", "type", "chart", "chart_type", "line", "categories", JSONArray.of("甲", "乙"), "series", JSONArray.of(JSONObject.of("name", "值", "values", JSONArray.of(1, 2))));
        AipptRules.block(chart);
        chart.getJSONArray("series").getJSONObject(0).put("values", JSONArray.of(1));
        assertThrows(AipptException.class, () -> AipptRules.block(chart));
        chart.getJSONArray("series").getJSONObject(0).put("values", JSONArray.of(1, Double.NaN));
        assertThrows(AipptException.class, () -> AipptRules.block(chart));
    }
    @Test void imageRejectsRemoteUrlsAndUnknownFields()  {
        JSONObject image = JSONObject.of("id", "i", "slot_id", "image", "type", "image", "source", "placeholder", "alt", "图示", "url", null);
        AipptRules.block(image);
        image.put("url", "http://127.0.0.1/");
        assertThrows(AipptException.class, () -> AipptRules.block(image));
        image.put("url", null);
        image.put("owner", 1);
        assertThrows(AipptException.class, () -> AipptRules.block(image));
    }
    @Test void leaseRequiresMatchingAttemptFenceSecretAndUnexpiredState()  {
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        JSONObject task = JSONObject.of("status", "running", "attempt", 2, "fencing_token", 7, "lease_token", "secret", "lease_until", now.plusSeconds(30).toString());
        JSONObject lease = JSONObject.of("attempt", 2, "fencingToken", 7, "leaseToken", "secret");
        TaskLeaseService.validateLease(task, lease, now);
        lease.put("attempt", 1);
        assertThrows(AipptException.class, () -> TaskLeaseService.validateLease(task, lease, now));
        lease.put("attempt", 2);
        task.put("fencing_token", 8);
        assertThrows(AipptException.class, () -> TaskLeaseService.validateLease(task, lease, now));
        task.put("fencing_token", 7);
        task.put("lease_until", now.minusSeconds(1).toString());
        assertThrows(AipptException.class, () -> TaskLeaseService.validateLease(task, lease, now));
    }
    @Test void lockedBlockContentEqualityIgnoresJsonPropertyOrder()  {
        JSONObject before=slide(),after=AipptRules.copy(before);
        JSONObject original=before.getJSONArray("blocks").getJSONObject(0),reordered=new JSONObject();
        java.util.List<String> keys=new java.util.ArrayList<>(original.keySet());
        java.util.Collections.reverse(keys);
        keys.forEach(key->reordered.put(key,original.get(key)));
        after.getJSONArray("blocks").set(0,reordered);
        assertDoesNotThrow(()->AipptRules.protect(before,after,true));
        assertTrue(reordered.getBooleanValue("locked"));
    }
    @Test void unchangedLockedChartAcceptsIntegerAndFloatWireRepresentations()  {
        JSONObject before=slide();
        before.put("blocks",JSONArray.of(AipptRules.defaultBlock("chart")));
        before.getJSONArray("blocks").getJSONObject(0).getJSONArray("series").getJSONObject(0).put("values",JSONArray.of(1,2));
        JSONObject after=AipptRules.copy(before);
        after.getJSONArray("blocks").getJSONObject(0).getJSONArray("series").getJSONObject(0).put("values",JSONArray.of(1.0,2.00));
        assertDoesNotThrow(()->AipptRules.protect(before,after,true));
        assertTrue(after.getJSONArray("blocks").getJSONObject(0).getBooleanValue("locked"));
        assertDoesNotThrow(()->AipptRules.protect(after,before,true));
    }
    @Test void lockedChartRejectsActualNumericChangesAndNonFiniteValues()  {
        JSONObject before=slide();
        before.put("blocks",JSONArray.of(AipptRules.defaultBlock("chart")));
        before.getJSONArray("blocks").getJSONObject(0).getJSONArray("series").getJSONObject(0).put("values",JSONArray.of(1,2));
        JSONObject changed=AipptRules.copy(before);
        changed.getJSONArray("blocks").getJSONObject(0).getJSONArray("series").getJSONObject(0).put("values",JSONArray.of(2.0,2.0));
        assertThrows(AipptException.class,()->AipptRules.protect(before,changed,true));
        changed.getJSONArray("blocks").getJSONObject(0).getJSONArray("series").getJSONObject(0).put("values",JSONArray.of(Double.NaN,2.0));
        assertThrows(AipptException.class,()->AipptRules.protect(before,changed,true));
    }
    @Test void optionalNullableContentCanBeOmittedWithoutChangingLockedContent()  {
        for(String type:List.of("chart","image","kpi","callout","cards")) {
            JSONObject before=slide();
            JSONObject original=AipptRules.defaultBlock(type);
            before.put("blocks",JSONArray.of(original));
            JSONObject after=AipptRules.copy(before);
            JSONObject omitted=after.getJSONArray("blocks").getJSONObject(0);
            switch(type) {
                case "chart"->omitted.remove("unit");
                case "image"->{ omitted.remove("url"); omitted.remove("credit"); }
                case "kpi"->omitted.remove("note");
                case "callout"->omitted.remove("icon");
                case "cards"->{ for(Object item:omitted.getJSONArray("items"))((JSONObject)item).remove("icon"); }
            }
            String originalContent=JSON.toJSONString(original,JSONWriter.Feature.WriteMapNullValue);
            String copiedContent=JSON.toJSONString(after.getJSONArray("blocks").getJSONObject(0),JSONWriter.Feature.WriteMapNullValue);
            assertDoesNotThrow(()->AipptRules.protect(before,after,true),type);
            assertDoesNotThrow(()->AipptRules.protect(after,before,true),type);
            assertEquals(originalContent,JSON.toJSONString(original,JSONWriter.Feature.WriteMapNullValue));
            assertEquals(copiedContent,JSON.toJSONString(after.getJSONArray("blocks").getJSONObject(0),JSONWriter.Feature.WriteMapNullValue));
        }
    }
    @Test void lockComparisonKeepsOtherFieldsAndNonNullContentStrict()  {
        JSONObject before=slide();
        before.put("blocks",JSONArray.of(AipptRules.defaultBlock("chart")));
        JSONObject changedUnit=AipptRules.copy(before);
        changedUnit.getJSONArray("blocks").getJSONObject(0).put("unit","");
        assertThrows(AipptException.class,()->AipptRules.protect(before,changedUnit,true));
        changedUnit.getJSONArray("blocks").getJSONObject(0).put("unit","万元");
        assertThrows(AipptException.class,()->AipptRules.protect(before,changedUnit,true));
        JSONObject withUnknown=AipptRules.copy(before);
        withUnknown.getJSONArray("blocks").getJSONObject(0).put("unknown",null);
        assertThrows(AipptException.class,()->AipptRules.protect(before,withUnknown,true));
        JSONObject textBefore=slide();
        textBefore.getJSONArray("blocks").getJSONObject(0).put("text",null);
        JSONObject textAfter=AipptRules.copy(textBefore);
        textAfter.getJSONArray("blocks").getJSONObject(0).remove("text");
        assertThrows(AipptException.class,()->AipptRules.protect(textBefore,textAfter,true));
    }
    @Test void lockComparisonNormalizesNestedMapsCollectionsAndNativeArrays()  {
        JSONObject before=slide();
        JSONObject chart=AipptRules.defaultBlock("chart");
        chart.put("series",List.of(java.util.Map.of("name","系列","values",new int[]{1,2})));
        before.put("blocks",JSONArray.of(chart));
        JSONObject after=AipptRules.copy(before);
        after.getJSONArray("blocks").getJSONObject(0).getJSONArray("series").getJSONObject(0).put("values",new double[]{1.0,2.0});
        assertDoesNotThrow(()->AipptRules.protect(before,after,true));
        assertDoesNotThrow(()->AipptRules.protect(after,before,true));
        after.getJSONArray("blocks").getJSONObject(0).getJSONArray("series").getJSONObject(0).put("values",new double[]{2.0,1.0});
        assertThrows(AipptException.class,()->AipptRules.protect(before,after,true));
        assertInstanceOf(List.class,chart.get("series"));
        assertArrayEquals(new int[]{1,2},(int[])((java.util.Map<?,?>)((List<?>)chart.get("series")).get(0)).get("values"));
    }
    @Test void styleUsesOnlySharedKeysAndColorTokens()  {
        AipptRules.style(JSONObject.of("size_pt",24,"color","accent","weight",700));
        assertThrows(AipptException.class,()->AipptRules.style(JSONObject.of("font_size",24)));
        assertThrows(AipptException.class,()->AipptRules.style(JSONObject.of("color","text")));
    }
    @Test void rectOverrideMustStayWithinCanvas()  {
        JSONObject slide = slide();
        JSONObject leaf = slide.getJSONObject("layout_tree").getJSONArray("children").getJSONObject(0);
        leaf.put("rect_override", JSONObject.of("x", .1, "y", .1, "w", .8, "h", .8));
        AipptRules.slide(slide);
        leaf.getJSONObject("rect_override").put("w", .95);
        assertThrows(AipptException.class, () -> AipptRules.slide(slide));
    }
}
