package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.WorkflowImportService;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 保留原multipart上传入口，预览不写入；确认只创建待编辑草稿。 */
@RestController
@RequestMapping("/paiflow/legacy/workflow/import")
@ConditionalOnProperty(prefix = "paiflow", name = "enabled", havingValue = "true")
public class LegacyWorkflowImportController {
    private final WorkflowImportService imports;

    public LegacyWorkflowImportController(WorkflowImportService imports) { this.imports = imports; }

    @PostMapping("/preview")
    public LegacyResult<WorkflowImportService.Preview> preview(@RequestParam("file") MultipartFile file,
            @RequestHeader(value = "space-id", required = false) String header) throws IOException {
        return LegacyResult.success(imports.preview(bytes(file), file.getOriginalFilename(), header));
    }

    @PostMapping
    @Log(title = "PaiFlow工作流导入草稿", businessType = BusinessType.INSERT,
         isSaveRequestData = false, isSaveResponseData = false)
    public LegacyResult<JSONObject> confirm(@RequestParam("file") MultipartFile file,
            @RequestParam("previewHash") String previewHash,
            @RequestHeader(value = "space-id", required = false) String header) throws IOException {
        return LegacyResult.success(imports.confirm(bytes(file), file.getOriginalFilename(), header, previewHash));
    }

    private static byte[] bytes(MultipartFile file) throws IOException {
        int maximum = WorkflowImportService.MAX_FILE_BYTES;
        if (file == null || file.isEmpty() || file.getSize() > maximum)
            throw new ServiceException("导入文件须为非空且不超过20MiB的YAML", 413);
        try (var stream = file.getInputStream()) {
            byte[] bytes = stream.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new ServiceException("导入文件超出允许大小", 413);
            return bytes;
        }
    }
}
