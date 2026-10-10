package com.ruoyi.paismart.api;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paismart.application.PaismartIdentity;
import com.ruoyi.paismart.application.documents.UploadService;
import com.ruoyi.paismart.application.spaces.SpaceService.Scope;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/paismart/uploads")
@ConditionalOnProperty(prefix="paismart",name="enabled",havingValue="true")
public class UploadController extends BaseController {
    private final UploadService uploads;
    public UploadController(UploadService uploads) {this.uploads=uploads;}
    private Scope actor() {var a=PaismartIdentity.current();return new Scope(a.userId(),a.administrator());}
    @GetMapping("/supported-types")
    @PreAuthorize("@ss.hasPermi('paismart:document:upload')")
    public AjaxResult types() {return success(uploads.supportedTypes());}
    @GetMapping
    @PreAuthorize("@ss.hasPermi('paismart:document:upload')")
    public com.ruoyi.common.core.page.TableDataInfo list(@RequestParam(defaultValue="UPLOADING") String state,@RequestParam(required=false) String sha256,@RequestParam(required=false) String spaceId,@RequestParam(required=false) String visibility,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {
        var page=uploads.list(actor(),state,sha256,spaceId,visibility,keyword,pageNum,pageSize);var result=PaismartTables.page(page.rows(),page.total());result.setCode(200);result.setMsg("查询成功");return result;
    }
    @PostMapping
    @PreAuthorize("@ss.hasPermi('paismart:document:upload')")
    public AjaxResult create(@RequestBody UploadService.Create input,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        if(input.requestKey()==null) input=new UploadService.Create(input.filename(),input.byteSize(),input.sha256(),input.chunkSize(),input.spaceId(),input.visibility(),key);
        return success(uploads.create(actor(),input));
    }
    @GetMapping({"/{id}","/{id}/status"})
    @PreAuthorize("@ss.hasPermi('paismart:document:upload')")
    public AjaxResult status(@PathVariable String id) {return success(uploads.status(actor(),id));}
    @PutMapping(value="/{id}/chunks/{number}",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @PreAuthorize("@ss.hasPermi('paismart:document:upload')")
    public AjaxResult chunk(@PathVariable String id,@PathVariable int number,@RequestHeader("X-Chunk-SHA256") String digest,HttpServletRequest request) throws IOException {
        try(var body=request.getInputStream()) {return success(uploads.chunk(actor(),id,number,digest,body));}
    }
    @PutMapping(value="/{id}/chunks/{number}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@ss.hasPermi('paismart:document:upload')")
    public AjaxResult multipart(@PathVariable String id,@PathVariable int number,@RequestParam String sha256,@RequestPart MultipartFile file) throws IOException {
        try(var body=file.getInputStream()) {return success(uploads.chunk(actor(),id,number,sha256,body));}
    }
    @PostMapping("/{id}/merge")
    @PreAuthorize("@ss.hasPermi('paismart:document:upload')")
    @Log(title="知识库文件合并",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult merge(@PathVariable String id) {return success(uploads.merge(actor(),id));}
}
