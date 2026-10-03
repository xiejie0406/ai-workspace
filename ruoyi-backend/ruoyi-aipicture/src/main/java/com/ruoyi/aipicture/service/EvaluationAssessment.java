package com.ruoyi.aipicture.service;

import java.util.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.persistence.AiPictureRepository;
import com.ruoyi.aipicture.tool.ToolRegistry;

/** 专项只冻结已成功的本人正式输出，不能借评测再次派发原收费工具。 */
public final class EvaluationAssessment {
    private EvaluationAssessment() {}
    private static final Set<String> LOCAL=Set.of("remove_background","adjust_image","letterbox","prepare_delivery_sizes","encode","resize");
    private static final Map<String,Set<String>> TOOLS=Map.of(
        "sam",Set.of("selection"),"region_protection",Set.of("erase_region","replace_region","promote_object_to_layer"),
        "ocr",Set.of("split_layers"),"enhancement",Set.of("upscale_image"),"generation",Set.of("generate_image","generate_marketing"));
    private static final Set<String> FORBIDDEN=Set.of("input","expect","path","assessment","provider","document","input_snapshot","mask_asset_id");
    private static final Set<String> PROVIDER=Set.of("image","matting","ocr","sam","offline_test","enhancement_implementation","true_super_resolution","provider","model","model_version","algorithm_version","weight_sha256","mode");

    public static List<Object> prepare(long owner,List<Object> source,AiPictureRepository db,AssetService assets,ToolRegistry registry) {
        if(source.isEmpty()||source.size()>100) throw AiPictureException.invalid("评测需要 1–100 条限定素材样本");
        var result=new ArrayList<Object>();var names=new HashSet<String>();
        for(Object object:source) {
            var item=Json.copy(Json.map(object));String name=Json.string(item.get("id")).strip();
            if(name.isBlank()||name.codePointCount(0,name.length())>80||!names.add(name)) throw AiPictureException.invalid("评测样本名称须为不同的 1–80 字符");
            item.put("id",name);for(String key:FORBIDDEN) if(item.containsKey(key)) throw AiPictureException.invalid("客户端不能指定评测服务字段 "+key);
            String task=Json.string(item.get("task"));
            if(!task.equals("assess_result")) {
                if(!LOCAL.contains(task)) throw AiPictureException.invalid("不支持的评测任务");
                assets.record(owner,Json.uuid(item.get("input_asset_id")));
            } else freeze(owner,item,db,assets,registry);
            if(item.get("expect_asset_id")!=null) assets.record(owner,Json.uuid(item.get("expect_asset_id")));
            result.add(item);
        }
        return result;
    }

