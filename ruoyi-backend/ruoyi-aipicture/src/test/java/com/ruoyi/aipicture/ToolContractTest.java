package com.ruoyi.aipicture;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import com.ruoyi.aipicture.config.AiPictureProperties;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.tool.*;

class ToolContractTest {
    ToolRegistry registry;
    @BeforeEach void setup() throws Exception {registry=new ToolRegistry(new AiPictureProperties());}
    @Test void all22ToolsHaveASingleVersionedSchemaAndCorrectExecutionOwner() {
        assertEquals(22,Json.list(registry.registry().get("tools")).size());
        assertEquals("2026.10-r2",registry.registry().get("toolRegistryVersion"));
        assertEquals(9,Json.list(registry.registry().get("tools")).stream().filter(t->"java".equals(Json.map(t).get("executionOwner"))).count());
        assertEquals(13,Json.list(registry.registry().get("tools")).stream().filter(t->"python".equals(Json.map(t).get("executionOwner"))).count());
    }
    @Test void defaultsReferencesNullsAndBoundsMatchSourceSchema() {
        var generation=registry.validate("generate_image",Json.of("prompt","商品"));assertEquals(4,generation.get("count"));assertEquals("1:1",generation.get("ratio"));
        assertDoesNotThrow(()->registry.validate("generate_image",Json.of("prompt","商品","negative_prompt",null,"seed",2147483647L,"count",6)));
        assertThrows(AiPictureException.class,()->registry.validate("generate_image",Json.of("prompt","商品","count",7)));
        assertThrows(AiPictureException.class,()->registry.validate("generate_image",Json.of("prompt","商品","seed",-1)));
        assertThrows(AiPictureException.class,()->registry.validate("generate_image",Json.of("prompt","商品","reference_asset_ids",List.of("not-a-uuid"))));
        assertThrows(AiPictureException.class,()->registry.validate("generate_image",Json.of("prompt","商品","user_id",55)));
    }
    @Test void allElevenAdjustmentFieldsAndFiniteNumbersAreChecked() {
        assertDoesNotThrow(()->registry.validate("adjust_image",Json.of("brightness",-1,"contrast",1,"highlights",0.2,"shadows",-0.2,"temperature",0.5,"tint",-0.5,"saturation",0.1,"vibrance",0.1,"sharpness",0.5,"clarity",-0.1,"vignette",1)));
        assertThrows(AiPictureException.class,()->registry.validate("adjust_image",Json.of("vignette",-0.1)));
        assertThrows(AiPictureException.class,()->registry.validate("adjust_image",Json.of("brightness",Double.NaN)));
    }
    @Test void unknownPropertiesToolsAndContractVersionsAreRejected() {
        assertThrows(AiPictureException.class,()->registry.validate("missing_tool",Map.of()));
        assertThrows(AiPictureException.class,()->registry.versions(Json.of("schemaVersion","0","toolRegistryVersion","2026.10-r2")));
        registry.versions(Json.of("schemaVersion","1","toolRegistryVersion","2026.10-r2"));
    }
    @Test void defaultFactoriesMatchOriginalBatchAndDeliveryModels() {
        var batch=registry.validate("batch_process",Json.of("asset_ids",List.of(Json.id()),"operations",List.of(Json.of("tool","remove_background"))));assertEquals(List.of("png"),batch.get("formats"));assertEquals(List.of("1:1","4:5","9:16"),registry.validate("prepare_delivery_sizes",Map.of()).get("ratios"));
    }
}
