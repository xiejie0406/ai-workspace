package com.ruoyi.paicli.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paicli.service.*;
import com.ruoyi.paicli.domain.PcException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.core.io.FileSystemResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/paicli/v1/artifacts")
@ConditionalOnProperty(prefix="paicli",name="enabled",havingValue="true")
public class PcArtifactController extends PcControllerSupport {
    private final PcArtifactService artifacts;
    public PcArtifactController(PcStore store,PcArtifactService artifacts){super(store);this.artifacts=artifacts;}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@ss.hasPermi('paicli:artifact:write')")
    @Log(title="PaiCLI附件上传",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult upload(@RequestHeader("Idempotency-Key") String key,@RequestParam(required=false) Long directoryContextId,@RequestParam String purpose,@RequestPart("file") MultipartFile file)throws IOException{
        int limit="TURN_IMAGE".equals(purpose)?PcArtifactService.MAX_API_IMAGE_BYTES:PcArtifactService.MAX_DATA_BYTES;
        if(file.isEmpty()||file.getSize()>limit)throw new PcException(413,"PC_ARTIFACT_SIZE","附件超出容量上限");
        return AjaxResult.success(artifacts.upload(actor(),key,directoryContextId,purpose,file.getOriginalFilename(),file.getContentType(),file.getBytes()));
    }
    @GetMapping @PreAuthorize("@ss.hasPermi('paicli:artifact:read')")
    public com.ruoyi.common.core.page.TableDataInfo list(@RequestParam(required=false) Long directoryContextId){startPage();var rows=store.mapper.visible(com.ruoyi.paicli.domain.PcTable.ARTIFACT,actor(),directoryContextId,true);var result=getDataTable(rows);result.setRows(rows.stream().map(artifacts::view).toList());return result;}
    @GetMapping("/{id}/content")
    @PreAuthorize("@ss.hasPermi('paicli:artifact:read')")
    public ResponseEntity<FileSystemResource> content(@PathVariable long id){
        var value=artifacts.content(actor(),id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(value.mimeType())).contentLength(value.size())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(value.fileName(),StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"private, no-store")
            .header(HttpHeaders.ETAG,"\""+value.sha256()+"\"").body(new FileSystemResource(value.file()));
    }
    @GetMapping("/{id}/text")
    @PreAuthorize("@ss.hasPermi('paicli:artifact:read')")
    public ResponseEntity<AjaxResult> text(@PathVariable long id,@RequestParam long sessionId,
            @RequestParam(defaultValue="0") long offset,@RequestParam(defaultValue="1048576") int limit){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(AjaxResult.success(artifacts.outputText(actor(),id,sessionId,offset,limit)));
    }
    @GetMapping("/{id}/preview-text")
    @PreAuthorize("@ss.hasPermi('paicli:artifact:read')")
    public ResponseEntity<AjaxResult> previewText(@PathVariable long id,
            @RequestParam(defaultValue="0") long offset,@RequestParam(defaultValue="65536") int limit){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(AjaxResult.success(artifacts.userFilePreviewText(actor(),id,offset,limit)));
    }
}
