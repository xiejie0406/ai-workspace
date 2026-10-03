package com.ruoyi.aipicture.tool;

import com.ruoyi.aipicture.domain.*;
import java.util.*;

/** 从源 edits/document.py 迁移的九个文档命令，像素渲染仍由 Python 执行。 */
public final class DocumentCommandService {
    public static final Set<String> COMMANDS=Set.of("crop_canvas","flip_layer","set_layer_opacity","set_layer_visible","set_layer_text","reorder_layer","scale_layer","rotate_layer","move_layer");
    public Map<String,Object> apply(String tool,Map<String,Object> document,Map<String,Object> p) {
        var doc=Json.copy(document);
        if("crop_canvas".equals(tool)) { crop(doc,p); return doc; }
        var layer=resolve(doc,Json.string(p.get("layer_id")),tool.equals("set_layer_text"));
        var t=Json.map(layer.get("transform"));
        switch(tool) {
            case "flip_layer" -> { String axis="horizontal".equals(p.get("direction"))?"scale_x":"scale_y"; t.put(axis,-Json.decimal(t.get(axis),1)); }
            case "set_layer_opacity" -> layer.put("opacity",p.get("opacity"));
            case "set_layer_visible" -> layer.put("visible",p.get("visible"));
            case "set_layer_text" -> {
                if(!"text".equals(layer.get("kind"))) throw AiPictureException.invalid("只能修改文字图层");
                String value=Json.string(p.get("text")); layer.put("text",value);
                if(!value.isEmpty()) layer.put("name",value.substring(0,value.offsetByCodePoints(0,Math.min(12,value.codePointCount(0,value.length())))));
                if(p.get("font_size")!=null) layer.put("font_size",p.get("font_size"));
                if(p.get("fill")!=null) layer.put("fill",p.get("fill"));
            }
            case "scale_layer" -> {
                if(p.get("factor")==null&&p.get("scale_x")==null&&p.get("scale_y")==null) throw AiPictureException.invalid("需要相对倍率或绝对缩放");
                for(String axis:List.of("x","y")) if(p.get(axis)!=null) t.put(axis,p.get(axis));
                for(String axis:List.of("scale_x","scale_y")) {
                    double current=Json.decimal(t.get(axis),1);
                    if(p.get("factor")!=null) t.put(axis,current*Json.decimal(p.get("factor"),1));
                    else if(p.get(axis)!=null) t.put(axis,Math.copySign(Math.abs(Json.decimal(p.get(axis),1)),current));
                }
            }
            case "rotate_layer" -> {
                if(p.get("angle")==null&&p.get("rotation")==null) throw AiPictureException.invalid("需要旋转角度");
                t.put("rotation",p.get("angle")!=null?Json.decimal(t.get("rotation"),0)+Json.decimal(p.get("angle"),0):p.get("rotation"));
            }
            case "move_layer" -> {
                if(List.of("x","y","dx","dy").stream().allMatch(k->p.get(k)==null)) throw AiPictureException.invalid("需要坐标或位移");
                for(String axis:List.of("x","y")) t.put(axis,p.get(axis)!=null?p.get(axis):Json.decimal(t.get(axis),0)+Json.decimal(p.get("d"+axis),0));
            }
            case "reorder_layer" -> {
                var layers=Json.list(doc.get("layers")); int index=layers.indexOf(layer); String place=Json.string(p.get("place"));
                switch(place) {
                    case "top"->{layers.remove(index);layers.add(layer);}
                    case "bottom"->{layers.remove(index);layers.add(0,layer);}
                    case "up"->{if(index<layers.size()-1) Collections.swap(layers,index,index+1);}
                    case "down"->{if(index>0) Collections.swap(layers,index,index-1);}
                    default->throw AiPictureException.invalid("未知图层顺序");
                }
            }
            default->throw AiPictureException.invalid("未知文档命令");
        }
        layer.put("transform",t); return doc;
    }
    public static Map<String,Object> resolve(Map<String,Object> doc,String id,boolean text) {
        var layers=Json.list(doc.get("layers"));
        if(!id.isEmpty()) {
            // 沿原Python strip空白集合；显式纯空白引用仍查找/拒绝，不转为默认顶层。
            String wanted=id.replaceAll("^[\\p{javaWhitespace}\\p{Z}\\u0085]+|[\\p{javaWhitespace}\\p{Z}\\u0085]+$","");
            for(Object object:layers) {var layer=Json.map(object);if(wanted.equals(layer.get("id"))) return layer;}
            for(Object object:layers) {var layer=Json.map(object);if(wanted.equals(layer.get("name"))) return layer;}
        } else for(int i=layers.size()-1;i>=0;i--) {
            var layer=Json.map(layers.get(i)); if((text?"text":"image").equals(layer.get("kind"))&&Json.bool(layer.get("visible"),true)) return layer;
        }
        throw AiPictureException.invalid("没有可操作的图层");
    }
    private void crop(Map<String,Object> doc,Map<String,Object> p) {
        double cw=Json.number(doc.get("width")),ch=Json.number(doc.get("height")); double x,y,w,h;
        if((p.get("ratio")==null)==(p.get("rect")==null)) throw AiPictureException.invalid("裁剪需要比例或裁剪框之一");
        if(p.get("ratio")!=null) {
            String[] ratio=Json.string(p.get("ratio")).split(":"); double rw=Double.parseDouble(ratio[0]),rh=Double.parseDouble(ratio[1]);
            if(cw*rh>=ch*rw) {h=ch;w=h*rw/rh;} else {w=cw;h=w*rh/rw;}
            x=(cw-w)/2;y=(ch-h)/2;
        } else {
            var r=Json.map(p.get("rect")); double nx=Json.decimal(r.get("x"),0),ny=Json.decimal(r.get("y"),0),nw=Json.decimal(r.get("width"),0),nh=Json.decimal(r.get("height"),0);
            if(nx+nw>1.0001||ny+nh>1.0001) throw AiPictureException.invalid("裁剪框超出画布");
            x=nx*cw;y=ny*ch;w=nw*cw;h=nh*ch;
        }
        long left=Math.max(0,(long)Math.rint(x)),top=Math.max(0,(long)Math.rint(y));
        long right=Math.min((long)cw,(long)Math.rint(x+w)),bottom=Math.min((long)ch,(long)Math.rint(y+h));
        if(right-left<32||bottom-top<32) throw AiPictureException.invalid("裁剪最短边不能小于 32 像素");
        for(Object object:Json.list(doc.get("layers"))) {
            var layer=Json.map(object);var t=Json.map(layer.get("transform"));t.put("x",Json.decimal(t.get("x"),0)-left);t.put("y",Json.decimal(t.get("y"),0)-top);layer.put("transform",t);
        }
        doc.put("width",right-left);doc.put("height",bottom-top);
    }
    public static Map<String,Object> documentOf(Map<String,Object> asset) {
        return Json.of("width",asset.get("width"),"height",asset.get("height"),"layers",List.of(Json.of("id","base","kind","image","name","底图","width",asset.get("width"),"height",asset.get("height"),"asset_id",asset.get("id"),"transform",Json.of("x",0,"y",0,"scale_x",1,"scale_y",1,"rotation",0),"opacity",1,"visible",true,"locked",true,"font_size",24,"fill","#141414")));
    }
}
