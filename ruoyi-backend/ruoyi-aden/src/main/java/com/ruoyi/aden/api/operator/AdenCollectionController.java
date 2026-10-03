package com.ruoyi.aden.api.operator;

import com.ruoyi.aden.application.collection.AdenCollectionService;
import com.ruoyi.aden.application.security.AdenOperatorPrincipalProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/aden/collection/workspaces/{workspaceId}")
@ConditionalOnProperty(prefix="aden.collection",name="enabled",havingValue="true")
public class AdenCollectionController {
    private final AdenCollectionService service;
    private final AdenOperatorPrincipalProvider principals;
    public AdenCollectionController(AdenCollectionService service,AdenOperatorPrincipalProvider principals) { this.service=service; this.principals=principals; }
    @GetMapping("/items") public Object list(@PathVariable String workspaceId,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="false") boolean deleted,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return service.list(principals.current(),workspaceId,q,deleted,page,pageSize); }
    @GetMapping("/overview") public Object overview(@PathVariable String workspaceId) { return service.overview(principals.current(),workspaceId); }
    @GetMapping("/tasks") public Object tasks(@PathVariable String workspaceId,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="ALL") String status,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String fromDate,@RequestParam(defaultValue="") String toDate) { return service.tasks(principals.current(),workspaceId,q,status,page,pageSize,fromDate,toDate); }
    @GetMapping("/tasks/{taskId}") public Object taskDetail(@PathVariable String workspaceId,@PathVariable String taskId) { return service.taskDetail(principals.current(),workspaceId,taskId); }
    @GetMapping("/items/{id}") public Object detail(@PathVariable String workspaceId,@PathVariable String id) { return service.detail(principals.current(),workspaceId,id); }
    @GetMapping("/items/lookup") public Object lookup(@PathVariable String workspaceId,@RequestParam String sku) { return service.lookup(principals.current(),workspaceId,sku); }
    @GetMapping("/items/{id}/snapshots") public Object snapshots(@PathVariable String workspaceId,@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return service.snapshots(principals.current(),workspaceId,id,page,pageSize); }
    @GetMapping("/items/{id}/snapshots/{snapshotId}") public Object snapshot(@PathVariable String workspaceId,@PathVariable String id,@PathVariable String snapshotId) { return service.itemSnapshot(principals.current(),workspaceId,id,snapshotId); }
    @PostMapping("/items") public Object manual(@PathVariable String workspaceId,@RequestBody Map<String,Object> body) { return service.manual(principals.current(),workspaceId,body); }
    @PutMapping("/items/{id}/curation") public Object curate(@PathVariable String workspaceId,@PathVariable String id,@RequestBody Map<String,Object> body) { return service.curate(principals.current(),workspaceId,id,body); }
    @PostMapping("/items/trash") public Object trash(@PathVariable String workspaceId,@RequestBody Map<String,Object> body) { return service.trash(principals.current(),workspaceId,ids(body),false); }
    @PostMapping("/items/restore") public Object restore(@PathVariable String workspaceId,@RequestBody Map<String,Object> body) { return service.trash(principals.current(),workspaceId,ids(body),true); }
    @PostMapping("/captures") public Object capture(@PathVariable String workspaceId,@RequestBody Map<String,Object> body) { return service.capture(principals.current(),workspaceId,body); }
    @GetMapping("/captures/{id}") public Object status(@PathVariable String workspaceId,@PathVariable String id) { return service.captureStatus(principals.current(),workspaceId,id); }
    @PostMapping("/captures/{id}/assets/{imageId}/chunks") public Object upload(@PathVariable String workspaceId,@PathVariable String id,@PathVariable String imageId,@RequestBody Map<String,Object> body) { return service.upload(principals.current(),workspaceId,id,imageId,body); }
    @PostMapping("/items/{id}/images/chunks") public Object manualImage(@PathVariable String workspaceId,@PathVariable String id,@RequestBody Map<String,Object> body) { return service.uploadManualImage(principals.current(),workspaceId,id,body); }
    @PostMapping("/captures/{id}/complete") public Object complete(@PathVariable String workspaceId,@PathVariable String id,@RequestBody Map<String,Object> body) { return service.complete(principals.current(),workspaceId,id,body); }
    @GetMapping("/assets/{id}") public ResponseEntity<byte[]> asset(@PathVariable String workspaceId,@PathVariable String id) { return binary(service.asset(principals.current(),workspaceId,id),false); }
    @GetMapping("/assets/{id}/thumbnail") public ResponseEntity<byte[]> thumbnail(@PathVariable String workspaceId,@PathVariable String id) { return binary(service.asset(principals.current(),workspaceId,id,true),false); }
    @PostMapping("/exports") public Object export(@PathVariable String workspaceId,@RequestBody Map<String,Object> body) { return service.export(principals.current(),workspaceId,body); }
    @GetMapping("/exports") public Object exports(@PathVariable String workspaceId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return service.exports(principals.current(),workspaceId,page,pageSize); }
    @GetMapping("/exports/{id}") public Object exportDetail(@PathVariable String workspaceId,@PathVariable String id) { return service.exportDetail(principals.current(),workspaceId,id); }
    @GetMapping("/exports/{id}/download") public ResponseEntity<byte[]> download(@PathVariable String workspaceId,@PathVariable String id) { return binary(service.download(principals.current(),workspaceId,id),true); }
    private static List<String> ids(Map<String,Object> body) { return AdenCollectionService.listValue(body.get("itemIds")).stream().map(AdenCollectionService::str).toList(); }
    private static ResponseEntity<byte[]> binary(AdenCollectionService.Binary file,boolean attachment) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(file.mimeType()))
            .header("X-Content-Type-Options","nosniff").header("Content-Disposition",(attachment?"attachment":"inline")+"; filename=\""+file.fileName()+"\"")
            .body(file.bytes());
    }
}
