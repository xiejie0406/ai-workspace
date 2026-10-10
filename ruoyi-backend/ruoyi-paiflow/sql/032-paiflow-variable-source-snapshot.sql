-- 源码增量草案，未执行。不用当前可变会话值倒推历史读；不回填旧运行事实。
CREATE TABLE paiflow_variable_source (
  run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  lease_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  attempt INT NOT NULL,
  node_id VARCHAR(255) NOT NULL,
  snapshot_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  operation VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  scope_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  node_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_json MEDIUMTEXT NOT NULL,
  response_json MEDIUMTEXT NOT NULL,
  response_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY(run_id,lease_id,attempt,node_id,snapshot_key),
  CONSTRAINT fk_paiflow_variable_source_run FOREIGN KEY(run_id) REFERENCES paiflow_run(id),
  CONSTRAINT ck_paiflow_variable_source_attempt CHECK(attempt>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
