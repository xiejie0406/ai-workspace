package com.ruoyi.fashion.application.pool;
import static com.ruoyi.fashion.application.pool.PoolModels.*;
import java.util.*;
import java.time.Instant;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import com.ruoyi.fashion.infrastructure.persistence.pool.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @FashionModuleEnabled
public class FashionPoolService {
 private final JdbcFashionPoolRepository repository;private final FashionPoolCatalog catalog;
 public FashionPoolService(JdbcFashionPoolRepository repository,FashionPoolCatalog catalog){this.repository=repository;this.catalog=catalog;}
 @Transactional(transactionManager="fashionTransactionManager",readOnly=false)
 public Pool create(Create request,long actor){
  if(request.groups()==null||request.groups().size()<1||request.groups().size()>4)throw new ServiceException("请选择1至4个筛选组");
  if(request.priceBasis()!=null&&!Set.of("group","vip").contains(request.priceBasis()))throw new ServiceException("价格口径必须是团购价或VIP价");FashionPoolCatalog.range(request.totalMin(),request.totalMax());
  String title=request.title()==null||request.title().isBlank()?"新套装方案":request.title().trim();if(title.length()>200)throw new ServiceException("方案名称最多200字");
  List<Group> groups=new ArrayList<>();for(int i=0;i<request.groups().size();i++){GroupFilter filter=request.groups().get(i);List<MainSku> items=catalog.find(filter);if(items.isEmpty())throw new ServiceException("第"+(i+1)+"组没有匹配的主SKU，请检查分类映射、主SKU编码及筛选条件");groups.add(new Group(filter.label()==null?"品类"+(i+1):filter.label(),items));}
  long raw=PoolCombinations.rawCount(groups);Pool pool=new Pool(UUID.randomUUID().toString(),title,request,List.copyOf(groups),raw,0,Instant.now().toString());long total=PoolCombinations.enumerate(pool).size();pool=new Pool(pool.id(),title,request,pool.groups(),raw,total,pool.createdAt());repository.insert(pool,actor);return pool;
 }
 public Pool get(String id,long actor){return repository.get(id,actor);}
 public Object list(long actor){return repository.list(actor);}
 public Selection selection(String id,long actor){get(id,actor);return repository.selection(id,actor);}
 public Map<String,Object> combinations(String id,long actor,int page,int size,String sort,String keyword,boolean selectedOnly){
  Pool pool=get(id,actor);List<Combination> all=PoolCombinations.enumerate(pool);Selection selection=repository.selection(id,actor);
  if(selectedOnly)all=selected(all,selection);else all.sort(PoolCombinations.comparator(sort));
  if(keyword!=null&&!keyword.isBlank()){String search=keyword.trim().toLowerCase(Locale.ROOT);all=all.stream().filter(c->c.items().stream().anyMatch(x->(x.mainSkuCode()+" "+x.name()+" "+x.itemNo()+" "+x.articleNo()).toLowerCase(Locale.ROOT).contains(search))).toList();}
  if(page<1||size<1||size>100)throw new ServiceException("分页参数无效，每页最多100套");long offset=(long)(page-1)*size;List<Combination> items=offset>=all.size()?List.of():all.subList((int)offset,Math.min(all.size(),(int)offset+size));return Map.of("items",items,"total",all.size(),"rawCount",pool.rawCount(),"page",page,"pageSize",size);
 }
 @Transactional(transactionManager="fashionTransactionManager")
 public Selection save(String id,long actor,Selection request){
  if(request.rowVersion()<0||request.selectedIds().size()>100000||request.excludedIds().size()>100000||request.orderedIds().size()>100000||request.names().size()>1000||request.variantSelections().size()>1000)throw new ServiceException("已选内容过大，请分批整理；命名和选色每池最多1000套");
  Pool pool=get(id,actor);List<Combination> all=PoolCombinations.enumerate(pool);Map<String,Combination> ids=new HashMap<>();all.forEach(x->ids.put(x.id(),x));
  Set<String> refs=new HashSet<>(request.selectedIds());refs.addAll(request.excludedIds());refs.addAll(request.orderedIds());refs.addAll(request.names().keySet());refs.addAll(request.variantSelections().keySet());if(refs.stream().anyMatch(x->x==null||x.length()>32))throw new ServiceException("方案标识无效");if(!ids.keySet().containsAll(refs))throw new ServiceException("选择包含其他版本或不存在的方案");
  for(String name:request.names().values())if(name.length()>200)throw new ServiceException("方案名称最多200字");
  for(var entry:request.variantSelections().entrySet()){Combination combo=ids.get(entry.getKey());for(var variant:entry.getValue().entrySet()){MainSku main=combo.items().stream().filter(x->x.key().equals(variant.getKey())).findFirst().orElseThrow(()->new ServiceException("选色商品不属于该方案"));if(main.variants().stream().noneMatch(x->x.productId().equals(variant.getValue())))throw new ServiceException("颜色尺码不属于此主SKU");}}
  return repository.save(id,actor,request);
 }
 public ExportSelection requireExport(String id,long actor,long expectedVersion){Pool pool=get(id,actor);Selection selection=repository.selection(id,actor);if(selection.rowVersion()!=expectedVersion)throw new ServiceException("已选方案已变化，请重新预览后导出");List<Combination> combos=selected(PoolCombinations.enumerate(pool),selection);if(combos.isEmpty())throw new ServiceException("请先选择并保存方案");return new ExportSelection(pool,selection,combos);}
 private static List<Combination> selected(List<Combination> all,Selection s){Set<String> chosen=new HashSet<>(s.selectedIds()),excluded=new HashSet<>(s.excludedIds());List<Combination> result=new ArrayList<>(all.stream().filter(x->(s.all()||chosen.contains(x.id()))&&!excluded.contains(x.id())).toList());Map<String,Integer> order=new HashMap<>();for(int i=0;i<s.orderedIds().size();i++)order.putIfAbsent(s.orderedIds().get(i),i);result.sort(Comparator.comparingInt((Combination c)->order.getOrDefault(c.id(),Integer.MAX_VALUE)).thenComparing(Combination::id));return result;}
}
