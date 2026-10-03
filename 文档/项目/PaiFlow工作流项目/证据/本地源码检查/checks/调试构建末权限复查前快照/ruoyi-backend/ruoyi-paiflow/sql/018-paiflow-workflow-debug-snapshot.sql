-- 未执行的 MySQL 8 增量草案。调试来源属于既有不可变版本，不另建任务/运行真相。
-- 先停止派发并部署全部消费者；历史普通版本保留六列 NULL，不回填或改写原快照。
-- 真实执行需单独批准备份、迁移及回退步骤。H2 技术夹具去除 ascii 字符集声明，保留约束。
ALTER TABLE paiflow_workflow_version ADD COLUMN debug_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE paiflow_workflow_version ADD COLUMN debug_owner_user_id BIGINT NULL;
ALTER TABLE paiflow_workflow_version ADD COLUMN debug_comparison_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE paiflow_workflow_version ADD COLUMN debug_request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE paiflow_workflow_version ADD COLUMN debug_request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE paiflow_workflow_version ADD COLUMN debug_agent_id BIGINT NULL;
ALTER TABLE paiflow_workflow_version ADD CONSTRAINT uq_paiflow_debug_request UNIQUE (debug_owner_user_id,debug_request_key);
ALTER TABLE paiflow_workflow_version ADD CONSTRAINT ck_paiflow_debug_snapshot CHECK (
    (debug_mode IS NULL AND debug_owner_user_id IS NULL AND debug_comparison_id IS NULL
      AND debug_request_key IS NULL AND debug_request_hash IS NULL AND debug_agent_id IS NULL)
    OR (debug_mode IS NOT NULL AND debug_mode IN ('ordinary','comparison')
      AND debug_owner_user_id IS NOT NULL AND debug_owner_user_id > 0
      AND debug_request_key IS NOT NULL AND CHAR_LENGTH(debug_request_key) BETWEEN 8 AND 128
      AND debug_request_hash IS NOT NULL AND CHAR_LENGTH(debug_request_hash)=64
      AND (debug_agent_id IS NULL OR debug_agent_id > 0)
      AND ((debug_mode='ordinary' AND debug_comparison_id IS NULL)
        OR (debug_mode='comparison' AND debug_comparison_id IS NOT NULL AND CHAR_LENGTH(debug_comparison_id)=36)))
  );
