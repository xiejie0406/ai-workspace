package com.ruoyi.fashion.application.pool;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.fashion.application.pool.PoolModels.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.infrastructure.persistence.pool.*;
class FashionPoolServiceTest {
 final JdbcFashionPoolRepository repository=mock(JdbcFashionPoolRepository.class);
 final FashionPoolCatalog catalog=mock(FashionPoolCatalog.class);
 final FashionPoolService service=new FashionPoolService(repository,catalog);
 final Pool pool=PoolCombinationsTest.pool(List.of(PoolCombinationsTest.group("a",3),PoolCombinationsTest.group("b",4)),null,null);
 Selection selection(boolean all,List<String> selected,List<String> excluded,List<String> ordered){return new Selection("pool",1,all,selected,excluded,Map.of(),ordered,Map.of());}
 @Test void crossPageSelectionAndOrderSurviveRead(){when(repository.get("pool",7)).thenReturn(pool);when(repository.selection("pool",7)).thenReturn(selection(false,List.of("0-0","2-3"),List.of(),List.of("2-3","0-0")));var page=service.combinations("pool",7,1,1,"identity",null,true);assertEquals(2,page.get("total"));assertEquals("2-3",((List<Combination>)page.get("items")).get(0).id());assertEquals("0-0",((List<Combination>)service.combinations("pool",7,2,1,"identity",null,true).get("items")).get(0).id());}
 @Test void selectAllWithExclusionsIsNotCurrentPageOnly(){when(repository.get("pool",7)).thenReturn(pool);when(repository.selection("pool",7)).thenReturn(selection(true,List.of(),List.of("1-1"),List.of()));assertEquals(11,service.requireExport("pool",7,1).combinations().size());}
 @Test void staleExportVersionIsRejected(){when(repository.get("pool",7)).thenReturn(pool);when(repository.selection("pool",7)).thenReturn(selection(true,List.of(),List.of(),List.of()));assertThrows(ServiceException.class,()->service.requireExport("pool",7,0));}
 @Test void foreignCombinationCannotBeSaved(){when(repository.get("pool",7)).thenReturn(pool);assertThrows(ServiceException.class,()->service.save("pool",7,selection(false,List.of("99-99"),List.of(),List.of())));verify(repository,never()).save(anyString(),anyLong(),any());}
 @Test void readRequiresOwnerAccess(){when(repository.get("pool",8)).thenThrow(new ServiceException("no access"));assertThrows(ServiceException.class,()->service.selection("pool",8));verify(repository,never()).selection(anyString(),anyLong());}
 @Test void legacySingleGroupRemainsReadableButCannotSaveOrStartNewDelivery(){var legacy=PoolCombinationsTest.pool(List.of(PoolCombinationsTest.group("a",3)),null,null);when(repository.get("pool",7)).thenReturn(legacy);when(repository.selection("pool",7)).thenReturn(selection(true,List.of(),List.of(),List.of()));assertSame(legacy,service.get("pool",7));assertEquals(3,service.combinations("pool",7,1,20,"identity",null,false).get("total"));assertTrue(assertThrows(ServiceException.class,()->service.save("pool",7,selection(true,List.of(),List.of(),List.of()))).getMessage().contains("只读"));assertTrue(assertThrows(ServiceException.class,()->service.requireExport("pool",7,1)).getMessage().contains("只读"));verify(repository,never()).save(anyString(),anyLong(),any());}
 @Test void historicalPoolPageAndExportKeepUnknownDemandUndetermined(){
  var unknown=new MainSku("u","u","unknown","manual","leaf",null,null,null,"1",null,java.math.BigDecimal.ONE,java.math.BigDecimal.ONE,null,null,null,true,"CNY","included",List.of());
  var historical=PoolCombinationsTest.pool(List.of(new Group("a",List.of(unknown)),PoolCombinationsTest.group("b",1)),null,null);
  var json=tools.jackson.databind.json.JsonMapper.builder().build();
  when(repository.get("pool",7)).thenReturn(json.readValue(json.writeValueAsString(historical),Pool.class));
  when(repository.selection("pool",7)).thenReturn(selection(true,List.of(),List.of(),List.of()));
  var page=service.combinations("pool",7,1,20,"identity",null,false);
  var result=((List<Combination>)page.get("items")).get(0);assertNull(result.meetsDemand());assertNull(result.availableSets());
  assertNull(service.requireExport("pool",7,1).combinations().get(0).meetsDemand());
 }
}
