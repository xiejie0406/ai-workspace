-- 本地源码迁移增量，未执行。沿用本项目既有MySQL；不初始化/复制数据库。
-- 正式应用不得借调试域声明App授权；调试app_id由中心固定为debug:<workflow_id>。
CREATE TABLE paiflow_run_context (
  run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  space_id BIGINT NOT NULL,
  workflow_id BIGINT NOT NULL,
  owner_user_id BIGINT NOT NULL,
  flow_id VARCHAR(128) NOT NULL,
  app_id VARCHAR(128) NOT NULL,
  chat_id VARCHAR(128) NOT NULL,
  PRIMARY KEY(run_id),
  KEY ix_paiflow_history_scope(space_id,workflow_id,owner_user_id,app_id,chat_id),
  CONSTRAINT fk_paiflow_context_run FOREIGN KEY(run_id) REFERENCES paiflow_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE paiflow_node_history (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  attempt INT NOT NULL,
  node_id VARCHAR(255) NOT NULL,
  request_key_hash CHAR(64) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  question_json MEDIUMTEXT NOT NULL,
  answer_json MEDIUMTEXT NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  create_time DATETIME(3) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_paiflow_history_request(run_id,attempt,request_key_hash),
  KEY ix_paiflow_history_node(run_id,node_id,id),
  CONSTRAINT fk_paiflow_history_context FOREIGN KEY(run_id) REFERENCES paiflow_run_context(run_id),
  CONSTRAINT ck_paiflow_history_attempt CHECK(attempt>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
