-- Java原ChatItem与该次调用前的固定历史快照；沿用021中心身份/空间/会话，不建立Worker业务库。
-- 本增量仅保存待审核源码，未执行真实MySQL。
CREATE TABLE paiflow_java_node_history (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  lease_id VARCHAR(64) NOT NULL,
  attempt INT NOT NULL,
  node_id VARCHAR(255) NOT NULL,
  workflow_version_id BIGINT NOT NULL,
  dsl_hash CHAR(64) NOT NULL,
  input_hash CHAR(64) NOT NULL,
  snapshot_json MEDIUMTEXT NOT NULL,
  snapshot_hash CHAR(64) NOT NULL,
  item_json MEDIUMTEXT NOT NULL,
  item_hash CHAR(64) NOT NULL,
  model_operation_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  state VARCHAR(16) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  PRIMARY KEY(id),
  UNIQUE KEY uk_paiflow_java_history_node(run_id,attempt,node_id),
  UNIQUE KEY uk_paiflow_java_history_operation(model_operation_id),
  KEY ix_paiflow_java_history_window(node_id,id),
  CONSTRAINT fk_paiflow_java_history_context FOREIGN KEY(run_id) REFERENCES paiflow_run_context(run_id),
  CONSTRAINT fk_paiflow_java_history_operation FOREIGN KEY(model_operation_id) REFERENCES paiflow_model_operation(id),
  CONSTRAINT ck_paiflow_java_history_attempt CHECK(attempt>0),
  CONSTRAINT ck_paiflow_java_history_state CHECK((state='OPEN' AND model_operation_id IS NULL) OR (state='COMPLETED' AND model_operation_id IS NOT NULL)),
  CONSTRAINT ck_paiflow_java_history_revision CHECK(revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
