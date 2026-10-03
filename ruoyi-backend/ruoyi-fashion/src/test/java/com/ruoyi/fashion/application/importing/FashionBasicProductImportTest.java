package com.ruoyi.fashion.application.importing;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import com.ruoyi.fashion.domain.product.FashionProduct;
import com.ruoyi.common.exception.ServiceException;
class FashionBasicProductImportTest {
    @Test void basicThirteenColumnsDoNotRequireJdOrExtensionHeaders(){var parser=new FashionProductImportParser();var fields=parser.selectedMapping(Map.of(),Set.copyOf(parser.defaultFields()));var values=Map.ofEntries(Map.entry("sourceCode","INTERNAL"),Map.entry("skuCode","00012"),Map.entry("styleCode","ST"),Map.entry("name","衣服"),Map.entry("categoryCode","TOP"),Map.entry("colorCode","BLACK"),Map.entry("colorName","黑"),Map.entry("sizeCode","M"),Map.entry("sizeSystem","LETTER"),Map.entry("unit","件"),Map.entry("season","四季"));String csv=String.join(",",fields.values())+"\n"+fields.keySet().stream().map(k->values.getOrDefault(k,"")).collect(java.util.stream.Collectors.joining(","))+"\n";var row=parser.parse("basic.csv",csv.getBytes(StandardCharsets.UTF_8),fields).get(0);assertTrue(row.errors().isEmpty());assertEquals("00012",row.values().get("skuCode"));assertFalse(row.values().containsKey("jdUrl"));}
    @Test void missingOptionalAndBlankCannotEraseExistingAttributes(){FashionProduct old=mock(FashionProduct.class);when(old.brand()).thenReturn("品牌");when(old.jdUrl()).thenReturn("https://example.com/a");when(old.attributes()).thenReturn(Map.of("gender","male","materialNote","ignored","mainSkuCode","00012"));var values=new HashMap<String,String>();values.put("brand",null);values.put("gender","female");var normalized=FashionProductImportService.normalize(values,Set.of(),old);assertEquals("品牌",normalized.get("brand"));assertEquals("https://example.com/a",normalized.get("jdUrl"));assertEquals("00012",normalized.get("mainSkuCode"));assertEquals("female",normalized.get("gender"));var cleared=FashionProductImportService.normalize(values,Set.of("brand"),old);assertNull(cleared.get("brand"));}
    @Test void technicalFieldsAndMissingRequiredSelectionAreRejected(){var parser=new FashionProductImportParser();assertThrows(ServiceException.class,()->parser.selectedMapping(Map.of(),Set.of("skuCode")));assertThrows(ServiceException.class,()->parser.resolvedMapping(Map.of("rowVersion","版本")));}
    @Test void emptyRequiredSizeSystemProducesRowErrorInsteadOfNullPointer() throws Exception {
        var guard=mock(com.ruoyi.fashion.application.security.FashionDictionaryGuard.class);
        var service=new FashionProductImportService(null,null,null,guard,null,null,null,null,null,null);
        var errors=new ArrayList<ImportRowError>();
        var method=FashionProductImportService.class.getDeclaredMethod("validateRow",Map.class,List.class);method.setAccessible(true);
        method.invoke(service,new HashMap<String,String>(),errors);
        assertTrue(errors.stream().anyMatch(e->e.field().equals("sizeSystem")&&e.code().equals("invalid_enum")));
    }
}
