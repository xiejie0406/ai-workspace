package com.ruoyi.aipicture;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.tool.*;

class SelectionBoundaryTest {
    @Test void aSingleBrushDotIsLegalButEmptyAndInvalidPathsRemainRejected() {
        assertDoesNotThrow(()->SelectionInputValidator.validate(Json.of("revision",0,"strokes",List.of(List.of(Json.of("x",0.3,"y",0.7))))));
        assertThrows(AiPictureException.class,()->SelectionInputValidator.validate(Json.of("revision",0,"strokes",List.of(List.of()))));
        assertThrows(AiPictureException.class,()->SelectionInputValidator.validate(Json.of("revision",0,"strokes",List.of(List.of(Json.of("x",-0.01,"y",0.7))))));
        assertThrows(AiPictureException.class,()->SelectionInputValidator.validate(Json.of("revision",0,"strokes",List.of(List.of(Json.of("x",0.3,"y",Double.POSITIVE_INFINITY))))));
        assertThrows(AiPictureException.class,()->SelectionInputValidator.validate(Json.of("revision",0,"strokes",List.of(List.of(Json.of("x",0.3,"y",0.7))),"radius",0.121)));
    }
    @Test void normalizedPointsAndBrushAreAcceptedButStaleHiddenAndOutOfRangeInputsFail() {
        assertDoesNotThrow(()->SelectionInputValidator.validate(Json.of("revision",8,"points",List.of(Json.of("x",0.3,"y",0.7)))));
        assertDoesNotThrow(()->SelectionInputValidator.validate(Json.of("revision",8,"strokes",List.of(List.of(Json.of("x",0,"y",0),Json.of("x",1,"y",1))),"radius",0.03)));
        assertThrows(AiPictureException.class,()->SelectionInputValidator.validate(Json.of("revision",8,"points",List.of(Json.of("x",1.01,"y",0.7)))));
        assertThrows(AiPictureException.class,()->SelectionInputValidator.validate(Json.of("revision",8,"points",List.of(Json.of("x",0.3,"y",0.7)),"mask_asset_id",Json.id())));
        assertThrows(AiPictureException.class,()->SelectionInputValidator.validate(Json.of("revision",8,"strokes",List.of(List.of()))));
    }
}
