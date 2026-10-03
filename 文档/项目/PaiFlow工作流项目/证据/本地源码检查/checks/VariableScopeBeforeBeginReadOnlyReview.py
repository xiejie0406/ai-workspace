"""原变量持久作用域与当前Worker准入的只读来源证据；不建立新设计/运行真相。"""
from pathlib import Path
import hashlib
import json
from datetime import datetime, timezone

ROOT=Path(__file__).resolve().parents[6]
CHECKS=Path(__file__).resolve().parent
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
sources=[
 'python/paiflow-runtime/worker/task_runner.py','python/paiflow-runtime/worker/engine_adapter.py',
 'python/paiflow-runtime/worker/variables.py','python/paiflow-runtime/worker/center_client.py',
 'python/paiflow-runtime/worker/trace.py','python/paiflow-runtime/protocols/persistence-draft.schema.json',
 'python/paiflow-runtime/workflow/engine/nodes/global_variables/global_variables_node.py',
 'python/paiflow-runtime/workflow/engine/entities/variable_pool.py','python/paiflow-runtime/tests/test_original_engine.py',
 '参考项目/PaiFlow-main/core/workflow/engine/nodes/global_variables/global_variables_node.py',
 '参考项目/PaiFlow-main/core/workflow/engine/entities/variable_pool.py','参考项目/PaiFlow-main/core/workflow/api/v1/chat/debug.py',
 '参考项目/PaiFlow-main/core/workflow/domain/entities/chat.py','参考项目/PaiFlow-main/core/workflow/service/chat_service.py',
 '参考项目/PaiFlow-main/core/workflow/service/flow_service.py','参考项目/PaiFlow-main/core/workflow/service/app_service.py',
 '参考项目/PaiFlow-main/core/common/service/cache/redis_cache.py',
 'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/RunService.java',
 'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/RunLeaseService.java',
 'contracts/paiflow/worker.schema.json','contracts/paiflow/internal.openapi.json',
]
before={p:sha(ROOT/p) for p in sources}
content={p:(ROOT/p).read_text(encoding='utf-8-sig') for p in sources}
def at(index,needle):
    rel=sources[index]
    return {'path':rel,'line':content[rel][:content[rel].index(needle)].count('\n')+1,'anchor':needle}
