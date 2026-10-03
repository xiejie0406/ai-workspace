package com.ruoyi.fashion.application.pool;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.common.exception.ServiceException;
import static com.ruoyi.fashion.application.pool.PoolModels.*;
class PoolConditionsTest {
    private final JsonMapper json=JsonMapper.builder().build();
    GroupFilter filter(String value){return json.readValue(value,GroupFilter.class);}
    @Test void serverResolvesInheritanceAndKeepsHiddenDrafts(){
        var one=filter("{\"categoryLeafCodes\":[\"TOP\"],\"genders\":[\"female\"],\"quantity\":2}");
        var hidden=filter("{\"keyword\":\"第三组原值\",\"quantity\":77}");
        var total=filter("{\"genders\":[\"male\"],\"quantity\":100}");
        var modes=List.of(Map.of("quantity","follow","genders","independent"),Map.of("quantity","follow","genders","follow"),Map.<String,String>of(),Map.<String,String>of());
        var request=new Create("t",List.of(one,one),"group",null,null,2,List.of(one,one,hidden,PoolConditions.empty()),total,modes);
        var resolved=PoolConditions.resolve(request);
        assertEquals(100L,resolved.groups().get(0).quantity());assertEquals(List.of("female"),resolved.groups().get(0).genders());assertEquals(List.of("male"),resolved.groups().get(1).genders());assertEquals(77L,resolved.draftGroups().get(2).quantity());
        var restored=PoolConditions.resolve(new Create("t",resolved.groups(),"group",null,null,3,resolved.draftGroups(),total,modes));assertEquals("第三组原值",restored.groups().get(2).keyword());
    }
    @Test void newSingleGroupAndUnregisteredInheritanceAreRejected(){var empty=PoolConditions.empty();assertThrows(ServiceException.class,()->PoolConditions.resolve(new Create("t",List.of(empty),null,null,null)));assertThrows(ServiceException.class,()->PoolConditions.resolve(new Create("t",List.of(empty,empty),null,null,null,2,List.of(empty,empty,empty,empty),empty,List.of(Map.of("password","follow"),Map.of(),Map.of(),Map.of()))));}
    @Test void inventoryRankingUsesBottleneckAndUnknownLast(){var a=PoolCombinationsTest.item("a","CNY",java.math.BigDecimal.ONE);var b=PoolCombinationsTest.item("b","CNY",java.math.BigDecimal.ONE);var pool=PoolCombinationsTest.pool(List.of(new Group("a",List.of(a)),new Group("b",List.of(b))),null,null);var result=PoolCombinations.enumerate(pool).get(0);assertEquals(10L,result.availableSets());var unknown=new Combination("x",List.of(a,b),null,null,null,null,true,"CNY","included",null,null);var rows=new ArrayList<>(List.of(unknown,result));rows.sort(PoolCombinations.comparator("stockDesc"));assertEquals(result,rows.get(0));}
    MainSku stock(String id,Long qty){return new MainSku(id,id,id,"manual","leaf",null,null,null,"1",null,java.math.BigDecimal.ONE,java.math.BigDecimal.ONE,null,null,qty,qty==null,"CNY","included",List.of());}
    @Test void twoHundredOneTwentyFiveHundredSupplyOnlyOneTwentySets(){var p=PoolCombinationsTest.pool(List.of(new Group("a",List.of(stock("a",200L))),new Group("b",List.of(stock("b",120L))),new Group("c",List.of(stock("c",500L)))),null,null);assertEquals(120L,PoolCombinations.enumerate(p).get(0).availableSets());}
    @Test void zeroStockDoesNotMeetEvenOneSetAndMultipliersAreExplicitlyRejected(){var p=PoolCombinationsTest.pool(List.of(new Group("a",List.of(stock("a",0L))),new Group("b",List.of(stock("b",100L)))),null,null);assertFalse(PoolCombinations.enumerate(p).get(0).meetsDemand());var f=filter("{\"unitsPerSet\":2}");var request=new Create("t",List.of(f,f),"group",null,null);var withUnits=new Pool("p","t",request,p.groups(),1,1,"t");assertThrows(ServiceException.class,()->PoolCombinations.enumerate(withUnits));}
    @Test void missingStockLeavesDemandUndeterminedInsteadOfInsufficient(){
        var p=PoolCombinationsTest.pool(List.of(new Group("a",List.of(stock("a",null))),new Group("b",List.of(stock("b",100L)))),null,null);
        var result=PoolCombinations.enumerate(p).get(0);
        assertTrue(result.stockUnknown());assertNull(result.availableSets());assertNull(result.meetsDemand());
    }
    @Test void partialStockDoesNotProveEitherSufficientOrZero(){
        for(long qty:List.of(0L,100L)){
            var partial=new MainSku("a","a","a","manual","leaf",null,null,null,"1",null,java.math.BigDecimal.ONE,java.math.BigDecimal.ONE,null,null,qty,true,"CNY","included",List.of());
            var p=PoolCombinationsTest.pool(List.of(new Group("a",List.of(partial)),new Group("b",List.of(stock("b",100L)))),null,null);
            var result=PoolCombinations.enumerate(p).get(0);assertNull(result.availableSets());assertNull(result.meetsDemand());
        }
    }
    @Test void explicitZeroRemainsInsufficientEvenWhenAnotherPositionIsUnknown(){
        var p=PoolCombinationsTest.pool(List.of(new Group("a",List.of(stock("a",0L))),new Group("b",List.of(stock("b",null)))),null,null);
        var result=PoolCombinations.enumerate(p).get(0);assertTrue(result.stockUnknown());assertNull(result.availableSets());assertEquals(Boolean.FALSE,result.meetsDemand());
    }
    @Test void hardQuantityStillRejectsUnknownAndInsufficientStock(){
        var demand=filter("{\"quantity\":100}");var unrestricted=PoolConditions.empty();
        for(Long qty:Arrays.asList(null,0L,99L,100L)){
            var groups=List.of(new Group("a",List.of(stock("a",qty))),new Group("b",List.of(stock("b",200L))));
            var request=new Create("t",List.of(demand,unrestricted),"group",null,null);
            var result=PoolCombinations.enumerate(new Pool("p","t",request,groups,1,1,"t"));
            if(qty==null||qty<100)assertTrue(result.isEmpty());else{assertEquals(1,result.size());assertEquals(Boolean.TRUE,result.get(0).meetsDemand());}
        }
    }
}
