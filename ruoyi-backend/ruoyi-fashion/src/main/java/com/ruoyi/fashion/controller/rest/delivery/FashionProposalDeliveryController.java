package com.ruoyi.fashion.controller.rest.delivery;
import java.nio.charset.StandardCharsets;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.fashion.application.proposal.FashionProposalDeliveryService;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @FashionModuleEnabled @RequestMapping("/fashion/proposal-deliveries")
public class FashionProposalDeliveryController extends BaseController {
    private final FashionProposalDeliveryService service;
    public FashionProposalDeliveryController(FashionProposalDeliveryService service){this.service=service;}
    @GetMapping @PreAuthorize("@ss.hasPermi('fashion:quote:export')")
    public AjaxResult list(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize){return success(service.list(getUserId(),page,pageSize));}
    @PostMapping @PreAuthorize("@ss.hasPermi('fashion:quote:export')") @Log(title="生成选品提案交付",businessType=BusinessType.EXPORT)
    public AjaxResult create(@RequestBody FashionProposalDeliveryService.Create request){return success(service.create(getUserId(),request));}
    @GetMapping("/{id}/download") @PreAuthorize("@ss.hasPermi('fashion:quote:export')")
    public ResponseEntity<byte[]> download(@PathVariable String id){var file=service.download(id,getUserId());return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.title()+".pptx",StandardCharsets.UTF_8).build().toString()).contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation")).contentLength(file.bytes().length).body(file.bytes());}
}
