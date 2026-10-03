"""只读原App身份与全局变量scope来源；仅记录字段/关系和源码哈希。"""
import ast
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path

root = Path(__file__).resolve().parents[6]
old = "参考项目/PaiFlow-main/"
toolkit = old + "console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/"
commons = old + "console/backend/commons/src/main/java/com/iflytek/astron/console/commons/"
hub = old + "console/backend/hub/src/main/java/com/iflytek/astron/console/hub/"
core = old + "core/workflow/"
centre = "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/"
sources = [
    toolkit + "config/properties/CommonConfig.java",
    toolkit + "service/workflow/WorkflowService.java",
    toolkit + "service/extra/AppService.java",
    toolkit + "entity/core/workflow/sse/ChatSysReq.java",
    commons + "entity/workflow/Workflow.java",
    commons + "entity/bot/UserLangChainInfo.java",
    commons + "entity/user/AppMst.java",
    commons + "service/user/Impl/AppMstServiceImpl.java",
    commons + "util/MaasUtil.java",
    hub + "service/publish/impl/PublishApiServiceImpl.java",
    hub + "service/publish/impl/TenantServiceImpl.java",
    hub + "service/WorkflowChatService.java",
    core + "api/v1/flow/layout.py",
    core + "api/v1/chat/debug.py",
    core + "api/v1/chat/open.py",
    core + "domain/entities/chat.py",
    core + "domain/models/ai_app.py",
    core + "domain/models/flow.py",
    core + "domain/models/license.py",
    core + "repository/license_dao.py",
    core + "service/license_service.py",
    core + "service/flow_service.py",
    core + "service/app_service.py",
    core + "service/chat_service.py",
    core + "engine/dsl_engine.py",
    core + "engine/entities/variable_pool.py",
    core + "engine/nodes/global_variables/global_variables_node.py",
    centre + "domain/WorkflowRecord.java",
    centre + "domain/RunRecord.java",
    centre + "service/IdentityMappingService.java",
    centre + "service/WorkflowService.java",
    centre + "service/WorkflowModelCompiler.java",
    centre + "service/RunService.java",
    centre + "service/RunLeaseService.java",
    centre + "adapter/legacy/LegacyDebugBridgeService.java",
    centre + "adapter/legacy/LegacyApplicationController.java",
    "python/paiflow-runtime/worker/task_runner.py",
    "python/paiflow-runtime/worker/engine_adapter.py",
    "python/paiflow-runtime/protocols/persistence-draft.schema.json",
    "contracts/paiflow/worker.schema.json",
    "contracts/paiflow/internal.openapi.json",
    "文档/项目/PaiFlow工作流项目/证据/本地源码检查/checks/发布应用与Tenant原协议只读核对.json",
    "文档/项目/PaiFlow工作流项目/证据/本地源码检查/checks/原全局变量节点中心接续只读核对.json",
]
sources += [file.relative_to(root).as_posix() for file in sorted((root / "ruoyi-backend/ruoyi-paiflow/sql").glob("*.sql"))]

def sha(value):
    return hashlib.sha256(value).hexdigest()