facts=[
 {'id':'original_tuple','kind':'原源码事实','statement':'原VariablesManage各方法共用flow_id+uid+app_id作用域；仅chat_id is None省最后一维，空串仍是显式第四维。set使用hash_set_ex(...,None)，没有TTL。字段注解str不代表实际值只能是字符串，原调用传入验证后的任意输入值。','sources':[at(9,'if self.chat_id is None:'),at(9,'hash_set_ex(name, variable_name, value, None)')]},
 {'id':'original_scope_sources','kind':'原源码事实','statement':'GlobalVariablesNode从pool.system_params[FlowId]、Span.uid/app_id和pool.chat_id取原作用域。chat_service用chat_vo.flow_id设FlowId；VariablePool.add_init_variable从Span.chat_id设chat_id。不是Toolkit workflow.id、immutable version.id或Run.id。','sources':[at(9,'flow_id: str = variable_pool.system_params'),at(13,'ParamKey.FlowId, chat_vo.flow_id'),at(10,'self.chat_id = span.chat_id')]},
 {'id':'app_and_chat_origin','kind':'原源码＋Java子任务独立来源','statement':'Core debug把X-Consumer-Username当app_id，Span取chat_vo.uid/chat_id，并调用真实App查找。Toolkit Workflow.appId原来自CommonConfig真实默认App，经Core Flow存储；其非多轮buildParams省chat_id，而Core ChatVo默认空字符串。因此页面随机chatId不能直接代替持久作用域，空App执行兼容也不是正式App。','sources':[at(11,'app_id = x_consumer_username'),at(11,'span = Span(app_id=app_id'),at(12,'chat_id: str = Field('),at(14,'app_id=flow.app_id')]},
 {'id':'app_fallback_denied','kind':'原来源限制','statement':'原App service缺记录分支含占位来源和硬编码私有材料，不能当真实App恢复依据。此证据只记位置/源hash，不保存或打印材料内容；未导入执行该旧控制面。当前中心没有正式App注册/绑定，不能从WF/Agent/版本/Run ID伪造。','sources':[at(15,'def get_info(')]},
 {'id':'node_algorithm','kind':'原算法已迁入事实','statement':'原GlobalVariablesNode.method只允许set/get。set逐个输入写入；get读get_all后按声明输出取值，缺key才回落原局部VariablePool；存在null不当缺失。输出仍按output_identifier排序。目标仅改变中心委托/await和node_id关联，不另建变量算法。','sources':[at(9,'method: Literal["set", "get"]'),at(6,'await var_manager.add_variable'),at(6,'if key in global_vars:'),at(6,'for output in self.output_identifier:')]},
 {'id':'scope_delegation','kind':'当前源码事实','statement':'当前VariablesManage要求context.execution_context存在且node属于assignment，并严格比较原四元组。HTTP body没有自由flow/user/app/chat；归属必须由中心run/lease/attempt导出。缺端口/拒绝ACK不回落Redis或第二本地变量状态。','sources':[at(2,'if context.execution_context is None'),at(2,'if (self.flow_id, self.uid, self.app_id, self.chat_id)'),at(3,'self._run(run_id) + "/variables"')]},
 {'id':'cas_idempotency','kind':'当前源码事实','statement':'mutate先get_all取得scope revision，写前保存完整瞬时请求；幂等key由run/attempt和operation/node/iteration/values的JSON SHA组成，原重试复用原expectedRevision。锁只在本Worker context内，不代替中心行锁/CAS或跨Run并发授权。','sources':[at(2,'identity = json.dumps('),at(2,'async with context.variable_lock:'),at(2,'context.variable_requests[key] = request')]},
 {'id':'client_ack','kind':'当前客户端事实','statement':'get_all ACK须accepted=true、非布尔非负整数revision和variables对象；get要求found，found=true有value。set/clear要求revision严格大于expectedRevision。当前并不声称clear有原DSL节点消费者。','sources':[at(3,'async def variables('),at(3,'data["revision"] <= values["expectedRevision"]')]},
 {'id':'draft_only','kind':'契约状态事实','statement':'persistence-draft仍明确未实现；共享internal.openapi没有variables/history端点，worker.schema没有正式ExecutionContext定义。当前RunService没有变量profile准入，verify不返回真实App/session上下文。','sources':[at(5,'draft-center-persistence-not-implemented')]},
 {'id':'prebegin_gap','kind':'当前源缺口，尚未有正式派发','statement':'TaskRunner先验证lease/hash/事件序号/完整assignment，然后仅当binding存在时验四字段；缺失binding不会拒绝变量图，随后begin。Engine.execute才拒node-variable缺binding。原现有隔离测试明确预期缺scope提交FAILED；此行为不能作为下一批before-begin准入预期。','sources':[at(0,'binding = lease.get("executionContext")'),at(0,'permit = await self.center.begin_execution'),at(1,'if "node-variable" in types and context.execution_context is None:'),at(8,'test_missing_formal_scope_and_rejected_variable_ack_never_succeed')]},
 {'id':'uid_owner_boundary','kind':'需中心正式绑定的事实边界','statement':'原Span.uid来自原当前用户uid；新scope.userId字段当前只是中心可信字符串并直接传给Span.uid。Worker不能由自己解析/替换为任意请求uid。原uid到平台账号的映射、变量既有数据迁移和中心scope唯一键必须保持同一实际人，不能仅把字段名相同当迁移完成。','sources':[at(1,'uid=binding.get("userId", "")'),at(11,'uid=chat_vo.uid')]},
 {'id':'partial_mutation','kind':'原算法边界','statement':'set原来就是逐字段写，没有节点级多字段事务；第二字段失败不意味着第一字段未写。中心应保留各次CAS/幂等确认与原提交事实，不能把Run失败/取消当作变量回滚。','sources':[at(6,'for key in self.input_identifier:'),at(2,'return await self._request(operation, **request)')]},
 {'id':'nested_and_policy','kind':'尚缺正式中心归属/授权','statement':'当前mutate键含iteration关联，但VariablesRequest不含正式iteration实例字段，不能把不透明key当迭代授权；首批应继续排除嵌套。变量port也不经过RunContext.operation的effectPolicy门，正式set/clear必须由中心按真实scope、固定method/声明变量及既定写策略授权，不能由PURE_NODE_TYPES命名推出写许可。','sources':[at(2,'"iterationId": CURRENT_ITERATION_ID.get()'),at(5,'"VariablesRequest":'),at(2,'context.center.variables(')]},
 {'id':'legacy_storage_format','kind':'原存储格式事实','statement':'原Redis hash用pickle.dumps/loads存取。当前恢复的是受控JSON值与节点语义，没有读原Redis或导入旧pickle；既有持久值导入需要单独受控转换和原账号/App/flow/chat映射，不能以空状态冒称原记忆已迁完。','sources':[at(16,'if pickled := pickle.dumps(value):'),at(16,'return_dict.update({key_str: pickle.loads')]},
]
proposal={
 'minimalWorkerFiles':['worker/task_runner.py现Context parse之后、expected_permit之前；原engine_adapter.py缺scope二次防线保留'],
 'minimalWorkerRule':'固定assignment含node-variable时，absence executionContext明确RESOURCE_UNAVAILABLE；已有malformed/emptyApp仍在同位置拒绝。无变量纯图不依赖App；null/空chat原样deepcopy。不得begin、Factory/engine、events、variables或result。',
 'priority':'先完成有效租约、dslHash/workflowVersionId、lastEventSeq、完整assignment核对，再做scope准入，避免不可信外来DSL/旧领取触发执行许可或终态。',
 'centerNextBoundary':'真实CommonApp identity及WF绑定必须先受控恢复并授权，当前Java503不放开。作用域须对当前Run固定，同时每次检查实际平台账号/space/WF/App权限和有效租约；verify只在实际持久端口具备时交付绑定，不以全局配置/最新WF变化重定向在途scope。',
 'methodWhitelist':{'get':['get_all'],'set':['get_all前置读revision','set只允许固定inputs声明变量名'],'notCurrentNodeAlgorithms':['get library方法','clear library方法']},
 'nextChecks':['缺scope/缺App/非法四字段在begin前全零','有效纯图无context继续','真实形状null/empty chat保持不同','可信中心binding的变量shape到明确隔离Engine端','旧hash/assignment/seq冲突在scope和begin之前拒绝','重复begin失败绝不提交终态'],
 'notImplementation':'此项只读来源与最小建议，不是新的正式契约/App/变量表或默认能力实现。'
}
java_proof=CHECKS/'原应用身份与全局变量作用域来源只读核对.json'
assert sha(java_proof)=='6271969a24bb6be82e81924bad512f39b4188ceeb99039c594060797d5cf0baf'
internal=json.loads(content[sources[20]]);worker=json.loads(content[sources[19]]);draft=json.loads(content[sources[5]])
after={p:sha(ROOT/p) for p in sources}
result={'generatedAtUtc':datetime.now(timezone.utc).isoformat(),'recordType':'已有checks内的只读事实与后续窄门建议，不是第二状态/设计',
        'scope':'仅静态源码/JSON解析/hash检查，0Factory/Engine/test/HTTP/DB/Redis/Provider/Maven/服务/安装/target写。',
        'facts':facts,'proposal':proposal,'sourceBefore':before,'sourceAfter':after,'generationWindowChanged':[p for p in before if before[p]!=after[p]],
        'javaSourceProof':{'path':str(java_proof.relative_to(ROOT)),'sha256':sha(java_proof)},
        'actualContractFacts':{'sharedPersistencePaths':[p for p in internal['paths'] if '/variables' in p or '/history' in p],
                               'sharedExecutionContextDef':worker.get('$defs',{}).get('ExecutionContext'),
                               'draftStatus':draft['x-status'],'draftExecutionContext':draft['$defs']['ExecutionContext']}}
out=CHECKS/'原全局变量中心委托与begin前作用域只读核对.json'
if out.exists():raise RuntimeError('已有证据拒覆盖')
out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'path':str(out),'sha256':sha(out),'facts':len(facts),'sources':len(sources),'changed':result['generationWindowChanged']},ensure_ascii=False))
assert not result['generationWindowChanged']