    private static void freeze(long owner,Map<String,Object> item,AiPictureRepository db,AssetService assets,ToolRegistry registry) {
        String suite=Json.string(item.get("suite"));var tools=TOOLS.get(suite);if(tools==null) throw AiPictureException.invalid("未知专项评测类型");
        String sourceId=Json.uuid(item.get("source_run_id"));var run=db.owned("aip_run",sourceId,owner,false);
        String tool=Json.string(run.get("tool"));if(!"succeeded".equals(run.get("status"))||!tools.contains(tool)) throw AiPictureException.invalid("专项仅评估对应工具已成功的正式任务；未知结果不能重跑");
        var params=Json.map(run.get("params"));var snapshot=Json.map(run.get("input_snapshot"));var output=Json.map(run.get("result"));
        if(suite.equals("sam")&&Json.list(params.get("points")).isEmpty()) throw AiPictureException.invalid("SAM 专项需要真实点选任务，笔刷不能代替点选");
        if(suite.equals("ocr")&&!Json.bool(params.get("include_text"),false)) throw AiPictureException.invalid("OCR 专项需要启用文字提取的拆层任务");
        if(suite.equals("sam")&&item.get("expect_asset_id")==null) throw AiPictureException.invalid("SAM 专项需要独立人工预期遮罩");
        var outputs=new LinkedHashSet<String>();for(Object value:Json.list(output.get("asset_ids"))) outputs.add(Json.uuid(value));
        if(output.get("mask_asset_id")!=null) outputs.add(Json.uuid(output.get("mask_asset_id")));
        String selected=item.get("output_asset_id")==null?null:Json.uuid(item.get("output_asset_id"));
        if(selected!=null&&!outputs.contains(selected)) throw AiPictureException.invalid("所选输出不属于此正式任务");
        if(item.get("expect_asset_id")!=null) {
            String expected=Json.uuid(item.get("expect_asset_id"));var reference=assets.record(owner,expected);
            if(outputs.contains(expected)||sourceId.equals(reference.get("run_id"))) throw AiPictureException.invalid("独立预期不能使用被评估任务本身的输出自比较");
        }
        if(Set.of("generation","enhancement").contains(suite)&&selected==null) throw AiPictureException.invalid("此专项需要选择正式任务的输出素材");
        if(suite.equals("sam")) {
            String mask=Json.uuid(output.get("mask_asset_id"));if(selected!=null&&!selected.equals(mask)) throw AiPictureException.invalid("SAM 输出必须是该任务的正式遮罩");item.put("output_asset_id",mask);
        }
        if(item.containsKey("input_asset_id")) throw AiPictureException.invalid("专项原输入由正式任务冻结，不能另指定输入素材");
        var sourceDocument=Json.map(snapshot.get("document"));var resultDocument=Json.map(output.get("document"));
        if(Set.of("region_protection","ocr","enhancement").contains(suite)&&sourceDocument.isEmpty()) throw AiPictureException.invalid("专项缺少可核对的原画布快照");
        if(Set.of("region_protection","ocr").contains(suite)&&resultDocument.isEmpty()) throw AiPictureException.invalid("专项缺少可核对的结果文档");
        if(!sourceDocument.isEmpty()) registry.document(sourceDocument);if(!resultDocument.isEmpty()) registry.document(resultDocument);
        String mask=suite.equals("region_protection")?Json.uuid(params.get("mask_asset_id")):null;
        var references=new LinkedHashSet<String>();for(Object value:Json.list(snapshot.get("assets"))) references.add(Json.uuid(Json.map(value).get("id")));
        references.addAll(outputs);if(mask!=null) references.add(mask);collectDocument(sourceDocument,references);collectDocument(resultDocument,references);
        for(String id:references) assets.record(owner,id);
        var frozenParams=new LinkedHashMap<String,Object>();for(String key:List.of("scale","ratio","include_text","points")) if(params.containsKey(key)) frozenParams.put(key,params.get(key));
        var provider=new LinkedHashMap<String,Object>();Json.map(run.get("provider")).forEach((key,value)->{if(PROVIDER.contains(key)) provider.put(key,value);});
        var sourceResult=new LinkedHashMap<String,Object>();for(String key:List.of("asset_ids","mask_asset_id","document","ocr_mode","mode","true_super_resolution")) if(output.containsKey(key)) sourceResult.put(key,output.get(key));
        item.put("assessment",Json.of("source_tool",tool,"source_params",frozenParams,"source_document",sourceDocument,"result_document",resultDocument,"source_result",sourceResult,"source_provider",provider,"mask_asset_id",mask,"output_asset_ids",new ArrayList<>(outputs),"asset_ids",new ArrayList<>(references)));
        item.put("human_review",humanReview(item.get("human_review")));
        if(suite.equals("enhancement")) {
            if(item.get("require_true_super_resolution")!=null&&!(item.get("require_true_super_resolution") instanceof Boolean)) throw AiPictureException.invalid("严格超分要求必须是布尔值");
            item.putIfAbsent("require_true_super_resolution",true);
        }
        if(suite.equals("ocr")) validateOcr(Json.list(item.get("expected_ocr")));
        var thresholds=Json.map(item.get("thresholds"));var valid=new HashSet<>(switch(suite) {
            case "sam" -> Set.of("alpha_iou");case "region_protection" -> Set.of("outside_mae","outside_max");
            case "ocr" -> Set.of("cer","bbox_iou","count_match");default -> Set.of("size");
        });
        if(item.get("expect_asset_id")!=null&&!Set.of("sam","ocr").contains(suite)) valid.addAll(Set.of("mae","psnr"));
        if(suite.equals("ocr")&&item.get("expect_asset_id")!=null) throw AiPictureException.invalid("OCR 需要文字和框标注，不能用图片替代");
        for(var entry:thresholds.entrySet()) {
            String metric=entry.getKey();if(!valid.contains(metric)||!(entry.getValue() instanceof Number number)||!Double.isFinite(number.doubleValue())||number.doubleValue()<0) throw AiPictureException.invalid("专项指标阈值不合法");
            double value=number.doubleValue(),maximum=Set.of("alpha_iou","bbox_iou","size","count_match").contains(metric)?1:metric.equals("cer")?10:255;
            if(value>maximum||Set.of("size","count_match").contains(metric)&&value!=1||Set.of("outside_mae","outside_max").contains(metric)&&value!=0) throw AiPictureException.invalid("尺寸/文字数量须严格一致，遮罩外像素必须不变，不能降低固有边界");
        }
    }

    private static void collectDocument(Map<String,Object> document,Set<String> references) {
        for(Object value:Json.list(document.get("layers"))) {var layer=Json.map(value);if(layer.get("asset_id")!=null) references.add(Json.uuid(layer.get("asset_id")));}
    }
    static Map<String,Object> humanReview(Object value) {
        var human=Json.map(value);for(String key:human.keySet()) if(!Set.of("status","notes").contains(key)) throw AiPictureException.invalid("人工评语只接受状态与说明");
        String state=Json.string(human.getOrDefault("status","pending")),notes=Json.string(human.getOrDefault("notes","")).strip();
        if(!Set.of("pending","passed","failed").contains(state)||notes.codePointCount(0,notes.length())>2000||state.equals("passed")&&notes.isBlank()) throw AiPictureException.invalid("人工质量状态或说明不合法；通过需填写核对说明");
        return Json.of("status",state,"notes",notes);
    }
    private static void validateOcr(List<Object> expected) {
        if(expected.isEmpty()||expected.size()>128) throw AiPictureException.invalid("OCR 需要 1–128 条人工预期文字与画布像素框");int length=0;
        for(Object value:expected) {
            var item=Json.map(value);String text=Json.string(item.get("text"));var box=Json.list(item.get("bbox"));length+=text.codePointCount(0,text.length());
            if(text.isBlank()||text.codePointCount(0,text.length())>2000||box.size()!=4) throw AiPictureException.invalid("OCR 预期需非空文字及 x/y/宽/高像素框");
            for(int i=0;i<4;i++) if(!(box.get(i) instanceof Number n)||!Double.isFinite(n.doubleValue())||Math.abs(n.doubleValue())>100000||i>=2&&n.doubleValue()<=0) throw AiPictureException.invalid("OCR 像素框不合法");
        }
        if(length>32000) throw AiPictureException.invalid("OCR 预期总文字超过限制");
    }
}
