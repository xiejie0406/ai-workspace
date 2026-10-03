package com.ruoyi.fashion.application.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.ruoyi.fashion.application.product.port.FashionProductRepository;
import com.ruoyi.fashion.application.security.FashionDictionaryGuard;
import com.ruoyi.fashion.domain.product.*;
import com.ruoyi.fashion.domain.shared.*;
import com.ruoyi.fashion.infrastructure.persistence.FashionCatalogWriteLock;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class FashionProductListTest {
    @Test void loadsPricesOnceForTheWholePageAndPreservesUnmaintainedValues() {
        var repository=mock(FashionProductRepository.class);
        var first=product(11);var second=product(12);
        when(repository.search(any())).thenReturn(List.of(first,second));
        when(repository.count(any())).thenReturn(2L);
        when(repository.commercialPrices(Set.of(11L,12L))).thenReturn(Map.of(11L,new FashionProductRepository.CommercialPrices(new BigDecimal("0.00"),new BigDecimal("98.00"))));
        var service=new FashionProductService(repository,mock(FashionDictionaryGuard.class),mock(FashionIdGenerator.class),mock(FashionTimeSource.class),mock(FashionCatalogWriteLock.class),mock(TransactionTemplate.class));
        var page=service.search(null,null,null,null,1,20);
        assertEquals(new BigDecimal("0.00"),page.items().get(0).groupPrice());
        assertEquals(new BigDecimal("98.00"),page.items().get(0).vipPrice());
        assertNull(page.items().get(1).groupPrice());assertNull(page.items().get(1).vipPrice());
        verify(repository,times(1)).commercialPrices(Set.of(11L,12L));verify(repository,never()).findById(anyLong());
    }
    @Test void imageSummaryRequiresActiveConfirmedImagesForPermissionAndDoesNotTreatSourceLinksAsImages() {
        var product=product(11);Instant now=Instant.parse("2026-10-02T00:00:00Z");
        var allowed=new FashionProductImage("a","key-a","hash","main","upload",null,null,now,true,true,true,false,"active",1,now,20,20,"a.png");
        var revoked=new FashionProductImage("b","key-b","hash","main","upload",null,null,now,true,true,true,false,"revoked",1,now,20,20,"b.png");
        when(product.images()).thenReturn(List.of(allowed,revoked));when(product.mainImageKey()).thenReturn("key-a");
        var summary=ProductView.from(product).imageSummary();assertEquals(2,summary.totalCount());assertEquals(1,summary.activeCount());assertEquals(1,summary.proposalAllowedCount());assertEquals("allowed",summary.proposalPermission());assertEquals("confirmed",summary.reviewStatus());
        when(product.images()).thenReturn(List.of(revoked));assertEquals("denied",ProductView.from(product).imageSummary().proposalPermission());
        when(product.images()).thenReturn(List.of());when(product.attributes()).thenReturn(Map.of("sourceMainImageUrl","https://example.test/main.png"));assertEquals("none",ProductView.from(product).imageSummary().reviewStatus());
    }
    private FashionProduct product(long id) {
        var product=mock(FashionProduct.class);when(product.id()).thenReturn(id);when(product.status()).thenReturn(FashionProductStatus.ACTIVE);when(product.images()).thenReturn(List.of());when(product.attributes()).thenReturn(Map.of());return product;
    }
}
