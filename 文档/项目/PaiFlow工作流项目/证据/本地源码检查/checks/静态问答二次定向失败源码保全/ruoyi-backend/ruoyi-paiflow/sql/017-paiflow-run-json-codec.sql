-- 未执行增量草案：先停止派发并核对在途任务，再由批准的迁移步骤实施。
-- 旧NULL表示原request/event/result的V1字节规则，不回填、不重写历史哈希或事件。
-- 新运行由中心固定V2，显式null在输入/事件/结果中完整保留；该列不参与运行update。
ALTER TABLE paiflow_run ADD COLUMN run_json_codec VARCHAR(48) NULL;
ALTER TABLE paiflow_run ADD CONSTRAINT ck_paiflow_run_json_codec
 CHECK (run_json_codec IS NULL OR run_json_codec IN ('paiflow.run-json.v1','paiflow.run-json.v2'));
