package com.ruoyi.fashion.infrastructure.persistence.pool;
import static com.ruoyi.fashion.application.pool.PoolModels.*;
import java.util.*;
import java.math.BigDecimal;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import com.ruoyi.fashion.domain.product.*;
import com.ruoyi.fashion.application.product.port.FashionProductRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository @FashionModuleEnabled
public class FashionPoolCatalog {
 private final NamedParameterJdbcTemplate jdbc;private final FashionProductRepository products;private final com.ruoyi.fashion.infrastructure.persistence.FashionCatalogWriteLock writeLock;private final com.ruoyi.system.service.ISysConfigService config;
 public FashionPoolCatalog(@Qualifier("fashionJdbcTemplate") NamedParameterJdbcTemplate jdbc,FashionProductRepository products,com.ruoyi.fashion.infrastructure.persistence.FashionCatalogWriteLock writeLock,com.ruoyi.system.service.ISysConfigService config){this.jdbc=jdbc;this.products=products;this.writeLock=writeLock;this.config=config;}
 private record Item(FashionProduct p,BigDecimal groupPrice,BigDecimal vipPrice,Long stock,boolean stale){}
 public List<MainSku> find(GroupFilter filter){
  if(filter==null)throw new ServiceException("筛选组不能为空");
  validateList(filter.categoryLeafCodes());validateList(filter.seasons());validateList(filter.genders());validateList(filter.brands());validateList(filter.itemNos());validateList(filter.articleNos());validateList(filter.mainSkuCodes());validateList(filter.colors());validateList(filter.sizes());validateList(filter.styles());validateList(filter.fits());validateList(filter.scenes());
  if(filter.fabricComposition()!=null&&filter.fabricComposition().length()>1000)throw new ServiceException("面料筛选最多1000字");
  if(filter.stockFreshness()!=null&&!Set.of("all","fresh","stale").contains(filter.stockFreshness()))throw new ServiceException("库存时效筛选无效");
  if(Boolean.TRUE.equals(filter.requireAllSizes())&&(filter.sizes()==null||filter.sizes().isEmpty()))throw new ServiceException("齐码筛选请先选择所需尺码");
  if(filter.keyword()!=null&&filter.keyword().length()>200||filter.label()!=null&&filter.label().length()>100||filter.warehouseCode()!=null&&filter.warehouseCode().length()>100)throw new ServiceException("筛选文字过长");
  if(filter.categoryLeafCodes()==null||filter.categoryLeafCodes().isEmpty())throw new ServiceException("每组请选择至少一个三级分类");
  range(filter.groupPriceMin(),filter.groupPriceMax());range(filter.vipPriceMin(),filter.vipPriceMax());
  if(filter.stockMin()!=null&&filter.stockMin()<0||filter.stockMax()!=null&&filter.stockMax()<0||filter.stockMin()!=null&&filter.stockMax()!=null&&filter.stockMin()>filter.stockMax())throw new ServiceException("库存范围无效");
  if(filter.stockStatus()!=null&&!Set.of("all","positive","zero","unknown").contains(filter.stockStatus()))throw new ServiceException("库存状态无效");
  validateClassification();validateMasters();
  Map<String,Object> params=Map.of("categories",filter.categoryLeafCodes(),"warehouse",filter.warehouseCode()==null?"":filter.warehouseCode());
  Long candidates=jdbc.queryForObject("select count(*) from fq_product p where p.status='active' and JSON_UNQUOTE(JSON_EXTRACT(p.attributes_json,'$.categoryLeafCode')) in (:categories)",params,Long.class);if(candidates!=null&&candidates>5000)throw new ServiceException("该组分类包含超过5000条颜色尺码SKU，请减少三级分类范围后重试");
  int hours=24;try{int configured=Integer.parseInt(config.selectConfigByKey("fashion.stock.freshnessHours"));if(configured>=1&&configured<=168)hours=configured;}catch(NumberFormatException ignored){}final java.time.Instant cutoff=java.time.Instant.now().minus(java.time.Duration.ofHours(hours));
  List<Item> rows=jdbc.query("select p.id,p.group_price,p.vip_price,s.available_qty,s.as_of from fq_product p left join fq_stock s on s.product_id=p.id and s.warehouse_code=:warehouse where p.status='active' and JSON_UNQUOTE(JSON_EXTRACT(p.attributes_json,'$.categoryLeafCode')) in (:categories) order by p.source_code,p.id",params,(rs,n)->new Item(products.findById(rs.getLong("id")).orElseThrow(),rs.getBigDecimal("group_price"),rs.getBigDecimal("vip_price"),rs.getObject("available_qty")==null?null:rs.getLong("available_qty"),rs.getObject("available_qty")!=null&&(rs.getTimestamp("as_of")==null||rs.getTimestamp("as_of").toInstant().isBefore(cutoff))));
  Map<String,List<Item>> grouped=new TreeMap<>();
  for(Item x:rows){FashionProduct p=x.p();Map<String,String>a=p.attributes();String main=a.get("mainSkuCode");if(main==null||main.isBlank())continue;
   if(!match(filter.seasons(),p.season())||!match(filter.brands(),p.brand())||!match(filter.genders(),a.get("gender"))||!match(filter.itemNos(),a.get("itemNo"))||!match(filter.articleNos(),a.get("articleNo"))||!match(filter.mainSkuCodes(),main)||!match(filter.colors(),p.colorCode())||!match(filter.sizes(),p.sizeCode()))continue;
   if(!match(filter.styles(),a.get("style"))||!match(filter.fits(),a.get("fit"))||!match(filter.scenes(),a.get("scene")))continue;
   if(filter.fabricComposition()!=null&&!filter.fabricComposition().isBlank()&&(a.get("fabricComposition")==null||!a.get("fabricComposition").contains(filter.fabricComposition().trim())))continue;
   boolean hasImage=p.mainImageKey()!=null&&!p.mainImageKey().isBlank();boolean confirmedImage=hasImage&&p.images().stream().anyMatch(i->Objects.equals(i.objectKey(),p.mainImageKey())&&"active".equals(i.status())&&i.confirmedAt()!=null&&(i.allowInternal()||i.allowProposal()));
   if(filter.hasMainImage()!=null&&filter.hasMainImage()!=hasImage||filter.imagesConfirmed()!=null&&filter.imagesConfirmed()!=confirmedImage)continue;
   if(filter.keyword()!=null&&!filter.keyword().isBlank()&&!p.name().toLowerCase(Locale.ROOT).contains(filter.keyword().trim().toLowerCase(Locale.ROOT)))continue;
   if(!money(x.groupPrice(),filter.groupPriceMin(),filter.groupPriceMax())||!money(x.vipPrice(),filter.vipPriceMin(),filter.vipPriceMax()))continue;
   grouped.computeIfAbsent(p.sourceCode()+":"+main,k->new ArrayList<>()).add(x);
  }
  List<MainSku> result=new ArrayList<>();for(var e:grouped.entrySet()){List<Item> variants=e.getValue();
   if(Boolean.TRUE.equals(filter.requireAllSizes())){List<Item> allVariants=variants;Set<String> completeColors=new HashSet<>();for(Item candidate:allVariants){String color=candidate.p().colorCode();if(filter.sizes().stream().allMatch(size->allVariants.stream().anyMatch(x->Objects.equals(color,x.p().colorCode())&&size.equals(x.p().sizeCode())&&!x.stale()&&x.stock()!=null&&x.stock()>0)))completeColors.add(color);}variants=allVariants.stream().filter(x->completeColors.contains(x.p().colorCode())).toList();if(variants.isEmpty())continue;}
   Item first=variants.get(0);FashionProduct p=first.p();Map<String,String>a=p.attributes();if(variants.stream().anyMatch(x->!Objects.equals(p.currency(),x.p().currency())||!Objects.equals(p.taxMode(),x.p().taxMode())))throw new ServiceException("主SKU存在不同币种或税口径，请先修正商品资料："+a.get("mainSkuCode"));
   if(variants.stream().anyMatch(x->!Objects.equals(a.get("categoryLeafCode"),x.p().attributes().get("categoryLeafCode"))||!Objects.equals(a.get("itemNo"),x.p().attributes().get("itemNo"))||!Objects.equals(a.get("articleNo"),x.p().attributes().get("articleNo"))))throw new ServiceException("同一主SKU分类、品号或货号不一致，请先修正："+a.get("mainSkuCode"));
   boolean stale=variants.stream().anyMatch(Item::stale);boolean unknown=variants.stream().anyMatch(x->x.stock()==null||x.stale());
   if("fresh".equals(filter.stockFreshness())&&unknown||"stale".equals(filter.stockFreshness())&&!stale)continue;
   Long qty=unknown?null:variants.stream().mapToLong(Item::stock).sum();
   String mode=filter.stockStatus()==null?"all":filter.stockStatus();if("unknown".equals(mode)&&!unknown||"positive".equals(mode)&&(qty==null||qty<=0)||"zero".equals(mode)&&(qty==null||qty!=0)||filter.stockMin()!=null&&(qty==null||qty<filter.stockMin())||filter.stockMax()!=null&&(qty==null||qty>filter.stockMax()))continue;
   List<Variant> vv=variants.stream().map(x->new Variant(Long.toString(x.p().id()),x.p().skuCode(),x.p().colorCode(),x.p().colorName(),x.p().sizeCode(),x.groupPrice(),x.vipPrice(),x.stale()?null:x.stock(),image(x.p()),x.p().mainImageKey(),x.p().images(),x.p().visualVersion())).toList();
   result.add(new MainSku(e.getKey(),a.get("mainSkuCode"),p.name(),p.sourceCode(),a.get("categoryLeafCode"),p.brand(),a.get("itemNo"),a.get("articleNo"),Long.toString(p.id()),image(p),price(variants,true,false),price(variants,true,true),price(variants,false,false),price(variants,false,true),qty,unknown,p.currency(),p.taxMode(),vv));
  }return result;
 }
 private static String image(FashionProduct p){return p.mainImageKey()==null?null:"/fashion/products/"+p.id()+"/main-image";}
 private static BigDecimal price(List<Item> items,boolean group,boolean max){List<BigDecimal> prices=items.stream().map(x->group?x.groupPrice():x.vipPrice()).toList();if(prices.stream().anyMatch(Objects::isNull))return null;return prices.stream().min(max?Comparator.reverseOrder():Comparator.naturalOrder()).orElse(null);}
 private static void validateList(List<String> list){if(list!=null&&(list.size()>200||list.stream().anyMatch(x->x==null||x.isBlank()||x.length()>160)))throw new ServiceException("筛选选项最多200项，每项需为非空短文本");}
 private static boolean match(List<String> values,String value){return values==null||values.isEmpty()||values.contains(value);}
 private static boolean money(BigDecimal value,BigDecimal min,BigDecimal max){return (min==null||value!=null&&value.compareTo(min)>=0)&&(max==null||value!=null&&value.compareTo(max)<=0);}
 public static void range(BigDecimal min,BigDecimal max){if(min!=null&&min.signum()<0||max!=null&&max.signum()<0||min!=null&&max!=null&&min.compareTo(max)>0)throw new ServiceException("价格区间无效");}
 private void validateMasters(){Long bad=jdbc.queryForObject("select count(*) from (select source_code,JSON_UNQUOTE(JSON_EXTRACT(attributes_json,'$.mainSkuCode')) main from fq_product where status='active' and JSON_EXTRACT(attributes_json,'$.mainSkuCode') is not null group by source_code,main having count(distinct coalesce(JSON_UNQUOTE(JSON_EXTRACT(attributes_json,'$.categoryLeafCode')),''))>1 or count(distinct coalesce(JSON_UNQUOTE(JSON_EXTRACT(attributes_json,'$.itemNo')),''))>1 or count(distinct coalesce(JSON_UNQUOTE(JSON_EXTRACT(attributes_json,'$.articleNo')),''))>1 or count(distinct coalesce(currency,''))>1 or count(distinct coalesce(tax_mode,''))>1) c",Map.of(),Long.class);if(bad!=null&&bad>0)throw new ServiceException("同一主SKU分类、品号、货号或币种税口径不一致，请先修正商品资料");}
 private void validateClassification(){Long bad=jdbc.queryForObject("select count(*) from (select JSON_UNQUOTE(JSON_EXTRACT(attributes_json,'$.categoryLeafCode')) code from fq_product where JSON_EXTRACT(attributes_json,'$.categoryLeafCode') is not null group by code having count(distinct coalesce(JSON_UNQUOTE(JSON_EXTRACT(attributes_json,'$.categoryLeafPath')),''))>1) c",Map.of(),Long.class);if(bad!=null&&bad>0)throw new ServiceException("同一三级分类编码对应不同分类路径，请先修正商品分类资料");}
 public Map<String,Object> options(){validateClassification();List<Map<String,String>> cats=jdbc.query("select distinct JSON_UNQUOTE(JSON_EXTRACT(attributes_json,'$.categoryLeafCode')) code,JSON_UNQUOTE(JSON_EXTRACT(attributes_json,'$.categoryLeafPath')) path from fq_product where JSON_EXTRACT(attributes_json,'$.categoryLeafCode') is not null",Map.of(),(rs,n)->Map.of("code",rs.getString(1),"path",rs.getString(2)==null?rs.getString(1):rs.getString(2)));return Map.of("categories",cats,"brands",values("brand"),"seasons",values("season"),"genders",values("JSON_UNQUOTE(JSON_EXTRACT(attributes_json,'$.gender'))"),"warehouses",jdbc.queryForList("select distinct warehouse_code from fq_stock order by warehouse_code",Map.of(),String.class));}
 private List<String> values(String field){return jdbc.queryForList("select distinct "+field+" from fq_product where "+field+" is not null order by 1",Map.of(),String.class);}
 public Map<String,Object> price(String id){List<Map<String,Object>> rows=jdbc.query("select group_price,vip_price,row_version,currency,tax_mode from fq_product where id=:id",Map.of("id",id),(rs,n)->{Map<String,Object> row=new LinkedHashMap<>();row.put("productId",id);row.put("groupPrice",rs.getBigDecimal(1));row.put("vipPrice",rs.getBigDecimal(2));row.put("rowVersion",rs.getLong(3));row.put("currency",rs.getString(4));row.put("taxMode",rs.getString(5));return row;});if(rows.isEmpty())throw new ServiceException("商品不存在");return rows.get(0);}
 @org.springframework.transaction.annotation.Transactional(transactionManager="fashionTransactionManager")
 public Map<String,Object> price(String id,BigDecimal group,BigDecimal vip,long version,long actor){return writeLock.executeLocked(10,()->{Map<String,Object> before=price(id);range(group,group);range(vip,vip);if(group!=null&&(group.scale()>2||group.precision()-group.scale()>14)||vip!=null&&(vip.scale()>2||vip.precision()-vip.scale()>14))throw new ServiceException("价格最多两位小数");Map<String,Object> params=new HashMap<>();params.put("id",id);params.put("group",group);params.put("vip",vip);params.put("version",version);params.put("actor",actor);if(jdbc.update("update fq_product set group_price=:group,vip_price=:vip,row_version=row_version+1,update_by=:actor,update_time=now() where id=:id and row_version=:version",params)!=1)throw new ServiceException("商品不存在或版本已变化");Map<String,Object> after=price(id);Map<String,Object> result=new LinkedHashMap<>(after);result.put("previous",before);return result;});}
}
