-- 未执行MySQL8增量草案：依赖015，不创建新业务表、不回填或重编译旧版本。
-- 新含顶层静态QA版本由中心选择V3；无QA新版本继续V2；NULL/V1/V2原字节不变。
-- 正式迁移需先核对现有CHECK名字与在途运行；应用不自动执行DDL。
ALTER TABLE paiflow_workflow_version DROP CHECK ck_paiflow_execution_codec;
ALTER TABLE paiflow_workflow_version ADD CONSTRAINT ck_paiflow_execution_codec
 CHECK(execution_codec IS NULL OR execution_codec IN ('paiflow.execution.v1','paiflow.execution.v2','paiflow.execution.v3'));
-- H2夹具仅将DROP CHECK改为DROP CONSTRAINT，保留实际集合约束及创建session。
