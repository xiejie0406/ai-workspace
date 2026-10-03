package com.ruoyi.fashion.application.pool;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.fashion.application.pool.PoolModels.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
class PoolCombinationsTest {
 static MainSku item(String id,String currency,BigDecimal price){return new MainSku(id,id,id,"manual","leaf","brand","item","article","1",null,price,price,null,null,10L,false,currency,"included",List.of());}
 static Group group(String prefix,int size){List<MainSku> rows=new ArrayList<>();for(int i=0;i<size;i++)rows.add(item(prefix+i,"CNY",BigDecimal.valueOf(i+1)));return new Group(prefix,rows);}
 static Pool pool(List<Group> groups,BigDecimal min,BigDecimal max){return new Pool("pool","test",new Create("test",List.of(),"group",min,max),groups,PoolCombinations.rawCount(groups),0,"2026-09-26T00:00:00Z");}
 @Test void completeCartesianProductHasNoDuplicates(){Pool p=pool(List.of(group("a",2),group("b",3),group("c",4),group("d",5)),null,null);var rows=PoolCombinations.enumerate(p);assertEquals(120,p.rawCount());assertEquals(120,rows.size());assertEquals(120,rows.stream().map(Combination::id).distinct().count());}
 @Test void overlappingGroupsCannotUseSameMasterTwice(){Group g=group("a",3);var p=pool(List.of(g,g),null,null);assertEquals(9,p.rawCount());assertEquals(6,PoolCombinations.enumerate(p).size());}
 @Test void totalBudgetFiltersWithoutTruncation(){var p=pool(List.of(group("a",3),group("b",3)),new BigDecimal("4"),new BigDecimal("4"));assertEquals(3,PoolCombinations.enumerate(p).size());}
 @Test void missingVipNeverFallsBackToGroupPrice(){var p=new Pool("id","t",new Create("t",List.of(),"vip",null,new BigDecimal("10")),List.of(group("a",2)),2,0,"t");assertTrue(PoolCombinations.enumerate(p).isEmpty());}
 @Test void priceIntervalDoesNotInventAnAvailableVariant(){var a=item("a","CNY",BigDecimal.TEN);var variants=List.of(new Variant("1","s1","c","c","s",BigDecimal.TEN,null,1L,null,null,List.of(),1),new Variant("2","s2","c","c","m",new BigDecimal("100"),null,1L,null,null,List.of(),1));var item=new MainSku(a.key(),a.mainSkuCode(),a.name(),a.sourceCode(),a.categoryLeafCode(),a.brand(),a.itemNo(),a.articleNo(),a.productId(),null,BigDecimal.TEN,new BigDecimal("100"),null,null,2L,false,"CNY","included",variants);assertTrue(PoolCombinations.enumerate(pool(List.of(new Group("a",List.of(item))),new BigDecimal("40"),new BigDecimal("50"))).isEmpty());}
 @Test void mixedCurrenciesAreExcluded(){var p=pool(List.of(new Group("a",List.of(item("a","CNY",BigDecimal.ONE))),new Group("b",List.of(item("b","USD",BigDecimal.ONE)))),null,null);assertTrue(PoolCombinations.enumerate(p).isEmpty());}
 @Test void excessivePoolFailsExplicitly(){assertThrows(ServiceException.class,()->pool(List.of(group("a",101),group("b",100),group("c",100)),null,null));}
 @Test void excessiveVariantPriceWorkFailsWithoutReturningPartialResults(){var variant=new Variant("1","s","c","c","S",BigDecimal.ONE,null,1L,null,null,List.of(),1);List<MainSku> rows=new ArrayList<>();for(int i=0;i<500;i++)rows.add(new MainSku("m"+i,"m"+i,"name","source","cat",null,null,null,"1",null,BigDecimal.ONE,BigDecimal.ONE,null,null,1L,false,"CNY","included",Collections.nCopies(5000,variant)));var p=pool(List.of(new Group("one",rows)),null,BigDecimal.TEN);assertThrows(ServiceException.class,()->PoolCombinations.enumerate(p));}
 @Test void orderingIsStableAndMissingPricesLast(){var p=pool(List.of(new Group("a",List.of(item("a","CNY",null),item("b","CNY",BigDecimal.TEN),item("c","CNY",BigDecimal.ONE)))),null,null);var rows=PoolCombinations.enumerate(p);rows.sort(PoolCombinations.comparator("groupDesc"));assertEquals("1",rows.get(0).id());assertEquals("0",rows.get(2).id());}
 @Test void demandJsonRetainsBooleansAndSupportsNullAndLegacyMissingField(){
  var json=tools.jackson.databind.json.JsonMapper.builder().build();
  for(Boolean demand:Arrays.asList(Boolean.TRUE,Boolean.FALSE,null)){
   var combination=new Combination("x",List.of(),null,null,null,null,demand==null,"CNY","included",null,demand);
   var wire=json.writeValueAsString(combination);assertEquals(demand,json.readValue(wire,Combination.class).meetsDemand());
   if(demand==null)assertTrue(json.readTree(wire).path("meetsDemand").isNull());
  }
  assertNull(json.readValue("{\"id\":\"legacy\",\"items\":[],\"stockUnknown\":true}",Combination.class).meetsDemand());
  assertNull(new Combination("legacy",List.of(),null,null,null,null,true,"CNY","included").meetsDemand());
 }
}
