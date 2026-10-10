package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.WorkflowImportService;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 原上传入口增加预览/确认；无收据绝不产生草稿。 */
@RestController
@RequestMapping("/paiflow/legacy/workflow/import")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyWorkflowImportController {
    private final WorkflowImportService imports;public LegacyWorkflowImportController(WorkflowImportService imports){this.imports=imports;}
    @PostMapping("/preview") public LegacyResult<WorkflowImportService.Preview> preview(@RequestParam("file") MultipartFile file,@RequestHeader(value="space-id",required=false) String header)throws IOException{return LegacyResult.success(imports.preview(bytes(file),file.getOriginalFilename(),header));}
    @PostMapping public LegacyResult<JSONObject> confirm(@RequestParam("file") MultipartFile file,@RequestParam String previewHash,@RequestHeader(value="space-id",required=false) String header)throws IOException{return LegacyResult.success(imports.confirm(bytes(file),file.getOriginalFilename(),header,previewHash));}
    private static byte[] bytes(MultipartFile file)throws IOException{if(file==null||file.isEmpty()||file.getSize()>20*1024*1024)throw new ServiceException("导入文件须为非空且不超过20MB的YAML",413);try(var input=file.getInputStream()){byte[] bytes=input.readNBytes(20*1024*1024+1);if(bytes.length>20*1024*1024)throw new ServiceException("导入文件超出允许大小",413);return bytes;}}
}
