package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.mapper.*;
import javax.sql.DataSource;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/** 真实Spring事务代理与MyBatis连接：入口后段失败不能留下已写行或完成回执。 */
class DataWorkerTransactionTest {
    private static <T>T proxy(T target,Class<T> type,DataSource source){var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));return type.cast(factory.getProxy());}
    @Test void lateLeaseFailureRollsBackActualInsertAndBothReceipts() throws Exception {checkRollback(false);}
    @Test void completionSaveFailureRollsBackActualInsertAndOperationReceipt() throws Exception {checkRollback(true);}
    @Test void unfinishedActivePredecessorCannotWriteActualRowOrReceipts() throws Exception {checkRollback(false,true);}
    private void checkRollback(boolean saveFailure) throws Exception {checkRollback(saveFailure,false);}
    private void checkRollback(boolean saveFailure,boolean unfinishedPrior) throws Exception {
        var factory=new DataMapperPersistenceTest().factory();var source=factory.getConfiguration().getEnvironment().getDataSource();
        factory.getConfiguration().setEnvironment(new Environment("spring-data",new SpringManagedTransactionFactory(),source));
        var template=new SqlSessionTemplate(factory);var mapper=template.getMapper(DataMapper.class);var f=new DataCompletionPersistenceTest.Fixture(mapper);
        try(var connection=source.getConnection();var update=connection.prepareStatement("UPDATE paiflow_data_table SET fields_json=? WHERE id=7")){
            update.setString(1,"[{\"name\":\"id\",\"type\":\"Integer\",\"isSystem\":true},{\"name\":\"uid\",\"type\":\"String\",\"isSystem\":true},{\"name\":\"create_time\",\"type\":\"Time\",\"isSystem\":true},{\"name\":\"name\",\"type\":\"String\",\"isRequired\":true}]");update.executeUpdate();
        }
        f.node.getJSONObject("data").getJSONObject("nodeParam").put("mode",1);
        f.node.getJSONObject("data").put("inputs",new JSONArray(List.of(JSONObject.of("name","name","schema",JSONObject.of("type","string","value",JSONObject.of("type","literal","content","入口真实写入"))))));
        var assignment=JSON.parseObject(f.run.getAssignmentJson());assignment.put("input",new JSONObject());assignment.getJSONObject("dsl").getJSONArray("nodes").set(0,f.node);assignment.getJSONObject("dsl").put("edges",new JSONArray());f.run.setAssignmentJson(assignment.toJSONString());f.run.setEngineProfile("python");f.run.setLastEventSeq(0L);
        if(unfinishedPrior){
            // 输入全为字面量也必须等原图活跃前驱；不能只校引用便进入正式写入。
            var prior=JSONObject.of("id","text-joiner::pending","data",JSONObject.of("inputs",new JSONArray(),"outputs",new JSONArray(),"nodeParam",JSONObject.of("mode",0,"template","尚未执行")));
            assignment.getJSONObject("dsl").getJSONArray("nodes").add(prior);
            assignment.getJSONObject("dsl").getJSONArray("edges").add(JSONObject.of("sourceNodeId",prior.get("id"),"targetNodeId",f.node.get("id")));
            f.run.setAssignmentJson(assignment.toJSONString());
        }
        // 固定任务经JSON落盘重读，整数字段Java类型变化不改变资源描述的协议字节。
        when(f.catalog.resolvePinned(eq(2L),eq(1L),eq(DataCompletionPersistenceTest.RESOURCE),argThat(value->value!=null && RunJsonBoundary.canonicalFull(value).equals(RunJsonBoundary.canonicalFull(f.descriptor))))).thenReturn(new DataRuntimeCatalog.FixedData(DataCompletionPersistenceTest.RESOURCE,f.descriptor,mapper.database(5),List.of(mapper.table(7))));
        var operationsMapper=mapper;
        if(saveFailure){operationsMapper=mock(DataMapper.class,delegatesTo(mapper));doThrow(new ServiceException("完成保存失败",500)).when(operationsMapper).recordCompletion(anyMap());}
        var operations=proxy(new DataOperationService(f.catalog,f.rows,operationsMapper),DataOperationService.class,source);
        var runs=mock(RunMapper.class);var spaces=mock(SpaceMapper.class);var leases=mock(RunLeaseService.class);
        var identity=new WorkerIdentityService.Principal("python-local","python");when(runs.find(f.run.getId())).thenReturn(f.run);when(spaces.lock(2)).thenReturn(new SpaceRecord());
        if(saveFailure)when(leases.requireActiveLease(f.run.getId(),identity,f.run.getLeaseId(),1)).thenReturn(f.run);
        else when(leases.requireActiveLease(f.run.getId(),identity,f.run.getLeaseId(),1)).thenReturn(f.run).thenThrow(new ServiceException("末尾租约已失效",410));
        var completed=new CompletedOutputSources(mock(ToolMapper.class),mock(ModelOperationMapper.class),mock(CheckpointMapper.class));
        var worker=proxy(new DataWorkerOperationService(runs,spaces,leases,f.catalog,operations,completed),DataWorkerOperationService.class,source);
        var payload=DataInputPayload.expected(assignment.getJSONObject("dsl"),f.node,assignment.getJSONObject("input"),new JSONArray(),2);
        var request=JSONObject.of("leaseId",f.run.getLeaseId(),"attempt",1,"nodeId",f.node.get("id"),"operation","data.mutate","idempotencyKey","spring-rollback","parameters",JSONObject.of("resourceId",DataCompletionPersistenceTest.RESOURCE,"resourceVersion","2","kind","database","provider","center-data","legacyPayload",payload,"resourceBindings",new JSONObject()));
        assertEquals(unfinishedPrior?403:saveFailure?500:410,assertThrows(ServiceException.class,()->worker.start(f.run.getId(),identity,request)).getCode());
        // 原初始样本已提交；失败操作不能留下新增行、通用回执或完成事实。
        assertEquals(1,mapper.countRows(7,2));assertNull(mapper.request(f.run.getId(),1,DataRuntimeCatalog.sha("spring-rollback")));assertNull(mapper.completion(f.run.getId(),1,DataRuntimeCatalog.sha("spring-rollback")));
        verify(leases,times(unfinishedPrior||saveFailure?1:2)).requireActiveLease(f.run.getId(),identity,f.run.getLeaseId(),1);
    }
}
