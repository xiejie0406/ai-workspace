package com.ruoyi.paismart.api;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paismart.application.PaismartIdentity;
import com.ruoyi.paismart.application.documents.DocumentService;
import com.ruoyi.paismart.application.spaces.SpaceService.Scope;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paismart/documents")
@ConditionalOnProperty(prefix="paismart",name="enabled",havingValue="true")
public class DocumentController extends BaseController {
    public record TextRequest(String title,String content,String body,String spaceId,String visibility,String requestKey) {}
    public record ActionRequest(Long rowVersion,Long expectedVersion,String requestKey) {}
    private final DocumentService documents;
    public DocumentController(DocumentService documents) {this.documents=documents;}
    private Scope actor() {var a=PaismartIdentity.current();return new Scope(a.userId(),a.administrator());}
    @GetMapping
    @PreAuthorize("@ss.hasPermi('paismart:document:list')")
    public TableDataInfo list(@RequestParam(required=false) String title,@RequestParam(required=false) String keyword,@RequestParam(required=false) String state,@RequestParam(required=false) String spaceId,
      @RequestParam(required=false) String visibility,@RequestParam(defaultValue="false") boolean mine,
      @RequestParam(defaultValue="false") boolean deleted,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {
        var page=documents.list(actor(),title==null?keyword:title,spaceId,visibility,mine,deleted,pageNum,pageSize,state);
        TableDataInfo response=PaismartTables.page();response.setCode(200);response.setMsg("查询成功");response.setRows(page.rows());response.setTotal(page.total());return response;
    }
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('paismart:document:read')")
    public AjaxResult detail(@PathVariable String id) {return success(documents.getReadable(actor(),id));}
    @PostMapping("/text")
    @PreAuthorize("@ss.hasPermi('paismart:document:upload')")
    @Log(title="知识库文本",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult text(@RequestBody TextRequest input,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        return success(documents.createText(actor(),input.title(),input.content()==null?input.body():input.content(),input.spaceId(),input.visibility(),input.requestKey()==null?key:input.requestKey()));
    }
    private void version(DocumentService.Document doc,Integer contentVersion) {
        if(contentVersion!=null && doc.contentVersion()!=contentVersion) throw new ServiceException("文档内容版本已变化，请刷新",409);
    }
    @GetMapping("/{id}/content")
    @PreAuthorize("@ss.hasPermi('paismart:document:read')")
    public ResponseEntity<byte[]> content(@PathVariable String id,@RequestParam(required=false) Integer contentVersion) {
        Scope actor=actor();version(documents.getReadable(actor,id),contentVersion);
        return ResponseEntity.ok().contentType(new MediaType("text","plain",StandardCharsets.UTF_8)).header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.noStore()).body(documents.content(actor,id).getBytes(StandardCharsets.UTF_8));
    }
    @GetMapping("/{id}/pages/{number}")
    @PreAuthorize("@ss.hasPermi('paismart:document:read')")
    public ResponseEntity<byte[]> page(@PathVariable String id,@PathVariable int number,@RequestParam(required=false) Integer contentVersion) {
        Scope actor=actor();version(documents.getReadable(actor,id),contentVersion);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.noStore()).body(documents.pdfPage(actor,id,number));
    }
    @GetMapping("/{id}/download")
    @PreAuthorize("@ss.hasPermi('paismart:document:read')")
    public ResponseEntity<InputStreamResource> download(@PathVariable String id) {
        Scope actor=actor();var doc=documents.getReadable(actor,id);InputStream data=documents.download(actor,id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(doc.byteSize())
          .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(doc.filename(),StandardCharsets.UTF_8).build().toString())
          .header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.noStore()).body(new InputStreamResource(data));
    }
    private long expected(Long query,String match,ActionRequest body) {
        Long version=query;
        if(version==null && body!=null) version=body.rowVersion()==null?body.expectedVersion():body.rowVersion();
        if(version==null && match!=null) try {version=Long.parseLong(match.replace("\"",""));} catch(NumberFormatException e) {throw new ServiceException("版本标识无效",400);}
        if(version==null || version<=0) throw new ServiceException("需要当前文档版本",400);return version;
    }
    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('paismart:document:remove')")
    @Log(title="知识库文档删除",businessType=BusinessType.DELETE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult delete(@PathVariable String id,@RequestParam(required=false) Long rowVersion,@RequestHeader(value="If-Match",required=false) String match) {return success(documents.delete(actor(),id,expected(rowVersion,match,null)));}
    @PostMapping("/{id}/restore")
    @PreAuthorize("@ss.hasPermi('paismart:document:remove')")
    @Log(title="知识库文档恢复",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult restore(@PathVariable String id,@RequestBody ActionRequest input) {return success(documents.restore(actor(),id,expected(null,null,input)));}
    @PostMapping({"/{id}/parse","/{id}/reindex","/{id}/vectorization/retry","/{id}/retry","/{id}/vectorization"})
    @PreAuthorize("@ss.hasPermi('paismart:document:reindex')")
    @Log(title="知识库文档重处理",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult reprocess(@PathVariable String id,@RequestBody ActionRequest input,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        return success(documents.reprocess(actor(),id,"DOCUMENT_PROCESS",input.requestKey()==null?key:input.requestKey(),expected(null,null,input)));
    }
}