before = {path: (root / path).read_bytes() for path in sources}
app_tree = ast.parse(before[core + "service/app_service.py"].decode("utf-8-sig"))
get_info = next(node for node in app_tree.body if isinstance(node, ast.FunctionDef) and node.name == "get_info")
# 只报告存在的常量字段名，不输出任何常量内容或凭据。
constant_fallback_fields = sorted({target.id for node in ast.walk(get_info) if isinstance(node, ast.Assign) and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str) for target in node.targets if isinstance(target, ast.Name) and target.id in {"source_id", "name", "desc", "api_key", "api_secret"}})
tables = []
for path in sources:
    if path.startswith("ruoyi-backend/ruoyi-paiflow/sql/"):
        tables += re.findall(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([a-zA-Z0-9_]+)", before[path].decode("utf-8-sig"), re.IGNORECASE)
domain_files = [file.name for file in (root / centre / "domain").glob("*.java")]
after = {path: (root / path).read_bytes() for path in sources}
drift = [path for path in sources if before[path] != after[path]]
report = {
    "schemaVersion": "paiflow.readonly-app-scope-facts.v1",
    "checkedAt": datetime.now(timezone.utc).isoformat(),
    "scope": "原App/应用别名到GlobalVariablesNode作用域的字段关系核对；非新的设计/计划/授权或实现状态",
    "execution": {"sourceWrites": 0, "targetWrites": 0, "MavenRuns": 0, "engineImports": 0, "FactoryRuns": 0, "realDatabaseConnections": 0, "ProviderCalls": 0, "secretConfigurationReads": 0, "GitWrites": 0},
    "privacy": "不读取.env/application真实配置；源代码凭据仅作AST字段存在性检查，报告不保存原常量值。",
    "sourceWindow": {"count": len(sources), "drift": drift},
    "sources": [{"path": path, "sha256": sha(before[path])} for path in sources],
    "originalIdentityChain": [
        {"source": toolkit + "config/properties/CommonConfig.java", "lines": [8, 15], "fact": "common配置声明appId/apiKey/apiSecret；本次只读声明，不读真实值。它是默认后台应用来源，不是AgentId。"},
        {"source": toolkit + "service/workflow/WorkflowService.java", "lines": [1015, 1054], "fact": "原create无条件setAppId(commonConfig.getAppId())，callProtocolAdd后将原Core返回flow_id保存Workflow.flowId；原本地Workflow.id、flowId、uid、spaceId各自独立。"},
        {"source": toolkit + "service/workflow/WorkflowService.java", "lines": [1734, 1753], "fact": "protocol/add body.app_id来自WorkflowReq.appId，返回Core db_flow.id字符串作为flowId。不能把本地自增Workflow.id当CoreflowID。"},
        {"source": core + "api/v1/flow/layout.py", "lines": [41, 80], "fact": "Core add以flow.app_id给Span，app_service.get_info获取App，再flow_service.save；回执flow_id是实际db_flow.id。"},
        {"source": core + "service/flow_service.py", "lines": [44, 70], "fact": "Core Flow存独立id/group_id、app_id为应用别名、source来自App.actual_source、草稿version=-1；group_id用于后续发布版本/license关系，不是变量scope中的App或flow替代。"},
        {"source": core + "domain/models/ai_app.py", "fact": "App.id为内部雪花主键，alias_id是外部业务App标识；有status/source/actual_source及凭据字段。GlobalVariablesNode使用Span.app_id即alias_id，不是App内部id。"},
        {"source": core + "service/app_service.py", "lines": [get_info.lineno, get_info.end_lineno], "fact": "get_info先App cache再按alias_id读DB；缺记录分支目前有硬编码source/name/desc/凭据占位并插入App。该fallback不是已核实真实Tenant回执，不应迁为默认成功实现。", "constantFallbackFieldNamesOnly": constant_fallback_fields},
        {"source": toolkit + "service/workflow/WorkflowService.java", "lines": [3095, 3132], "fact": "原debug chat查实际Workflow并取该appId凭据，X-Consumer-Username=Workflow.appId；Core请求flow_id来自同一Workflow，uid来自当前UserInfoManager，parameters来自本次inputs。nodeParam.appId不是变量scope的直接来源。"},
        {"source": toolkit + "entity/core/workflow/sse/ChatSysReq.java", "lines": [35, 48], "fact": "chatId为nullable String，无默认值。Toolkit3118-3124只在有效多轮历史时调用buildParams，3246才setChatId；否则NON_NULL省略chat_id，Core ChatVo默认空串。原页面SSE关联chatId不能不经核对直接变成变量chat scope。"},
        {"source": core + "api/v1/chat/debug.py", "lines": [32, 68], "fact": "Coreapp_id=可信网关header的x_consumer_username，Span同时用ChatVo.uid/chat_id；再查App信息。原发布open路径同样以header App给Span，但另要求publishedflow/license。迁移不再信任用户自由指定uid/App header。"},
        {"source": core + "service/chat_service.py", "lines": [512, 531], "fact": "正式执行把chat_vo.flow_id设为ParamKey.FlowId。Engine Factory只解析/构造节点，不产生应用或用户身份。"},
        {"source": core + "engine/entities/variable_pool.py", "lines": [466, 485], "fact": "原Start输入写入时把span.chat_id复制给VariablePool.chat_id。"},
        {"source": core + "engine/nodes/global_variables/global_variables_node.py", "lines": [153, 171], "fact": "变量tuple严格取FlowId systemparam、Span.uid/app_id、pool.chat_id；不取nodeParam appId、Workflow版本、BotId、RunId。"},
    ],
    "originalAgentAndPublishedAppChain": [
        {"source": commons + "entity/bot/UserLangChainInfo.java", "lines": [22, 70], "fact": "Agent关系表有botId/uid/flowId/maasId，无独立App字段；App由关联Workflow或发布API关系提供，不能从Agent.id推断。"},
        {"source": hub + "service/publish/impl/PublishApiServiceImpl.java", "lines": [87, 104], "fact": "用户App创建先取Tenant实际createApp/getAppDetail回执再保存app_mst(uid,appId...)；与草稿CommonConfig默认App不同，不能用metadata代替正式回执。"},
        {"source": commons + "service/user/Impl/AppMstServiceImpl.java", "lines": [49, 62], "fact": "用户App读取/选择按uid+appId+未删除；旧app_mst不带space_id，迁移归属需显式核对而不能挑最近命名空间。"},
        {"source": hub + "service/publish/impl/PublishApiServiceImpl.java", "lines": [118, 137], "fact": "发布创建检查当前空间owner及当前uid拥有Bot和App，分别取得实际两个记录；不是body AppId即可授权。"},
        {"source": hub + "service/publish/impl/PublishApiServiceImpl.java", "lines": [182, 213], "fact": "Workflow型Bot由user_lang_chain_info取flowId，由Release取versionName，以用户AppId调用Maas publish/bind，之后写chat_bot_api(uid,botId,assistantId=flowId,appId)。该App不自动改草稿Workflow默认appId。"},
        {"source": commons + "util/MaasUtil.java", "lines": [399, 410], "fact": "实际publish与bind两个回执分别校验；绑定范围是flowId/目标App/version。"},
        {"source": core + "repository/license_dao.py", "lines": [14, 35], "fact": "Core公开运行按app.alias_id与Flow.group_id查app内部id→license.app_id关系；license.service.bind存实际App.id+group_id。不能用原License存在性取代当前平台空间/菜单权限。"},
    ],
    "currentCentreFacts": [
        {"source": centre + "service/IdentityMappingService.java", "lines": [49, 63], "fact": "已存在old_uid→平台用户映射与当前活动账号核验；ruoyi:<platformId>是新增compat UID，不代表原变量旧uid。迁移旧变量时须明示实际old_uid映射，不能据同平台用户多别名任选一个。"},
        {"source": centre + "service/WorkflowService.java", "lines": [93, 93], "fact": "当前新Workflow.flowId由中心UUID产生，有真实space/owner和Agent绑定；不等同原Coreflow雪花ID，现没有旧flow/App迁移ID映射。"},
        {"source": centre + "service/WorkflowService.java", "lines": [147, 171], "fact": "Agent create/copy入口剥离appId；WorkflowRecord没有App绑定字段，opaque metadata不能作为实际App授权。"},
        {"source": centre + "service/WorkflowModelCompiler.java", "lines": [84, 85], "fact": "受控OpenAI仅在执行副本补appId空串以满足原Node字符串字段；这不是正式App关系，不能进入变量tuple。StaticQA兼容同理。"},
        {"source": centre + "adapter/legacy/LegacyDebugBridgeService.java", "lines": [67, 79], "fact": "当前静态调试验证chatId后仅传inputs/固定版本/真实actor/key给Run创建；没有持久chat/App记录，不把页面会话关联键伪装成业务会话。"},
        {"source": centre + "service/RunLeaseService.java", "lines": [129, 140], "fact": "正式verify仅返回valid/cancel/hash/version/seq/assignment，无executionContext；RunRecord/assignment尚无正式App/chat绑定。模型资源快照不是App身份。"},
        {"source": centre + "adapter/legacy/LegacyApplicationController.java", "lines": [11, 26], "fact": "该Application是团队空间加入申请/apply-record，不是业务应用App；文件名不能作为App已实现证据。"},
    ],
    "currentCentreStaticAbsence": {
        "candidateSQLTables": tables,
        "applicationRegistryOrChatTableMatches": [name for name in tables if re.search(r"(^|_)(app|application|chat|conversation)(_|$)", name)],
        "applicationIdentityDomainMatches": [name for name in domain_files if name in {"AppRecord.java", "ApplicationRecord.java", "ChatRecord.java", "ConversationRecord.java"}],
        "meaning": "当前候选018以前没有正式应用/会话registry或App/Flow导入mapping；全部SQL仍仅未执行草案，不能从它们推断真实原库状态。",
    },
    "conclusionsAndSmallestPrerequisites": [
        "现在能够恢复的是当前平台actor、真实WF/Agent/Space关系与固定版本；不能仅从这些记录恢复原App alias、旧flowId或业务chatId。变量正式准入目前503是实质缺能力，不应删除。",
        "不必为了变量先迁入整个旧Tenant控制面：若有经过授权核实的CommonConfig默认App、Core App.alias_id及原Workflow.appId资料，可受控登记/导入为中心正式App身份与WF绑定；已有真实用户App也可按uid映射和实际回执导入。但当前源码没有该记录/端口，不能自行假设已配置。",
        "至少补同一中心的正式App identity/状态/作用域或已批准真实绑定端口，以及WF→App关系和Run固定executionContext。原App别名保存为外部标识，中心内键与别名区别；uid和flow历史映射明确，Worker从lease读取，禁止body自由指定。",
        "Chat语义须按原入口核对：无历史Toolkitdebug原省略chat_id而Core有效空串；有历史或Agent/open业务请求可携带实际chat。null、空串、真实chat对象分开，不能把本次runId或页面随机ID自动当原会话。正式会话绑定尚缺时保持拒绝，或先在唯一设计明确新的调试scope差异。",
        "应用登记和变量state不声称用户App已真实创建/发布。正式Tenant创建、密钥、发布版本license/渠道仍要求各自受控回执，旧hardcoded fallback不迁入，不存公开secret。",
    ],
    "notEstablished": ["原实际配置AppID/凭据", "原真实库App/Workflow/license/chat及Redis变量数据", "已执行原/目标业务数据库映射", "正式Tenant/Provider回执", "新的Java或Python运行检查", "真实HTTP/浏览器业务验收"],
}
if drift:
    raise RuntimeError("只读源窗口变化：" + str(drift))
out = Path(__file__).with_name("原应用身份与全局变量作用域来源只读核对.json")
out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"sha256": sha(out.read_bytes()), "sources": len(sources), "sourceDrift": len(drift), "currentAppTableMatches": report["currentCentreStaticAbsence"]["applicationRegistryOrChatTableMatches"], "appFallbackFieldNamesOnly": constant_fallback_fields}, ensure_ascii=True))
