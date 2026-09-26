package com.ruoyi.fashion.controller.rest.pool;
import static com.ruoyi.fashion.application.pool.PoolModels.*;
import java.math.BigDecimal;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import com.ruoyi.fashion.application.pool.FashionPoolService;
import com.ruoyi.fashion.infrastructure.persistence.pool.FashionPoolCatalog;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @FashionModuleEnabled @RequestMapping("/fashion/pools")
public class FashionPoolController extends BaseController {
 private final FashionPoolService service;private final FashionPoolCatalog catalog;
 public FashionPoolController(FashionPoolService service,FashionPoolCatalog catalog){this.service=service;this.catalog=catalog;}
 @GetMapping("/options") @PreAuthorize("@ss.hasPermi('fashion:quote:list')") public AjaxResult options(){return success(catalog.options());}
 @GetMapping @PreAuthorize("@ss.hasPermi('fashion:quote:list')") public AjaxResult list(){return success(service.list(getUserId()));}
 @PostMapping @PreAuthorize("@ss.hasPermi('fashion:quote:edit')") @Log(title="创建套装方案池",businessType=BusinessType.INSERT,isSaveRequestData=false) public AjaxResult create(@RequestBody Create request){return success(service.create(request,getUserId()));}
 @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('fashion:quote:list')") public AjaxResult get(@PathVariable String id){return success(service.get(id,getUserId()));}
 @GetMapping("/{id}/combinations") @PreAuthorize("@ss.hasPermi('fashion:quote:list')") public AjaxResult combinations(@PathVariable String id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,@RequestParam(defaultValue="identity")String sort,@RequestParam(required=false)String keyword,@RequestParam(defaultValue="false")boolean selectedOnly){return success(service.combinations(id,getUserId(),page,pageSize,sort,keyword,selectedOnly));}
 @GetMapping("/{id}/selection") @PreAuthorize("@ss.hasPermi('fashion:quote:list')") public AjaxResult selection(@PathVariable String id){return success(service.selection(id,getUserId()));}
 @PutMapping("/{id}/selection") @PreAuthorize("@ss.hasPermi('fashion:quote:edit')") @Log(title="保存套装已选方案",businessType=BusinessType.UPDATE,isSaveRequestData=false) public AjaxResult save(@PathVariable String id,@RequestBody Selection request){return success(service.save(id,getUserId(),request));}
 @GetMapping("/products/{id}/commercial-prices") @PreAuthorize("@ss.hasPermi('fashion:price:import')") public AjaxResult price(@PathVariable String id){return success(catalog.price(id));}
 public record CommercialPrice(BigDecimal groupPrice,BigDecimal vipPrice,long rowVersion){}
 @PutMapping("/products/{id}/commercial-prices") @PreAuthorize("@ss.hasPermi('fashion:price:import')") @Log(title="维护商品双价格",businessType=BusinessType.UPDATE,isSaveRequestData=false) public AjaxResult price(@PathVariable String id,@RequestBody CommercialPrice request){return success(catalog.price(id,request.groupPrice(),request.vipPrice(),request.rowVersion(),getUserId()));}
}
