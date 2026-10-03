package com.ruoyi.aipicture.service;

import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.ruoyi.aipicture.config.AiPictureProperties;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.persistence.AiPictureRepository;
import com.ruoyi.aipicture.tool.*;

class EvaluationAssessmentTest {
    final long owner=1;
    final String source=Json.id(),input=Json.id(),output=Json.id(),expected=Json.id(),mask=Json.id();
    AiPictureRepository db;AssetService assets;ToolRegistry registry;Map<String,Object> run,document;
    @BeforeEach void prepare() throws Exception {
        db=mock(AiPictureRepository.class);assets=mock(AssetService.class);registry=new ToolRegistry(new AiPictureProperties());
        document=DocumentCommandService.documentOf(Json.of("id",input,"width",80,"height",64));
        run=Json.of("id",source,"user_id",owner,"tool","generate_image","status","succeeded","params",Json.of("ratio","1:1"),"input_snapshot",Json.of("assets",List.of(Json.of("id",input))),"result",Json.of("asset_ids",List.of(output)),"provider",Json.of("image","dashscope","offline_test",false,"true_super_resolution",false));
        when(db.owned("aip_run",source,owner,false)).thenReturn(run);
        when(assets.record(eq(owner),anyString())).thenAnswer(call->Json.of("id",call.getArgument(1),"width",80,"height",64,"status","ready"));
    }
    Map<String,Object> item(String suite) {return Json.of("id","独立人工样本","task","assess_result","suite",suite,"source_run_id",source,"output_asset_id",output,"thresholds",Map.of(),"human_review",Json.of("status","passed","notes","已逐项核对实际产物与独立预期"));}
    Map<String,Object> prepare(Map<String,Object> item) {return Json.map(EvaluationAssessment.prepare(owner,List.of(item),db,assets,registry).get(0));}
    @Test void formalOutputFreezesProvenanceWithoutDispatchOrMutatingClient() {
        var raw=item("generation");var result=prepare(raw);assertFalse(raw.containsKey("assessment"));var frozen=Json.map(result.get("assessment"));
        assertEquals("generate_image",frozen.get("source_tool"));assertEquals(false,Json.map(frozen.get("source_provider")).get("offline_test"));assertEquals(Set.of(input,output),new HashSet<>(Json.list(frozen.get("asset_ids"))));
        verify(db,never()).execute(anyString(),anyMap());verify(assets).record(owner,input);verify(assets).record(owner,output);
    }
    @Test void rejectsForgedServerSnapshotBeforeReadingRun() {
        for(String key:List.of("assessment","provider","document","input_snapshot","mask_asset_id","path")) {var raw=item("generation");raw.put(key,Map.of());assertThrows(AiPictureException.class,()->prepare(raw));}
        verify(db,never()).owned(anyString(),anyString(),anyLong(),anyBoolean());
    }
    @Test void otherOwnerCannotReadSourceOrUseItsOutput() {
        when(db.owned("aip_run",source,2,false)).thenThrow(AiPictureException.missing());assertEquals(404,assertThrows(AiPictureException.class,()->EvaluationAssessment.prepare(2,List.of(item("generation")),db,assets,registry)).status());
        verify(assets,never()).record(eq(2L),anyString());var raw=item("generation");raw.put("output_asset_id",expected);assertThrows(AiPictureException.class,()->prepare(raw));
    }
    @Test void unknownFailedAndWrongToolsCannotBeReissuedAsAssessment() {
        for(String state:List.of("queued","running","failed","cancelled","external_result_unknown","partial_success")) {run.put("status",state);assertThrows(AiPictureException.class,()->prepare(item("generation")));}
        run.put("status","succeeded");run.put("tool","resize_layer");assertThrows(AiPictureException.class,()->prepare(item("generation")));verify(db,never()).execute(anyString(),anyMap());
    }
    @Test void samRequiresPointSelectionAndIndependentMask() {
        run.put("tool","selection");run.put("result",Json.of("asset_ids",List.of(mask),"mask_asset_id",mask));run.put("params",Json.of("strokes",List.of()));var raw=item("sam");raw.remove("output_asset_id");raw.put("expect_asset_id",expected);
        assertThrows(AiPictureException.class,()->prepare(raw));run.put("params",Json.of("points",List.of(Json.of("x",0.5,"y",0.5))));assertEquals(mask,prepare(raw).get("output_asset_id"));
        raw.remove("expect_asset_id");assertThrows(AiPictureException.class,()->prepare(raw));
    }
    @Test void regionFreezesBothDocumentsAndOriginalMask() {
        run.put("tool","erase_region");run.put("params",Json.of("mask_asset_id",mask));run.put("input_snapshot",Json.of("assets",List.of(Json.of("id",input),Json.of("id",mask)),"document",document));run.put("result",Json.of("asset_ids",List.of(output),"document",document));
        var raw=item("region_protection");raw.remove("output_asset_id");var frozen=Json.map(prepare(raw).get("assessment"));assertEquals(mask,frozen.get("mask_asset_id"));assertTrue(Json.equivalent(document,frozen.get("source_document")));assertTrue(Json.equivalent(document,frozen.get("result_document")));
        assertEquals(Set.of(input,output,mask),new HashSet<>(Json.list(frozen.get("asset_ids"))));run.put("result",Json.of("asset_ids",List.of(output)));assertThrows(AiPictureException.class,()->prepare(raw));
    }
    @Test void independentExpectationCannotBeTheSameFormalOutput() {
        var raw=item("generation");raw.put("expect_asset_id",output);assertThrows(AiPictureException.class,()->prepare(raw));
        raw.put("expect_asset_id",expected);when(assets.record(owner,expected)).thenReturn(Json.of("id",expected,"run_id",source));assertThrows(AiPictureException.class,()->prepare(raw));
        when(assets.record(owner,expected)).thenReturn(Json.of("id",expected,"source","upload","status","ready"));assertEquals(expected,prepare(raw).get("expect_asset_id"));
    }
    @Test void ocrNeedsExplicitExtractionAndBoundedIndependentTextBoxes() {
        run.put("tool","split_layers");run.put("input_snapshot",Json.of("assets",List.of(Json.of("id",input)),"document",document));run.put("result",Json.of("asset_ids",List.of(output),"document",document));run.put("params",Json.of("include_text",false));var raw=item("ocr");raw.remove("output_asset_id");raw.put("expected_ocr",List.of(Json.of("text","中文价格","bbox",List.of(0,0,48,24))));
        assertThrows(AiPictureException.class,()->prepare(raw));run.put("params",Json.of("include_text",true));assertEquals(1,Json.list(prepare(raw).get("expected_ocr")).size());
        raw.put("expected_ocr",List.of(Json.of("text","中文价格","bbox",List.of(0,0,-1,24))));assertThrows(AiPictureException.class,()->prepare(raw));
    }
    @Test void manualOpinionAndStrictRequirementCannotForgeRealMode() {
        run.put("tool","upscale_image");run.put("params",Json.of("scale",2));run.put("input_snapshot",Json.of("assets",List.of(Json.of("id",input)),"document",document));var raw=item("enhancement");raw.put("human_review",Json.of("status","passed","notes"," "));
        assertThrows(AiPictureException.class,()->prepare(raw));raw.put("human_review",Json.of("status","pending","notes","等待真实质量判断"));var prepared=prepare(raw);assertEquals(true,prepared.get("require_true_super_resolution"));assertEquals(false,Json.map(Json.map(prepared.get("assessment")).get("source_provider")).get("true_super_resolution"));assertEquals("pending",Json.map(prepared.get("human_review")).get("status"));
        raw.put("require_true_super_resolution","true");assertThrows(AiPictureException.class,()->prepare(raw));
    }
    @Test void assessmentReferenceBudgetDoesNotExpandOrdinaryPixelCommands() {
        var ids=new ArrayList<String>();for(int i=0;i<65;i++) ids.add(Json.id());
        var runs=new RunService(db,mock(SessionService.class),assets,registry,mock(com.ruoyi.aipicture.storage.AiPictureStorage.class),new AiPictureProperties(),mock(org.springframework.transaction.support.TransactionTemplate.class));
        assertEquals(65,Json.list(runs.freeze(owner,null,Json.of("asset_ids",ids),"evaluation").get("assets")).size());
        assertThrows(AiPictureException.class,()->runs.freeze(owner,null,Json.of("asset_ids",ids),"adjust_image"));
        for(int i=ids.size();i<265;i++) ids.add(Json.id());assertThrows(AiPictureException.class,()->runs.freeze(owner,null,Json.of("asset_ids",ids),"evaluation"));
    }
    @Test void apiCannotRelaxExactSizeCountOrOutsideProtection() {
        var generation=item("generation");generation.put("thresholds",Json.of("size",0));assertThrows(AiPictureException.class,()->prepare(generation));generation.put("thresholds",Json.of("outside_max",0));assertThrows(AiPictureException.class,()->prepare(generation));
        run.put("tool","erase_region");run.put("params",Json.of("mask_asset_id",mask));run.put("input_snapshot",Json.of("assets",List.of(Json.of("id",input)),"document",document));run.put("result",Json.of("asset_ids",List.of(output),"document",document));var region=item("region_protection");region.put("thresholds",Json.of("outside_max",1));assertThrows(AiPictureException.class,()->prepare(region));
        run.put("tool","split_layers");run.put("params",Json.of("include_text",true));var ocr=item("ocr");ocr.put("expected_ocr",List.of(Json.of("text","独立文字","bbox",List.of(0,0,24,24))));ocr.put("thresholds",Json.of("count_match",0));assertThrows(AiPictureException.class,()->prepare(ocr));
    }
}
