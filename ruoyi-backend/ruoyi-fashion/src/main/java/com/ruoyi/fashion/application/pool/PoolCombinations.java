package com.ruoyi.fashion.application.pool;
import static com.ruoyi.fashion.application.pool.PoolModels.*;
import java.math.BigDecimal;
import java.util.*;
import com.ruoyi.common.exception.ServiceException;
/** 有界完整枚举；绝不截取前 N 条冒充完整池。 */
public final class PoolCombinations {
 public static final long LIMIT=100_000;
 private PoolCombinations() {}
 public static long rawCount(List<Group> groups) {
  long count=1;
  for(Group g:groups) { if(g.items().isEmpty())return 0; if(count>LIMIT/g.items().size())throw new ServiceException("原始组合超过10万套，请缩小分类或价格等筛选范围后重试"); count*=g.items().size(); }
  return count;
 }
 public static List<Combination> enumerate(Pool pool) {
  rawCount(pool.groups()); List<Combination> out=new ArrayList<>(); walk(pool,0,new ArrayList<>(),new ArrayList<>(),out,new long[]{0}); return out;
 }
 private static void walk(Pool p,int depth,List<MainSku> items,List<Integer> indices,List<Combination> out,long[] work) {
  if(depth==p.groups().size()) {
   MainSku first=items.get(0);
   if(items.stream().anyMatch(x->!Objects.equals(first.currency(),x.currency())||!Objects.equals(first.taxMode(),x.taxMode())))return;
   BigDecimal gmin=sum(items,true,false),gmax=sum(items,true,true),vmin=sum(items,false,false),vmax=sum(items,false,true);
   BigDecimal min="vip".equals(p.request().priceBasis())?vmin:gmin,max="vip".equals(p.request().priceBasis())?vmax:gmax;
   if(p.request().totalMin()!=null&&(max==null||max.compareTo(p.request().totalMin())<0))return;
   if(p.request().totalMax()!=null&&(min==null||min.compareTo(p.request().totalMax())>0))return;
   if((p.request().totalMin()!=null||p.request().totalMax()!=null)&&!budgetExists(items,p.request(),work))return;
   out.add(new Combination(indices.stream().map(String::valueOf).reduce((a,b)->a+"-"+b).orElseThrow(),List.copyOf(items),gmin,gmax,vmin,vmax,items.stream().anyMatch(MainSku::stockUnknown),first.currency(),first.taxMode()));return;
  }
  List<MainSku> candidates=p.groups().get(depth).items();
  for(int i=0;i<candidates.size();i++) { MainSku candidate=candidates.get(i);if(items.stream().anyMatch(x->x.key().equals(candidate.key())))continue;items.add(candidate);indices.add(i);walk(p,depth+1,items,indices,out,work);items.remove(items.size()-1);indices.remove(indices.size()-1); }
 }
 private static BigDecimal sum(List<MainSku> items,boolean group,boolean max) {BigDecimal result=BigDecimal.ZERO;for(MainSku x:items){BigDecimal v=group?(max?x.groupPriceMax():x.groupPriceMin()):(max?x.vipPriceMax():x.vipPriceMin());if(v==null)return null;result=result.add(v);}return result;}
 private static boolean budgetExists(List<MainSku> items,Create request,long[] work){
  List<List<BigDecimal>> values=new ArrayList<>();for(MainSku item:items){work[0]+=item.variants().size();checkWork(work);List<BigDecimal> prices=item.variants().stream().map(v->"vip".equals(request.priceBasis())?v.vipPrice():v.groupPrice()).filter(Objects::nonNull).distinct().sorted().toList();
   // 用于历史快照和纯算法调用的单一明确价格仍可验证；不把区间当离散价格。
   if(prices.isEmpty()){BigDecimal min="vip".equals(request.priceBasis())?item.vipPriceMin():item.groupPriceMin(),max="vip".equals(request.priceBasis())?item.vipPriceMax():item.groupPriceMax();if(min==null||max==null||min.compareTo(max)!=0)return false;prices=List.of(min);}values.add(prices);}
  return searchBudget(values,0,BigDecimal.ZERO,request.totalMin(),request.totalMax(),work);
 }
 private static boolean searchBudget(List<List<BigDecimal>> values,int index,BigDecimal sum,BigDecimal low,BigDecimal high,long[] work){
  work[0]++;checkWork(work);
  if(index==values.size())return (low==null||sum.compareTo(low)>=0)&&(high==null||sum.compareTo(high)<=0);
  BigDecimal smallest=sum,largest=sum;for(int i=index;i<values.size();i++){smallest=smallest.add(values.get(i).get(0));largest=largest.add(values.get(i).get(values.get(i).size()-1));}if(high!=null&&smallest.compareTo(high)>0||low!=null&&largest.compareTo(low)<0)return false;
  for(BigDecimal value:values.get(index)){if(high!=null&&sum.add(value).compareTo(high)>0)break;if(searchBudget(values,index+1,sum.add(value),low,high,work))return true;}return false;
 }
 private static void checkWork(long[] work){if(work[0]>2_000_000)throw new ServiceException("颜色尺码价格组合计算超过200万步，请缩小价格、颜色或尺码范围后重试");}
 public static Comparator<Combination> comparator(String sort) {
  Comparator<BigDecimal> asc=Comparator.nullsLast(Comparator.naturalOrder()),desc=Comparator.nullsLast(Comparator.reverseOrder());
  Comparator<Combination> c=switch(sort){case "identity"->Comparator.comparing(Combination::id);case "groupAsc"->Comparator.comparing(Combination::groupPriceMin,asc);case "groupDesc"->Comparator.comparing(Combination::groupPriceMax,desc);case "vipAsc"->Comparator.comparing(Combination::vipPriceMin,asc);case "vipDesc"->Comparator.comparing(Combination::vipPriceMax,desc);default->throw new ServiceException("不支持的方案排序");};return c.thenComparing(Combination::id);
 }
}
