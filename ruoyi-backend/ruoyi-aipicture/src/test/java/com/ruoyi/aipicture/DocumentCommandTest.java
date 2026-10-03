package com.ruoyi.aipicture;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.tool.*;

class DocumentCommandTest {
    DocumentCommandService service=new DocumentCommandService();Map<String,Object> original;
    @BeforeEach void setup() {
        original=Json.copy(DocumentCommandService.documentOf(Json.of("id",Json.id(),"width",400,"height",300)));
        Json.list(original.get("layers")).add(Json.of("id","text","kind","text","name","标题","text","原文","width",120,"height",40,"opacity",1,"visible",true,"transform",Json.of("x",10,"y",20,"scale_x",1,"scale_y",1,"rotation",0)));
    }
    Map<String,Object> layer(Map<String,Object> doc,String id) {return DocumentCommandService.resolve(doc,id,false);}
    @Test void flipScaleAndPlacementPreserveSignedTransformInOneCommand() {
        var flipped=service.apply("flip_layer",original,Json.of("direction","horizontal","layer_id","base"));
        var scaled=service.apply("scale_layer",flipped,Json.of("layer_id","base","scale_x",2,"scale_y",3,"x",45,"y",67));var t=Json.map(layer(scaled,"base").get("transform"));
        assertEquals(-2,Json.decimal(t.get("scale_x"),0));assertEquals(3,Json.decimal(t.get("scale_y"),0));assertEquals(45,Json.decimal(t.get("x"),0));
        assertEquals(1,Json.decimal(Json.map(layer(original,"base").get("transform")).get("scale_x"),0));
    }
    @Test void cropUsesBankersRoundingAndMovesEveryLayer() {
        var doc=service.apply("crop_canvas",original,Json.of("ratio","1:1"));assertEquals(300,Json.number(doc.get("width")));assertEquals(300,Json.number(doc.get("height")));assertEquals(-50,Json.decimal(Json.map(layer(doc,"base").get("transform")).get("x"),0));
        assertThrows(AiPictureException.class,()->service.apply("crop_canvas",original,Json.of("rect",Json.of("x",0.9,"y",0,"width",0.2,"height",0.2))));
        assertThrows(AiPictureException.class,()->service.apply("crop_canvas",original,Json.of("rect",Json.of("x",0,"y",0,"width",0.01,"height",0.01))));
    }
    @Test void relativeMoveRotateOpacityVisibilityAndReorderAreIndependent() {
        var moved=service.apply("move_layer",original,Json.of("layer_id","text","dx",4,"dy",-2));
        var rotated=service.apply("rotate_layer",moved,Json.of("layer_id","text","angle",45));
        assertEquals(14,Json.decimal(Json.map(layer(rotated,"text").get("transform")).get("x"),0));assertEquals(45,Json.decimal(Json.map(layer(rotated,"text").get("transform")).get("rotation"),0));
        var faded=service.apply("set_layer_opacity",rotated,Json.of("layer_id","text","opacity",0.5));assertEquals(0.5,layer(faded,"text").get("opacity"));
        var hidden=service.apply("set_layer_visible",faded,Json.of("layer_id","text","visible",false));assertEquals(false,layer(hidden,"text").get("visible"));
        var bottom=service.apply("reorder_layer",hidden,Json.of("layer_id","text","place","bottom"));assertEquals("text",Json.map(Json.list(bottom.get("layers")).get(0)).get("id"));
        assertTrue(Json.equivalent(layer(original,"base"),layer(bottom,"base")));
    }
    @Test void textEditsOnlyTextLayerAndMissingRequiredMotionFails() {
        var text=service.apply("set_layer_text",original,Json.of("text","新品上市","font_size",36,"fill","#abc"));assertEquals("新品上市",layer(text,"text").get("text"));
        assertThrows(AiPictureException.class,()->service.apply("set_layer_text",original,Json.of("layer_id","base","text","x")));
        assertThrows(AiPictureException.class,()->service.apply("scale_layer",original,Map.of()));assertThrows(AiPictureException.class,()->service.apply("rotate_layer",original,Map.of()));assertThrows(AiPictureException.class,()->service.apply("move_layer",original,Map.of()));
    }
    @Test void noOpIsNumericAndKeyOrderIndependent() {
        var doc=service.apply("move_layer",original,Json.of("layer_id","base","dx",0.0,"dy",0.0));assertTrue(Json.equivalent(original,doc));
        assertTrue(Json.equivalent(Json.of("a",1,"b",2),Json.of("b",2.0,"a",1.0)));
    }

    @Test void textLayerNamePreservesOriginalTwelveCodePoints() {
        String complete="abcdefghijk😀备注";
        var changed=service.apply("set_layer_text",original,Json.of("layer_id","text","text",complete));
        assertAll("原Python短名截12码点，正文完整保留",
            ()->assertEquals("abcdefghijk😀",layer(changed,"text").get("name")),
            ()->assertEquals(complete,layer(changed,"text").get("text")),
            ()->assertEquals("原文",layer(original,"text").get("text")));
        var exactlyTwelve=service.apply("set_layer_text",original,Json.of("text","abcdefghijk😀"));
        assertEquals("abcdefghijk😀",layer(exactlyTwelve,"text").get("name"));
        var emoji=service.apply("set_layer_text",original,Json.of("text","😀😃😄😁😆😅😂🤣😊😇🙂🙃后续"));
        assertEquals("😀😃😄😁😆😅😂🤣😊😇🙂🙃",layer(emoji,"text").get("name"));
        var empty=service.apply("set_layer_text",original,Json.of("text",""));
        assertEquals("",layer(empty,"text").get("text"));assertEquals("标题",layer(empty,"text").get("name"));
    }

    @Test void explicitLayerReferencePreservesOriginalWhitespaceRules() {
        for(String ref:List.of(" \tbase\n ","\u00a0base\u00a0","\u0085base\u0085","\u2007base\u2007","\u202fbase\u202f")) {
            var moved=assertDoesNotThrow(()->service.apply("move_layer",original,Json.of("layer_id",ref,"dx",5)),"原合法引用边缘空白不能阻止命中");
            assertEquals(5,Json.decimal(Json.map(layer(moved,"base").get("transform")).get("x"),0));
            assertEquals(10,Json.decimal(Json.map(layer(moved,"text").get("transform")).get("x"),0));
        }
        var named=assertDoesNotThrow(()->service.apply("move_layer",original,Json.of("layer_id","\u00a0 标题\u202f","dx",3)));
        assertEquals(13,Json.decimal(Json.map(layer(named,"text").get("transform")).get("x"),0));
        for(String missing:List.of(" \t\n ","\u00a0\u0085\u2007\u202f"," 不存在 "))
            assertThrows(AiPictureException.class,()->service.apply("move_layer",original,Json.of("layer_id",missing,"dx",5)),"显式引用不存在，不能误操作默认图层");
        for(Object absent:Arrays.asList(null,"")) {
            var moved=service.apply("move_layer",original,Json.of("layer_id",absent,"dx",5));
            assertEquals(5,Json.decimal(Json.map(layer(moved,"base").get("transform")).get("x"),0));
        }
        assertEquals(0,Json.decimal(Json.map(layer(original,"base").get("transform")).get("x"),0));
    }
}
