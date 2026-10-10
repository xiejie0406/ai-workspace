package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.paiflow.service.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 原React的19个数据库接口：复用中心身份和真实业务事务，不调用原独立PG服务。 */
@RestController @RequestMapping("/paiflow/legacy/db")
@ConditionalOnProperty(prefix="paiflow",name={"enabled","data.enabled"},havingValue="true")
public class LegacyDataController {
    private final DataResourceService data;private final DataSpreadsheetService sheets;
    public LegacyDataController(DataResourceService data,DataSpreadsheetService sheets){this.data=data;this.sheets=sheets;}
    private DefaultPersonalSpaceService.Selection scope(String space,JSONObject body,boolean edit){return data.selection(space,body==null?null:body.get("spaceId"),edit);}
    @PostMapping("/create") public LegacyResult<Void> create(@RequestHeader(value="space-id",required=false)String s,@RequestBody JSONObject b){data.create(scope(s,b,true),b);return LegacyResult.success(null);}
    @GetMapping("/detail") public LegacyResult<JSONObject> detail(@RequestHeader(value="space-id",required=false)String s,@RequestParam long id){return LegacyResult.success(data.detail(scope(s,null,false),id));}
    @PostMapping("/update") public LegacyResult<Void> update(@RequestHeader(value="space-id",required=false)String s,@RequestBody JSONObject b){data.update(scope(s,b,true),b);return LegacyResult.success(null);}
    @GetMapping("/delete") public LegacyResult<Void> delete(@RequestHeader(value="space-id",required=false)String s,@RequestParam long id){data.delete(scope(s,null,true),id);return LegacyResult.success(null);}
    @GetMapping("/copy") public LegacyResult<Void> copy(@RequestHeader(value="space-id",required=false)String s,@RequestParam long id,@RequestParam(required=false)String name){data.copy(scope(s,null,true),id,name);return LegacyResult.success(null);}
    @PostMapping("/page-list") public LegacyResult<JSONObject> list(@RequestHeader(value="space-id",required=false)String s,@RequestBody JSONObject b){return LegacyResult.success(data.list(scope(s,b,false),b));}
    @PostMapping("/create-table") public LegacyResult<Void> createTable(@RequestHeader(value="space-id",required=false)String s,@RequestBody JSONObject b){data.createTable(scope(s,b,true),b);return LegacyResult.success(null);}
    @GetMapping("/table-list") public LegacyResult<List<JSONObject>> tables(@RequestHeader(value="space-id",required=false)String s,@RequestParam long dbId){return LegacyResult.success(data.tables(scope(s,null,false),dbId));}
    @GetMapping("/db_table-list") public LegacyResult<List<JSONObject>> tree(@RequestHeader(value="space-id",required=false)String s){return LegacyResult.success(data.tree(scope(s,null,false)));}
    @PostMapping("/update-table") public LegacyResult<Void> updateTable(@RequestHeader(value="space-id",required=false)String s,@RequestBody JSONObject b){data.updateTable(scope(s,b,true),b);return LegacyResult.success(null);}
    @PostMapping("/import-field-list") public LegacyResult<List<JSONObject>> fieldsImport(@RequestHeader(value="space-id",required=false)String s,@RequestParam MultipartFile file){scope(s,null,true);return LegacyResult.success(sheets.importFields(file));}
    @PostMapping("/table-field-list") public LegacyResult<JSONObject> fields(@RequestHeader(value="space-id",required=false)String s,@RequestBody JSONObject b){return LegacyResult.success(data.fields(scope(s,b,false),b));}
    @GetMapping("/delete-table") public LegacyResult<Void> deleteTable(@RequestHeader(value="space-id",required=false)String s,@RequestParam long id){data.deleteTable(scope(s,null,true),id);return LegacyResult.success(null);}
    @PostMapping("/operate-table-data") public LegacyResult<Void> rows(@RequestHeader(value="space-id",required=false)String s,@RequestBody JSONObject b){data.operate(scope(s,b,true),b);return LegacyResult.success(null);}
    @PostMapping("/select-table-data") public LegacyResult<JSONObject> select(@RequestHeader(value="space-id",required=false)String s,@RequestBody JSONObject b){return LegacyResult.success(data.data(scope(s,b,false),b));}
    @GetMapping("/copy-table") public LegacyResult<Void> copyTable(@RequestHeader(value="space-id",required=false)String s,@RequestParam long tbId){data.copyTable(scope(s,null,true),tbId);return LegacyResult.success(null);}
    @PostMapping("/import-table-data") public LegacyResult<Void> rowsImport(@RequestHeader(value="space-id",required=false)String s,@RequestParam long tbId,@RequestParam int execDev,@RequestParam MultipartFile file){sheets.importRows(scope(s,null,true),tbId,execDev,file);return LegacyResult.success(null);}
    @PostMapping("/export-table-data") public ResponseEntity<byte[]> export(@RequestHeader(value="space-id",required=false)String s,@RequestBody JSONObject b){return download(sheets.export(scope(s,b,false),DataResourceService.id(b.get("tbId")),DataResourceService.environment(b.get("execDev")),b.getJSONArray("dataIds")),"paiflow-data.xlsx");}
    @GetMapping("/table-template") public ResponseEntity<byte[]> template(@RequestHeader(value="space-id",required=false)String s,@RequestParam long tbId){return download(sheets.template(scope(s,null,false),tbId),"paiflow-template.xlsx");}
    @GetMapping("/field-template") public ResponseEntity<byte[]> fieldTemplate(@RequestHeader(value="space-id",required=false)String s){scope(s,null,false);return download(DataSpreadsheetService.fieldTemplate(),"paiflow-fields.xlsx");}
    @GetMapping("/reserved-fields") public LegacyResult<JSONObject> reservedFields(@RequestHeader(value="space-id",required=false)String s){return LegacyResult.success(data.reservedFields(scope(s,null,false)));}
    private static ResponseEntity<byte[]> download(byte[] bytes,String name){return ResponseEntity.ok().contentType(MediaType.parseMediaType(DataSpreadsheetService.MIME)).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(name).build().toString()).body(bytes);}
}
