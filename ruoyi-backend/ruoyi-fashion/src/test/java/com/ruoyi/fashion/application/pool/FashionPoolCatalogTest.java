package com.ruoyi.fashion.application.pool;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.ruoyi.fashion.application.pool.PoolModels.*;
import java.util.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import com.ruoyi.fashion.infrastructure.persistence.pool.FashionPoolCatalog;
import com.ruoyi.fashion.infrastructure.persistence.FashionCatalogWriteLock;
import com.ruoyi.fashion.application.product.port.FashionProductRepository;
import com.ruoyi.fashion.domain.product.*;
import com.ruoyi.system.service.ISysConfigService;
import tools.jackson.databind.json.JsonMapper;
class FashionPoolCatalogTest {
 record Fixture(String color,String size,Integer qty,Instant asOf){Fixture(String size,Integer qty,Instant asOf){this("BLACK",size,qty,asOf);}}
 FashionPoolCatalog setup(List<Fixture> fixtures) throws Exception {
  NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);FashionProductRepository products=mock(FashionProductRepository.class);ISysConfigService config=mock(ISysConfigService.class);when(config.selectConfigByKey(anyString())).thenReturn("24");when(jdbc.queryForObject(anyString(),anyMap(),eq(Long.class))).thenReturn(0L);
  when(jdbc.query(anyString(),anyMap(),org.mockito.ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(call->{RowMapper<Object> mapper=call.getArgument(2);List<Object> out=new ArrayList<>();for(int i=0;i<fixtures.size();i++){Fixture f=fixtures.get(i);long id=i+1;FashionProduct p=mock(FashionProduct.class);when(p.id()).thenReturn(id);when(p.attributes()).thenReturn(Map.of("mainSkuCode","M1","categoryLeafCode","C1","style","sport","fabricComposition","cotton 100%","fit","loose","scene","outdoor"));when(p.name()).thenReturn("test");when(p.sourceCode()).thenReturn("manual");when(p.currency()).thenReturn("CNY");when(p.taxMode()).thenReturn("included");when(p.sizeCode()).thenReturn(f.size());when(p.colorCode()).thenReturn(f.color());when(p.mainImageKey()).thenReturn("unbound-main.png");when(p.images()).thenReturn(List.of());when(products.findById(id)).thenReturn(Optional.of(p));ResultSet rs=mock(ResultSet.class);when(rs.getLong("id")).thenReturn(id);when(rs.getBigDecimal("group_price")).thenReturn(BigDecimal.TEN);when(rs.getBigDecimal("vip_price")).thenReturn(BigDecimal.ONE);when(rs.getObject("available_qty")).thenReturn(f.qty());when(rs.getLong("available_qty")).thenReturn(f.qty()==null?0L:f.qty().longValue());when(rs.getTimestamp("as_of")).thenReturn(f.asOf()==null?null:Timestamp.from(f.asOf()));out.add(mapper.mapRow(rs,i));}return out;});
  return new FashionPoolCatalog(jdbc,products,mock(FashionCatalogWriteLock.class),config,mock(com.ruoyi.fashion.application.stock.FashionWarehouseService.class));
 }
 GroupFilter filter(String extra){return JsonMapper.builder().build().readValue("{\"categoryLeafCodes\":[\"C1\"],\"warehouseCode\":\"MAIN\""+extra+"}",GroupFilter.class);}
 @Test void staleInventoryCannotBeSoldAsPositive() throws Exception {var c=setup(List.of(new Fixture("S",10,Instant.now().minusSeconds(172800))));assertTrue(c.find(filter(",\"stockStatus\":\"positive\"")).isEmpty());var all=c.find(filter(""));assertTrue(all.get(0).stockUnknown());assertNull(all.get(0).availableQty());assertNull(all.get(0).variants().get(0).availableQty());}
 @Test void unknownIsDistinctFromZero() throws Exception {var c=setup(List.of(new Fixture("S",null,null)));assertTrue(c.find(filter(",\"stockStatus\":\"zero\"")).isEmpty());assertEquals(1,c.find(filter(",\"stockStatus\":\"unknown\"")).size());}
 @Test void allSizesRequireEveryRequestedSizeAvailable() throws Exception {var c=setup(List.of(new Fixture("S",10,Instant.now()),new Fixture("M",0,Instant.now())));assertTrue(c.find(filter(",\"sizes\":[\"S\",\"M\"],\"requireAllSizes\":true")).isEmpty());assertEquals(1,c.find(filter(",\"sizes\":[\"S\"],\"requireAllSizes\":true")).size());}
 @Test void clothingAttributesAndImageFiltersAreApplied() throws Exception {var c=setup(List.of(new Fixture("S",10,Instant.now())));assertEquals(1,c.find(filter(",\"styles\":[\"sport\"],\"fits\":[\"loose\"],\"scenes\":[\"outdoor\"],\"fabricComposition\":\"cotton\"")).size());assertTrue(c.find(filter(",\"hasMainImage\":true")).isEmpty());assertTrue(c.find(filter(",\"imagesConfirmed\":true")).isEmpty());assertTrue(c.find(filter(",\"styles\":[\"formal\"]")).isEmpty());}
 @Test void excessiveCategorySkuCountIsRejectedBeforeLoadingProducts(){NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);when(jdbc.queryForObject(anyString(),anyMap(),eq(Long.class))).thenReturn(0L);when(jdbc.queryForObject(startsWith("select count(*) from fq_product p"),anyMap(),eq(Long.class))).thenReturn(5001L);FashionProductRepository products=mock(FashionProductRepository.class);var catalog=new FashionPoolCatalog(jdbc,products,mock(FashionCatalogWriteLock.class),mock(ISysConfigService.class),mock(com.ruoyi.fashion.application.stock.FashionWarehouseService.class));assertThrows(com.ruoyi.common.exception.ServiceException.class,()->catalog.find(filter("")));verifyNoInteractions(products);}

 @Test void advancedUnknownKeysAreRejectedBeforeSql(){var catalog=new FashionPoolCatalog(mock(NamedParameterJdbcTemplate.class),mock(FashionProductRepository.class),mock(FashionCatalogWriteLock.class),mock(ISysConfigService.class),mock(com.ruoyi.fashion.application.stock.FashionWarehouseService.class));assertThrows(com.ruoyi.common.exception.ServiceException.class,()->catalog.find(filter(",\"attributeFilters\":{\"password\":\"x\"}")));}
 @Test void demandCannotUseUnknownOrInsufficientStock() throws Exception {assertTrue(setup(List.of(new Fixture("S",10,Instant.now()))).find(filter(",\"quantity\":11")).isEmpty());assertTrue(setup(List.of(new Fixture("S",null,null))).find(filter(",\"quantity\":1")).isEmpty());assertEquals(1,setup(List.of(new Fixture("S",10,Instant.now()))).find(filter(",\"quantity\":10")).size());}

 @Test void allSizesCannotBeAssembledFromDifferentColors() throws Exception {var catalog=setup(List.of(new Fixture("BLUE","S",10,Instant.now()),new Fixture("RED","M",10,Instant.now())));assertTrue(catalog.find(filter(",\"sizes\":[\"S\",\"M\"],\"requireAllSizes\":true")).isEmpty());}

}
