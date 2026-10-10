-- 未执行MySQL8增量草案：依赖015/016；不新增业务表，不回填或重编译历史版本。
-- V4固定顶层PE/Decision/动态QA的原消息与FunctionCall语义；NULL/V1/V2/V3原允许值保留。
-- 正式迁移须核对CHECK名称、当前版本及在途运行，并按已批准步骤执行；应用不自动运行DDL。
ALTER TABLE paiflow_workflow_version DROP CHECK ck_paiflow_execution_codec;
ALTER TABLE paiflow_workflow_version ADD CONSTRAINT ck_paiflow_execution_codec
 CHECK(execution_codec IS NULL OR execution_codec IN ('paiflow.execution.v1','paiflow.execution.v2','paiflow.execution.v3','paiflow.execution.v4'));
