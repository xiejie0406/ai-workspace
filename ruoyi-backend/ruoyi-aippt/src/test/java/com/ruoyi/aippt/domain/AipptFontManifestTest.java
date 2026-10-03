package com.ruoyi.aippt.domain;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AipptFontManifestTest {
    private static final String HASH="a".repeat(64);

    @Test void unavailableUsesOnlyExplicitEstimateMode() {
        assertDoesNotThrow(()->AipptRules.fontManifest("unavailable",JSONObject.of("font_manifest_hash","unavailable","font_mode","estimate")));
        assertThrows(AipptException.class,()->AipptRules.fontManifest("unavailable",JSONObject.of("font_manifest_hash",HASH,"font_mode","verified")));
        assertThrows(AipptException.class,()->AipptRules.fontManifest("unavailable",JSONObject.of("font_manifest_hash","unavailable","font_mode","verified")));
    }

    @Test void verifiedRequiresExactHashAndMode() {
        assertDoesNotThrow(()->AipptRules.fontManifest(HASH,JSONObject.of("font_manifest_hash",HASH,"font_mode","verified")));
        assertThrows(AipptException.class,()->AipptRules.fontManifest(HASH,JSONObject.of("font_manifest_hash","b".repeat(64),"font_mode","verified")));
        assertThrows(AipptException.class,()->AipptRules.fontManifest(HASH,JSONObject.of("font_manifest_hash",HASH,"font_mode","estimate")));
    }

    @Test void missingOrInvalidIdentityCannotCommitAFontDependentResult() {
        assertThrows(AipptException.class,()->AipptRules.fontManifest(HASH,new JSONObject()));
        assertThrows(AipptException.class,()->AipptRules.fontManifest("A".repeat(64),JSONObject.of("font_manifest_hash","A".repeat(64),"font_mode","verified")));
        assertThrows(AipptException.class,()->AipptRules.fontManifest("arbitrary-version",new JSONObject()));
    }
}
