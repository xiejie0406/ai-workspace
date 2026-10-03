package com.ruoyi.aden.application.collection;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.aden.application.collection.AdenCollectionService.*;

class AdenCollectionExtractionTest {
    static Map<String,Object> payload(){
        return map("documentId","page-1","fields",map("title","采集样本","sourceUrl","https://item.jd.com/100001.html","sku","100001","parameters","材质：棉；材质：未知"),"blocks",List.of(),"images",List.of(),
                "extraction",map("protocolVersion",1,"documentId","page-1","pageFingerprint","a".repeat(64),"selectionRevision","9007199254740992","confirmationRevision","1","selectedSections",List.of("params"),"sections",new ArrayList<>(List.of(section())),
                "parameterRows",List.of(map("sectionId","params","sourceId","parameter:1","name","材质","value","棉","rawText","材质：棉"),map("sectionId","params","sourceId","parameter:1","name","材质","value","","unknownReason","原页未标注","rawText","材质：未知"))));
    }
    static Map<String,Object> section(){return map("sectionId","params","documentId","page-1","pageFingerprint","a".repeat(64),"selectionRevision","9007199254740992","rangeRevision","0","extractedRangeRevision","0","resultRevision","1","sourceIds",List.of("parameter:1"));}
    @Test void orderedDuplicateParametersKeepOriginalAndUnknownWithoutFloatLoss(){
        var result=AdenCollectionExtraction.validate(payload());
        assertEquals("9007199254740992",result.get("selectionRevision"));
        var rows=objects(result.get("parameterRows"));assertEquals(2,rows.size());assertEquals("材质",rows.get(0).get("name"));assertEquals("原页未标注",rows.get(1).get("unknownReason"));assertEquals(false,result.get("legacy"));
    }
    @Test void stalePageSelectionAndRangeAreRejected(){
        for(String key:List.of("documentId","pageFingerprint","selectionRevision","extractedRangeRevision")){
            var body=payload();var extraction=object(body.get("extraction"));var current=section();current.put(key,key.equals("pageFingerprint")?"b".repeat(64):key.equals("documentId")?"old-page":"2");extraction.put("sections",List.of(current));body.put("extraction",extraction);
            assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validate(body),key);
        }
    }
    @Test void repeatedSectionsSourcesUnknownSectionsAndNumericRevisionAreRejected(){
        var body=payload();var ex=object(body.get("extraction"));ex.put("selectedSections",List.of("params","params"));body.put("extraction",ex);var repeated=body;assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validate(repeated));
        for(Object invalid:List.of(1,"01","9223372036854775808")){
            body=payload();ex=object(body.get("extraction"));ex.put("confirmationRevision",invalid);body.put("extraction",ex);var input=body;assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validate(input));
        }
        body=payload();ex=object(body.get("extraction"));var section=section();section.put("sourceIds",List.of("a","a"));ex.put("sections",List.of(section));body.put("extraction",ex);var input=body;assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validate(input));
    }
    @Test void missingNeedsExplicitConfirmationAndCompleteCannotClaimMissing(){
        var body=payload();var ex=object(body.get("extraction"));var section=section();section.put("missingReason","未加载完整参数");ex.put("sections",List.of(section));body.put("extraction",ex);
        assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validate(body));section.put("missingConfirmed",true);ex.put("sections",List.of(section));body.put("extraction",ex);assertTrue(AdenCollectionExtraction.hasMissing(AdenCollectionExtraction.validate(body)));
        var complete=payload();ex=object(complete.get("extraction"));section=section();section.put("missingConfirmed",true);ex.put("sections",List.of(section));complete.put("extraction",ex);assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validate(complete));
    }
    @Test void everyMissingSectionNeedsItsOwnConfirmationAndAggregateFlagCannotReplaceIt(){
        var body=payload();var ex=object(body.get("extraction"));var params=section();params.put("missingReason","参数值不完整");params.put("missingConfirmed",true);
        var intro=section();intro.put("sectionId","intro");intro.put("sourceIds",List.of());intro.put("missingReason","介绍尚未加载");intro.put("missingConfirmed",true);
        ex.put("selectedSections",List.of("params","intro"));ex.put("sections",List.of(params,intro));body.put("extraction",ex);
        assertTrue(AdenCollectionExtraction.hasMissing(AdenCollectionExtraction.validate(body)));
        intro.remove("missingConfirmed");ex.put("sections",List.of(params,intro));body.put("extraction",ex);
        assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validate(body));
        intro.put("missingConfirmed",false);ex.put("sections",List.of(params,intro));body.put("extraction",ex);
        assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validate(body));
        ex.put("missingConfirmed",true);body.put("extraction",ex);
        assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validate(body));
    }
    @Test void missingFlagTypesAndBlankReasonAreRejected(){
        for(Object flag:List.of("true",1)){
            var body=payload();var ex=object(body.get("extraction"));var section=section();section.put("missingReason","参数未加载");section.put("missingConfirmed",flag);ex.put("sections",List.of(section));body.put("extraction",ex);
            assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validate(body));
        }
        var body=payload();var ex=object(body.get("extraction"));var section=section();section.put("missingReason"," ");section.put("missingConfirmed",true);ex.put("sections",List.of(section));body.put("extraction",ex);
        assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validate(body));
    }
    @Test void unsupportedActionsAndParametersOutsideTheirSourceAreRejected(){
        var body=payload();var ex=object(body.get("extraction"));ex.put("ranges",List.of(map("sectionId","params","sourceId","x","name","范围","mode","execute")));body.put("extraction",ex);assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validate(body));
        var bad=payload();ex=object(bad.get("extraction"));ex.put("parameterRows",List.of(map("sectionId","params","sourceId","outside","name","材质","value","棉")));bad.put("extraction",ex);assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validate(bad));
    }
    @Test void unselectedImagesAndContentCannotSneakIntoConfirmation(){
        var body=payload();body.put("images",List.of(map("group","MAIN","imageId","x")));assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validate(body));
        var other=payload();other.put("blocks",List.of(map("type","description","text","未选详情")));assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validate(other));
    }
    @Test void existingConsumerWithoutExtractionIsExplicitlyLegacy(){assertEquals(map("legacy",true),AdenCollectionExtraction.validate(map()));}
    @Test void repeatedStableSourceKeepsOneContentAndAllOwningSections(){
        var ex=map("sections",List.of(map("sectionId","intro","sourceIds",List.of("same")),map("sectionId","detail","sourceIds",List.of("same"))));
        var blocks=List.of(map("type","introduction","text","同一来源","sourceId","same","sectionIds",List.of("intro")),map("type","description","text","同一来源","sourceId","same","sectionIds",List.of("detail")));
        var normalized=AdenCollectionExtraction.normalizeBlocks(blocks,ex);assertEquals(1,normalized.size());assertEquals(List.of("intro","detail"),normalized.get(0).get("sectionIds"));
        assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.normalizeBlocks(List.of(blocks.get(0),map("type","description","text","冲突","sourceId","same","sectionIds",List.of("detail"))),ex));
    }
    @Test void sharedImageProvenanceIsAcceptedButCrossSectionSourcesAreRejected(){
        var extraction=map("sections",List.of(map("sectionId","main","sourceIds",List.of("image:1")),map("sectionId","gallery","sourceIds",List.of("image:1"))));
        var image=map("imageId","i1","group","MAIN","url","https://img10.360buyimg.com/sample.png","sourceId","image:1","sectionIds",List.of("main","gallery"));
        assertDoesNotThrow(()->AdenCollectionExtraction.validateImageSources(List.of(image),extraction));assertEquals(List.of("main","gallery"),image.get("sectionIds"));
        image.put("sourceId","outside");assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validateImageSources(List.of(image),extraction));
    }
    @Test void sameImageSourceKeepsOneResourceAndRejectsConflictingContent(){
        var extraction=map("sections",List.of(map("sectionId","main","sourceIds",List.of("image:1")),map("sectionId","gallery","sourceIds",List.of("image:1"))));
        var first=map("imageId","i1","sourceId","image:1","group","MAIN","url","https://img10.360buyimg.com/sample.png","width",800,"height",600,"sectionIds",List.of("main"));
        var second=map("imageId","i2","sourceId","image:1","group","GALLERY","url","https://img10.360buyimg.com/sample.png","width",800,"height",600,"sectionIds",List.of("gallery"));
        var normalized=AdenCollectionExtraction.validateImageSources(List.of(first,second),extraction);
        assertEquals(1,normalized.size());assertEquals("i1",normalized.get(0).get("imageId"));assertEquals(List.of("main","gallery"),normalized.get(0).get("sectionIds"));
        second.put("url","https://img10.360buyimg.com/different.png");assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validateImageSources(List.of(first,second),extraction));
        second.put("url",first.get("url"));second.put("width",400);assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validateImageSources(List.of(first,second),extraction));
    }
    @Test void legacyImagesKeepOriginalResourceIdentityAndDoNotClaimSectionSources(){
        var first=map("imageId","i1","sourceId","same","sectionIds",List.of("main"));var second=map("imageId","i2","sourceId","same","sectionIds",List.of("gallery"));
        var normalized=AdenCollectionExtraction.validateImageSources(List.of(first,second),map("legacy",true));
        assertEquals(2,normalized.size());assertEquals("i1",normalized.get(0).get("imageId"));assertEquals("i2",normalized.get(1).get("imageId"));assertFalse(first.containsKey("sourceId"));assertFalse(second.containsKey("sectionIds"));
    }
    @Test void excludedParameterCannotRemainInDisplayOrBlocksAndRawRowsKeepUnitsAndNotes(){
        var body=payload();var ex=object(body.get("extraction"));var rows=objects(ex.get("parameterRows"));
        rows.get(0).putAll(map("name","净重","value","12","unit","kg","rawText","净重：12 kg（含包装）"));rows.get(1).put("excluded",true);ex.put("parameterRows",rows);body.put("extraction",ex);
        var fields=object(body.get("fields"));fields.put("parameters","净重：12 kg（含包装）；材质：未知");body.put("fields",fields);
        assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validate(body));
        fields.put("parameters","净重：12 kg（含包装）");body.put("fields",fields);body.put("blocks",List.of(map("type","parameters","text","净重：12 kg（含包装）；材质：未知")));
        assertThrows(IllegalArgumentException.class,()->AdenCollectionExtraction.validate(body));
        body.put("blocks",List.of(map("type","parameters","text","净重：12 kg（含包装）")));
        var validated=objects(AdenCollectionExtraction.validate(body).get("parameterRows"));assertEquals("kg",validated.get(0).get("unit"));assertEquals("净重：12 kg（含包装）",validated.get(0).get("rawText"));assertEquals(true,validated.get(1).get("excluded"));assertEquals("原页未标注",validated.get(1).get("unknownReason"));
    }
}
