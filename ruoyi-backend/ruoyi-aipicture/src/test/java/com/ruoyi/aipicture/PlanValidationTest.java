package com.ruoyi.aipicture;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import com.ruoyi.aipicture.config.AiPictureProperties;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.tool.*;

class PlanValidationTest {
    PlanValidator validator;
    @BeforeEach void setup() throws Exception {validator=new PlanValidator(new ToolRegistry(new AiPictureProperties()));}
    Object step(String id,String dependency) {return Json.of("id",id,"tool","flip_layer","params",Json.of("direction","horizontal"),"depends_on",dependency==null?List.of():List.of(dependency));}
    @Test void legalMultiStepAndHiddenNullDefaultsWork() {assertEquals(2,validator.validate(List.of(step("a",null),step("b","a"))).size());assertDoesNotThrow(()->validator.validate(List.of(Json.of("id","x","tool","scale_layer","params",Json.of("factor",2,"x",null,"y",null)))));}
    @Test void duplicateUnknownMissingCyclicAndNineStepsAreRejected() {
        assertThrows(AiPictureException.class,()->validator.validate(List.of(step("a",null),step("a",null))));
        assertThrows(AiPictureException.class,()->validator.validate(List.of(step("a","b"),step("b","a"))));
        assertThrows(AiPictureException.class,()->validator.validate(List.of(step("a","missing"))));
        var nine=new ArrayList<Object>();for(int i=0;i<9;i++) nine.add(step("s"+i,null));assertThrows(AiPictureException.class,()->validator.validate(nine));
        assertThrows(AiPictureException.class,()->validator.validate(List.of(Json.of("id","x","tool","evil","params",Map.of()))));
    }
    @Test void modelCannotChooseOwnerMaskOrHiddenCoordinates() {
        assertThrows(AiPictureException.class,()->validator.validate(List.of(Json.of("id","x","tool","move_layer","params",Json.of("dx",1,"mask_asset_id",Json.id())))));
        assertThrows(AiPictureException.class,()->validator.validate(List.of(Json.of("id","x","tool","scale_layer","params",Json.of("factor",2,"x",55)))));
    }
}
