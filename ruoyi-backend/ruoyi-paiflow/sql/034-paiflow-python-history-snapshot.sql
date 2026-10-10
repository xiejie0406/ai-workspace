-- Python旧历史可信冻结及原追加来源证据；依赖021/013，未执行真实MySQL。
-- 既有无证据历史保留显示，不伪回填、不静默移除原窗口中的轮次。
CREATE TABLE paiflow_python_history_snapshot (
 history_ref VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 lease_id VARCHAR(64) NOT NULL,attempt INT NOT NULL,
 snapshot_json MEDIUMTEXT NOT NULL,snapshot_hash CHAR(64) NOT NULL,
 PRIMARY KEY(history_ref),UNIQUE KEY uk_paiflow_python_snapshot_run(run_id,attempt),
 CONSTRAINT fk_paiflow_python_snapshot_context FOREIGN KEY(run_id) REFERENCES paiflow_run_context(run_id),
 CONSTRAINT ck_paiflow_python_snapshot_attempt CHECK(attempt>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE paiflow_python_history_evidence (
 run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 attempt INT NOT NULL,request_key_hash CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 model_operation_id VARCHAR(64) NOT NULL,evidence_json TEXT NOT NULL,evidence_hash CHAR(64) NOT NULL,
 PRIMARY KEY(run_id,attempt,request_key_hash),
 UNIQUE KEY uk_paiflow_python_history_operation(model_operation_id),
 CONSTRAINT fk_paiflow_python_evidence_request FOREIGN KEY(run_id,attempt,request_key_hash)
   REFERENCES paiflow_node_history(run_id,attempt,request_key_hash),
 CONSTRAINT fk_paiflow_python_evidence_operation FOREIGN KEY(model_operation_id) REFERENCES paiflow_model_operation(id),
 CONSTRAINT ck_paiflow_python_evidence_attempt CHECK(attempt>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
