package com.ruoyi.fashion.controller.rest.delivery;

import java.nio.charset.StandardCharsets;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import com.ruoyi.fashion.application.proposal.*;
import com.ruoyi.fashion.infrastructure.storage.FashionUploadPolicy;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.ruoyi.fashion.application.proposal.ProposalModels.*;

@RestController @FashionModuleEnabled
@RequestMapping("/fashion/pools/{poolId}/proposal")
public class FashionProposalController extends BaseController {
    private final FashionProposalService service;private final ProposalImageService images;
    public FashionProposalController(FashionProposalService service,ProposalImageService images){this.service=service;this.images=images;}
    @PostMapping("/preflight") @PreAuthorize("@ss.hasPermi('fashion:quote:export')")
    public AjaxResult preflight(@PathVariable String poolId,@RequestBody ExportRequest request){return success(service.preflight(poolId,getUserId(),request));}
    @PostMapping("/pptx") @PreAuthorize("@ss.hasPermi('fashion:quote:export')")
    public ResponseEntity<byte[]> export(@PathVariable String poolId,@RequestBody ExportRequest request){
        byte[] content=service.export(poolId,getUserId(),request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("选品提案-v"+request.rowVersion()+".pptx",StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation")).contentLength(content.length).body(content);
    }
    @GetMapping("/images") @PreAuthorize("@ss.hasPermi('fashion:quote:query')")
    public AjaxResult workspace(@PathVariable String poolId,@RequestParam long rowVersion){return success(images.workspace(poolId,getUserId(),rowVersion));}
    @PostMapping("/models") @PreAuthorize("@ss.hasPermi('fashion:quote:edit')")
    public AjaxResult model(@PathVariable String poolId,@RequestParam long rowVersion,@RequestPart("file") MultipartFile file)throws java.io.IOException{
        if(file.getSize()>FashionUploadPolicy.MAX_IMAGE_BYTES)throw new ServiceException("模特图片不能超过 20MB");
        return success(images.uploadModel(poolId,getUserId(),rowVersion,file.getOriginalFilename(),file.getBytes()));
    }
    @PostMapping("/images") @PreAuthorize("@ss.hasPermi('fashion:quote:edit')")
    public AjaxResult create(@PathVariable String poolId,@RequestBody TryOnRequest request){return success(images.create(poolId,getUserId(),request));}
    @GetMapping("/images/{taskId}") @PreAuthorize("@ss.hasPermi('fashion:quote:query')")
    public AjaxResult get(@PathVariable String poolId,@PathVariable String taskId){return success(images.get(poolId,getUserId(),taskId));}
    public record Version(long rowVersion){}
    @PostMapping("/images/{taskId}/cancel") @PreAuthorize("@ss.hasPermi('fashion:quote:edit')")
    public AjaxResult cancel(@PathVariable String poolId,@PathVariable String taskId,@RequestBody Version request){return success(images.cancel(poolId,getUserId(),taskId,request.rowVersion()));}
    @PostMapping("/images/{taskId}/review") @PreAuthorize("@ss.hasPermi('fashion:quote:edit')")
    public AjaxResult review(@PathVariable String poolId,@PathVariable String taskId,@RequestBody ReviewRequest request){return success(images.review(poolId,getUserId(),taskId,request));}
    @GetMapping("/images/{taskId}/results/{resultNo}") @PreAuthorize("@ss.hasPermi('fashion:quote:query')")
    public ResponseEntity<byte[]> result(@PathVariable String poolId,@PathVariable String taskId,@PathVariable int resultNo){
        byte[] content=images.resultContent(poolId,getUserId(),taskId,resultNo);
        String type=content.length>8&&content[0]==(byte)0x89?"image/png":"image/jpeg";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(type)).body(content);
    }
}
