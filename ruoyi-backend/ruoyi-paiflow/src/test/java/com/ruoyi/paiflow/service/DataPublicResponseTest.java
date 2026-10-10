package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.paiflow.controller.WorkerModelController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际Service/Catalog/Controller生成公开数据；Mapper、租约和行源隔离mock，不是业务库联机。 */
class DataPublicResponseTest {
    @Test void actualControllerResourceAndSynchronousResponseRetainOriginalRowsWithoutPrivateFields() throws Exception {
        var boundary = new DataWorkerOperationTest.Fixture();
        var catalogue = new DataRuntimeCatalogTest.Fixture();
        var data = new DataOperationTest.Fixture();
        var fixed = catalogue.catalog.bindCurrent(2,5);
        var task = JSON.parseObject(boundary.run.getAssignmentJson());
        task.getJSONObject("resourceSnapshots").put(fixed.resourceId(),fixed.descriptor());
        task.put("dataEnvironment",2);
        task.put("effectPolicy",JSONObject.of("mode","read_only","allowedOperations",new JSONArray(List.of("data.query"))));
        boundary.run.setAssignmentJson(task.toJSONString());
        String lease="11111111-1111-1111-1111-111111111111";
        boundary.run.setLeaseId(lease);
        when(boundary.leases.requireActiveLease(boundary.run.getId(),boundary.identity,lease,1)).thenReturn(boundary.run);
        var receipts=new java.util.HashMap<String,java.util.Map<String,Object>>();
        when(data.mapper.request(anyString(),anyInt(),anyString())).thenAnswer(i->receipts.get(i.getArgument(2)));
        when(data.mapper.completion(anyString(),anyInt(),anyString())).thenReturn(null);
        when(data.mapper.record(anyString(),anyInt(),anyString(),anyString(),anyString())).thenAnswer(i->{receipts.put(i.getArgument(2),java.util.Map.of("requestHash",i.getArgument(3),"responseJson",i.getArgument(4)));return 1;});
        when(data.mapper.recordCompletion(any())).thenReturn(1);
        var operations=new DataOperationService(catalogue.catalog,data.rows,data.mapper);
        var worker=new DataWorkerOperationService(boundary.runs,boundary.spaces,boundary.leases,catalogue.catalog,operations,boundary.completedOutputs);
        var identities=mock(WorkerIdentityService.class);
        when(identities.authenticate("isolated-worker-fixture")).thenReturn(boundary.identity);
        var models=mock(ModelOperationService.class);
        var controller=new WorkerModelController(identities,models);
        ObjectProvider<DataWorkerOperationService> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(worker);
        controller.useDataOperations(provider);
        var resource=controller.resource("isolated-worker-fixture",boundary.run.getId(),fixed.resourceId(),
            JSONObject.of("leaseId",lease,"attempt",1));
        assertEquals(200,resource.get("code"));
        assertEquals(RunJsonBoundary.canonicalFull(fixed.descriptor()),RunJsonBoundary.canonicalFull((JSONObject)resource.get("data")));
        var request=boundary.request();request.put("leaseId",lease);
        var result=controller.start("isolated-worker-fixture",boundary.run.getId(),request);
        assertEquals(200,result.get("code"));
        var ack=(JSONObject)result.get("data");
        assertEquals("COMPLETED",ack.get("state"));assertNull(ack.get("failureKind"));
        assertEquals(new JSONArray(List.of(JSONObject.of("name","甲"),JSONObject.of("name","乙"))),
            ack.getJSONObject("legacyResponse").getJSONObject("data").getJSONArray("exec_success"));
        assertFalse(ack.containsKey("operationId"));assertFalse(ack.containsKey("endpoint"));
        verify(data.mapper).record(eq(boundary.run.getId()),eq(1),anyString(),anyString(),anyString());
        verify(data.rows,never()).mutateRow(any(),anyInt(),anyInt(),any(),anyLong());
        verifyNoInteractions(models);
        String export=System.getProperty("paiflow.dataPublicExport");
        if(export!=null)Files.writeString(Path.of(export),JSONObject.of("scope",
            "actual Catalog/Service/Controller JSON with isolated mock Mapper/lease/row-source; not business DB or live Worker",
            "resourceId",fixed.resourceId(),"resourceEnvelope",resource,"request",request,"responseEnvelope",result)
            .toJSONString(JSONWriter.Feature.WriteMapNullValue));
    }
}
