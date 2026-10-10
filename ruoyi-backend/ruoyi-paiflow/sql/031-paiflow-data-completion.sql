-- 数据节点真实完成来源。026之后独立增量，尚未执行；不回填旧事件为真实回执。
-- 与数据行及paiflow_data_request在同一中心事务保存；历史通用幂等记录继续保留。
CREATE TABLE paiflow_data_completion (
  run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  lease_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  attempt INT NOT NULL,
  node_id VARCHAR(255) NOT NULL,
  iteration_id VARCHAR(255) NOT NULL DEFAULT '',
  request_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  completion_json MEDIUMTEXT NOT NULL,
  completion_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY(run_id,attempt,request_key_hash),
  UNIQUE KEY uq_paiflow_data_completion_source(run_id,lease_id,attempt,node_id,iteration_id),
  CONSTRAINT fk_paiflow_data_completion_request FOREIGN KEY(run_id,attempt,request_key_hash)
    REFERENCES paiflow_data_request(run_id,attempt,request_key_hash),
  CONSTRAINT ck_paiflow_data_completion_attempt CHECK(attempt>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
