package com.ruoyi.aipicture.tool;

import java.util.*;
import com.ruoyi.aipicture.domain.*;

/** 选区控制请求的坐标边界，22个图像工具仍完全以唯一注册表校验。 */
public final class SelectionInputValidator {
    private SelectionInputValidator() { }
    public static void validate(Map<String,Object> p) {
        for(String key:p.keySet()) if(!Set.of("revision","points","strokes","radius","append").contains(key)) throw AiPictureException.invalid("未知选区字段："+key);
        if(!(p.get("revision") instanceof Number)||Json.number(p.get("revision"))<0) throw AiPictureException.invalid("选区 revision 无效");
        var points=Json.list(p.get("points"));var strokes=Json.list(p.get("strokes"));if(points.isEmpty()&&strokes.isEmpty()) throw AiPictureException.invalid("选区需要点或笔画");
        if(points.size()>100||strokes.size()>1000) throw AiPictureException.invalid("选区超出点或笔画上限");
        for(Object point:points) point(point);
        long total=0;for(Object stroke:strokes) {var path=Json.list(stroke);if(path.isEmpty()) throw AiPictureException.invalid("笔画至少需要一个点");total+=path.size();for(Object point:path) point(point);}
        if(total>10000) throw AiPictureException.invalid("笔画总点数超过上限");
        if(p.get("radius")!=null) {double r=Json.decimal(p.get("radius"),0);if(!Double.isFinite(r)||r<0.005||r>0.12) throw AiPictureException.invalid("笔刷半径需要 0.005–0.12");}
        if(p.get("append")!=null&&!(p.get("append") instanceof Boolean)) throw AiPictureException.invalid("append 必须为布尔值");
    }
    private static void point(Object value) {
        var point=Json.map(value);if(!point.keySet().equals(Set.of("x","y"))) throw AiPictureException.invalid("坐标需要 x/y");
        for(String axis:List.of("x","y")) {if(!(point.get(axis) instanceof Number)) throw AiPictureException.invalid("点坐标必须为数值");double number=Json.decimal(point.get(axis),0);if(!Double.isFinite(number)||number<0||number>1) throw AiPictureException.invalid("点坐标需要归一化至 0–1");}
    }
}
