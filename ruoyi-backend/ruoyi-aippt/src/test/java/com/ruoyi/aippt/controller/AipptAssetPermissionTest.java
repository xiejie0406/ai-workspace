package com.ruoyi.aippt.controller;

import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.application.*;
import com.ruoyi.aippt.configuration.AipptProperties;
import com.ruoyi.aippt.domain.AipptException;
import com.ruoyi.aippt.infrastructure.persistence.AipptRepository;
import com.ruoyi.common.utils.SecurityUtils;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AipptAssetPermissionTest {
    @Test void exportRoleNeedsDownloadPermissionForBlobAndTicketButImageDoesNot() {
        AssetService assets=mock(AssetService.class);
        AipptController controller=new AipptController(mock(AipptService.class),assets,mock(CatalogService.class),mock(AipptRepository.class),new AipptProperties(),mock(AipptHealthService.class));
        JSONObject asset=JSONObject.of("role","export","mime","application/vnd.openxmlformats-officedocument.presentationml.presentation","filename","业务.pptx");
        when(assets.metadata("asset",42)).thenReturn(asset);
        when(assets.read("asset",42)).thenReturn(new byte[0]);
        when(assets.ticket("asset",42)).thenReturn(JSONObject.of("url","private"));
        try(var security=mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(42L);
            security.when(()->SecurityUtils.hasPermi("aippt:export:download")).thenReturn(false);
            assertThrows(AipptException.class,()->controller.asset("asset",null));
            assertThrows(AipptException.class,()->controller.ticket("asset"));
            verify(assets,never()).read(anyString(),anyLong());
            verify(assets,never()).ticket(anyString(),anyLong());
            asset.put("role","image"); asset.put("mime","image/png");
            assertDoesNotThrow(()->controller.asset("asset",null));
            assertDoesNotThrow(()->controller.ticket("asset"));
            asset.put("role","export");
            security.when(()->SecurityUtils.hasPermi("aippt:export:download")).thenReturn(true);
            assertDoesNotThrow(()->controller.ticket("asset"));
        }
    }
    @Test void workerMapIngressAndPublicCommandKeepExplicitNestedNull() {
        TaskLeaseService tasks=mock(TaskLeaseService.class);
        WorkerTaskController worker=new WorkerTaskController(tasks,mock(AssetService.class));
        Map<String,Object> output=new HashMap<>(); output.put("speaker_notes",null);
        worker.complete("task",Map.of("output",output));
        var captor=org.mockito.ArgumentCaptor.forClass(JSONObject.class);
        verify(tasks).complete(eq("task"),captor.capture());
        assertTrue(captor.getValue().getJSONObject("output").containsKey("speaker_notes"));
        assertNull(captor.getValue().getJSONObject("output").get("speaker_notes"));
        AipptService service=mock(AipptService.class);
        AipptController controller=new AipptController(service,mock(AssetService.class),mock(CatalogService.class),mock(AipptRepository.class),new AipptProperties(),mock(AipptHealthService.class));
        try(var security=mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(42L);
            // 与HTTP Map入口相同的转换；调用现有content入口，不启动Web服务器。
            Map<String,Object> command=new HashMap<>();command.put("revision",1);command.put("type","kpi");command.put("note",null);
            controller.content("project","slide","block","key",command);
            verify(service).edit(eq("project"),eq("slide"),eq(42L),eq("key"),eq("content"),eq("block"),captor.capture());
            assertTrue(captor.getValue().containsKey("note"));
            assertNull(captor.getValue().get("note"));
        }
    }
}
