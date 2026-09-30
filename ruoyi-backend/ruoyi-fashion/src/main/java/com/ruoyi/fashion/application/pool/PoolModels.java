package com.ruoyi.fashion.application.pool;
import java.math.BigDecimal;
import java.util.*;
import com.ruoyi.fashion.domain.product.FashionProductImage;
public final class PoolModels {
 private PoolModels() {}
 public record GroupFilter(String label,List<String> categoryLeafCodes,List<String> seasons,List<String> genders,List<String> brands,List<String> itemNos,List<String> articleNos,List<String> mainSkuCodes,String keyword,List<String> colors,List<String> sizes,String warehouseCode,String stockStatus,Long stockMin,Long stockMax,BigDecimal groupPriceMin,BigDecimal groupPriceMax,BigDecimal vipPriceMin,BigDecimal vipPriceMax,Boolean requireAllSizes,String stockFreshness,List<String> styles,String fabricComposition,List<String> fits,List<String> scenes,Boolean hasMainImage,Boolean imagesConfirmed) {
 public GroupFilter(String label,List<String> categoryLeafCodes,List<String> seasons,List<String> genders,List<String> brands,List<String> itemNos,List<String> articleNos,List<String> mainSkuCodes,String keyword,List<String> colors,List<String> sizes,String warehouseCode,String stockStatus,Long stockMin,Long stockMax,BigDecimal groupPriceMin,BigDecimal groupPriceMax,BigDecimal vipPriceMin,BigDecimal vipPriceMax){this(label,categoryLeafCodes,seasons,genders,brands,itemNos,articleNos,mainSkuCodes,keyword,colors,sizes,warehouseCode,stockStatus,stockMin,stockMax,groupPriceMin,groupPriceMax,vipPriceMin,vipPriceMax,null,null,null,null,null,null,null,null);}
 }
 public record Create(String title,List<GroupFilter> groups,String priceBasis,BigDecimal totalMin,BigDecimal totalMax) {}
 public record Variant(String productId,String skuCode,String colorCode,String colorName,String sizeCode,BigDecimal groupPrice,BigDecimal vipPrice,Long availableQty,String mainImageUrl,String mainImageKey,List<FashionProductImage> images,long visualVersion) {}
 public record MainSku(String key,String mainSkuCode,String name,String sourceCode,String categoryLeafCode,String brand,String itemNo,String articleNo,String productId,String mainImageUrl,BigDecimal groupPriceMin,BigDecimal groupPriceMax,BigDecimal vipPriceMin,BigDecimal vipPriceMax,Long availableQty,boolean stockUnknown,String currency,String taxMode,List<Variant> variants) {}
 public record Group(String label,List<MainSku> items) {}
 public record Pool(String id,String title,Create request,List<Group> groups,long rawCount,long total,String createdAt) {}
 public record Combination(String id,List<MainSku> items,BigDecimal groupPriceMin,BigDecimal groupPriceMax,BigDecimal vipPriceMin,BigDecimal vipPriceMax,boolean stockUnknown,String currency,String taxMode) {}
 public record Selection(String poolId,long rowVersion,boolean all,List<String> selectedIds,List<String> excludedIds,Map<String,String> names,List<String> orderedIds,Map<String,Map<String,String>> variantSelections) {
  public Selection { selectedIds=selectedIds==null?List.of():List.copyOf(selectedIds); excludedIds=excludedIds==null?List.of():List.copyOf(excludedIds); names=names==null?Map.of():Map.copyOf(names); orderedIds=orderedIds==null?List.of():List.copyOf(orderedIds); variantSelections=variantSelections==null?Map.of():Map.copyOf(variantSelections); }
 }
 public record ExportSelection(Pool pool,Selection selection,List<Combination> combinations) {}
}
