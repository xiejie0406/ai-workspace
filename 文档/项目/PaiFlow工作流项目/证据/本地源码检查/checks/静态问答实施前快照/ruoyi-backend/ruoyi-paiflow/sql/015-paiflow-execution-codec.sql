-- 未执行MySQL8增量草案：依赖002不可变工作流版本表，012公开模型绑定列仍保持原语义。
-- NULL精确表示既有V1结构转换；不回填、不重新编译、不更新旧execution/hash/canonical字节。
-- 新版本由中心选择V2。客户端不能决定codec；真实切换须先核对既有版本与在途运行。
ALTER TABLE paiflow_workflow_version ADD COLUMN execution_codec VARCHAR(48) NULL;
ALTER TABLE paiflow_workflow_version ADD CONSTRAINT ck_paiflow_execution_codec
 CHECK(execution_codec IS NULL OR execution_codec IN ('paiflow.execution.v1','paiflow.execution.v2'));
-- H2隔离检查保留此CHECK；生产MySQL草案没有DEFAULT和UPDATE，应用不自动执行DDL。
