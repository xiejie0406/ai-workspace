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
}
