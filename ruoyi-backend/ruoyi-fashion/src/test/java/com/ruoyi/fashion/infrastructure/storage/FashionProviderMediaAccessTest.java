package com.ruoyi.fashion.infrastructure.storage;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.application.product.port.FashionProductRepository;
import com.ruoyi.fashion.domain.product.FashionProduct;
import com.ruoyi.fashion.domain.product.FashionProductImage;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class FashionProviderMediaAccessTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final FashionProductRepository products = mock(FashionProductRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final FashionProviderMediaAccess access = new FashionProviderMediaAccess(jdbc,mapper,products);
    private final String task = "00000000-0000-0000-0000-000000000001";
    @Test void revocationIsCheckedAgainstSelectedSkuEvenWhenAnotherSkuSharesTheImage() {
        String key = "materials/" + "a".repeat(64) + "/original.png";
        var input = mapper.createObjectNode();var item = input.putArray("products").addObject();
        item.put("productId","41");item.put("object_key",key);item.put("sha256","a".repeat(64));item.put("garment_position","top");item.put("visualVersion",1);
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of(Map.of("owner_id",42L,"input_json",mapper.writeValueAsString(input),"status","running")));
        var product = mock(FashionProduct.class);var image = mock(FashionProductImage.class);
        when(products.findById(41)).thenReturn(Optional.of(product));when(product.visualVersion()).thenReturn(1L);when(product.images()).thenReturn(List.of(image));
        when(image.status()).thenReturn("active");when(image.objectKey()).thenReturn(key);when(image.sha256()).thenReturn("a".repeat(64));when(image.allowAi()).thenReturn(true,false);
        assertDoesNotThrow(() -> access.requireCurrentPermission(key,task));
        assertThrows(ServiceException.class, () -> access.requireCurrentPermission(key,task));
        verify(products,times(2)).findById(41);verifyNoMoreInteractions(products);
    }
    @Test void modelAndObjectMustBelongToThePersistedTask() {
        String model = "proposal-models/42/" + "a".repeat(64) + ".png";
        var input = mapper.createObjectNode();input.putObject("model").put("objectKey",model);input.putArray("products");
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of(Map.of("owner_id",42L,"input_json",mapper.writeValueAsString(input),"status","queued")));
        assertDoesNotThrow(() -> access.requireCurrentPermission(model,task));
        assertThrows(ServiceException.class, () -> access.requireCurrentPermission(model.replace("/42/","/43/"),task));
        assertThrows(ServiceException.class, () -> access.requireCurrentPermission("materials/"+"a".repeat(64)+"/original.png",task));
        verifyNoInteractions(products);
    }
    @Test void immutableImageBytesAreCheckedForBothObjectTypes() {
        byte[] bytes = {1,2,3};
        String hash = FashionHashing.sha256(bytes);
        for (String key : new String[]{"materials/"+hash+"/original.png", "proposal-models/42/"+hash+".png"}) {
            assertDoesNotThrow(() -> access.verifyBytes(key, bytes));
            assertThrows(ServiceException.class, () -> access.verifyBytes(key, new byte[]{4,5,6}));
        }
        verifyNoInteractions(jdbc,products);
    }
}
