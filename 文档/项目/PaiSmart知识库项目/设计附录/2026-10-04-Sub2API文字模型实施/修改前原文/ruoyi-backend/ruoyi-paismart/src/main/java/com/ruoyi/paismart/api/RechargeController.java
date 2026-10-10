package com.ruoyi.paismart.api;

import com.ruoyi.common.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paismart.application.*;
import com.ruoyi.paismart.application.billing.*;
import com.ruoyi.paismart.configuration.PaismartEnabled;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paismart/recharge")
@PaismartEnabled
public class RechargeController {
    private final RechargeService service;private final WechatPaymentGateway gateway;private final MembershipService membership;
    public RechargeController(RechargeService service,WechatPaymentGateway gateway,MembershipService membership){this.service=service;this.gateway=gateway;this.membership=membership;}
    @GetMapping("/packages") @PreAuthorize("@ss.hasPermi('paismart:recharge:create')")
    public TableDataInfo packages(){membership.requireEligible(PaismartIdentity.userId());List<RechargeService.Package> list=service.packages(false);return PaismartTables.page(list,list.size());}
    @GetMapping("/admin/packages") @PreAuthorize("@ss.hasPermi('paismart:recharge:manage')")
    public TableDataInfo adminPackages(){List<RechargeService.Package> list=service.packages(true);return PaismartTables.page(list,list.size());}
    @PostMapping("/admin/packages") @PreAuthorize("@ss.hasPermi('paismart:recharge:manage')") @Log(title="知识库充值套餐",businessType=BusinessType.INSERT)
    public AjaxResult createPackage(@RequestBody Map<String,Object>b){return AjaxResult.success(service.save(null,b,null));}
    @PutMapping("/admin/packages/{id}") @PreAuthorize("@ss.hasPermi('paismart:recharge:manage')") @Log(title="知识库充值套餐",businessType=BusinessType.UPDATE)
    public AjaxResult updatePackage(@PathVariable String id,@RequestBody Map<String,Object>b,@RequestHeader("If-Match")long version){return AjaxResult.success(service.save(id,b,version));}
    @DeleteMapping("/admin/packages/{id}") @PreAuthorize("@ss.hasPermi('paismart:recharge:manage')") @Log(title="知识库充值套餐",businessType=BusinessType.DELETE)
    public AjaxResult deletePackage(@PathVariable String id,@RequestHeader("If-Match")long version){service.removePackage(id,version);return AjaxResult.success();}
    @PostMapping("/orders") @PreAuthorize("@ss.hasPermi('paismart:recharge:create')") @Log(title="知识库充值订单",businessType=BusinessType.INSERT)
    public AjaxResult createOrder(@RequestBody Map<String,Object>b,@RequestHeader("Idempotency-Key")String key){long uid=PaismartIdentity.userId();membership.requireEligible(uid);Object custom=b.get("customAmountFen");return AjaxResult.success(service.create(uid,b.get("packageId")==null?null:b.get("packageId").toString(),custom==null?null:com.ruoyi.paismart.infrastructure.Numbers.integer(custom),key));}
    @GetMapping("/orders/{tradeNo}") @PreAuthorize("@ss.hasPermi('paismart:recharge:create')")
    public AjaxResult order(@PathVariable String tradeNo){return AjaxResult.success(service.query(tradeNo,PaismartIdentity.userId()));}
    @GetMapping("/orders") @PreAuthorize("@ss.hasPermi('paismart:recharge:create')")
    public TableDataInfo orders(@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize,@RequestParam(defaultValue="")String state,@RequestParam(defaultValue="")String status){long uid=PaismartIdentity.userId();membership.requireEligible(uid);String selected=RechargeService.orderState(state),legacy=RechargeService.orderState(status);if(!selected.isEmpty()&&!legacy.isEmpty()&&!selected.equals(legacy))throw new IllegalArgumentException("订单状态条件不一致");if(selected.isEmpty())selected=legacy;int size=Math.max(1,Math.min(100,pageSize));long offset=(long)(Math.max(1,pageNum)-1)*size;return PaismartTables.page(service.orders(uid,(int)Math.min(Integer.MAX_VALUE,offset),size,selected),service.orderCount(uid,selected));}
    @GetMapping(value="/orders/{tradeNo}/qrcode",produces="image/png") @PreAuthorize("@ss.hasPermi('paismart:recharge:create')")
    public ResponseEntity<byte[]> qr(@PathVariable String tradeNo) throws Exception {
        var order=service.get(tradeNo,PaismartIdentity.userId(),false);
        if(order.codeUrl()==null || !order.expiresAt().isAfter(java.time.Instant.now()) || !Set.of("NOT_PAY","PAYING").contains(order.state())) throw new IllegalStateException("订单无有效扫码地址");
        var bits=new com.google.zxing.qrcode.QRCodeWriter().encode(order.codeUrl(),com.google.zxing.BarcodeFormat.QR_CODE,320,320);
        var image=new java.awt.image.BufferedImage(320,320,java.awt.image.BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<320;y++)for(int x=0;x<320;x++)image.setRGB(x,y,bits.get(x,y)?0x000000:0xffffff);
        var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"PNG",out);
        return ResponseEntity.ok().header("Cache-Control","no-store").body(out.toByteArray());
    }
    @PostMapping("/callbacks/wechat") @Anonymous
    public ResponseEntity<Void> callback(@RequestHeader("Wechatpay-Serial")String serial,@RequestHeader("Wechatpay-Nonce")String nonce,@RequestHeader("Wechatpay-Timestamp")String timestamp,@RequestHeader("Wechatpay-Signature")String signature,@RequestBody String body){service.accept(gateway.callback(serial,nonce,timestamp,signature,body));return ResponseEntity.noContent().build();}
}
