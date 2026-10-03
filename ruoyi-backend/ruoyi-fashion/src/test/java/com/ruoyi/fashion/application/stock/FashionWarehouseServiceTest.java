package com.ruoyi.fashion.application.stock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.application.settings.FashionSettingsService;
import com.ruoyi.fashion.infrastructure.persistence.FashionCatalogWriteLock;
import com.ruoyi.system.service.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
class FashionWarehouseServiceTest {
    private final ISysDictDataService dictionaries=mock(ISysDictDataService.class);
    private final ISysConfigService config=mock(ISysConfigService.class);
    private final FashionSettingsService settings=mock(FashionSettingsService.class);
    private final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
    private final FashionCatalogWriteLock lock=mock(FashionCatalogWriteLock.class);
    private final FashionWarehouseService service=new FashionWarehouseService(dictionaries,config,settings,jdbc,lock);
    FashionWarehouseServiceTest(){when(lock.executeLocked(anyInt(),any())).thenAnswer(i->((Supplier<?>)i.getArgument(1)).get());when(config.selectConfigByKey(anyString())).thenReturn("MAIN");}
    SysDictData entry(String code,String status){var row=new SysDictData();row.setDictCode(1L);row.setDictType("fashion_warehouse");row.setDictValue(code);row.setDictLabel("仓库"+code);row.setStatus(status);return row;}
    @Test void emptyEnabledWarehouseIsSelectableWithoutAnyStockRows(){when(dictionaries.selectDictDataList(any())).thenReturn(List.of(entry("EMPTY","0"),entry("OLD","1")));assertEquals(List.of(Map.of("code","EMPTY","name","仓库EMPTY")),service.activeOptions());verifyNoInteractions(jdbc);}
    @Test void defaultCannotBeDisabledAndCodeCannotBeRenamed(){when(dictionaries.selectDictDataList(any())).thenReturn(List.of(entry("MAIN","0")));assertThrows(ServiceException.class,()->service.status("MAIN","inactive","7"));assertThrows(ServiceException.class,()->service.update("MAIN",new FashionWarehouseService.Write("RENAMED","新名称","active",0L,null),"7"));verify(dictionaries,never()).updateDictData(any());}
    @Test void defaultSwitchRequiresEnabledTarget(){when(dictionaries.selectDictDataList(any())).thenReturn(List.of(entry("OLD","1")));assertThrows(ServiceException.class,()->service.makeDefault("OLD","7"));verifyNoInteractions(settings);}
    @Test void warehouseListsZeroAndUnknownTimeWithoutInventingStock(){when(dictionaries.selectDictDataList(any())).thenReturn(List.of(entry("MAIN","0")));when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of());var row=service.list().get(0);assertEquals(0L,row.get("stockSkuCount"));assertNull(row.get("lastStockAsOf"));assertEquals(true,row.get("isDefault"));}
}
