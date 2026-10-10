-- 本地源码增量草案，未执行；原7天变量缓存语义由中心MySQL派生状态承接。
-- scope_key由中心的空间/工作流/用户/App/会话五元组规范JSON计算SHA-256。
CREATE TABLE paiflow_runtime_variable (
  scope_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  values_json MEDIUMTEXT NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  expires_at DATETIME(3) NOT NULL,
  PRIMARY KEY(scope_key),CONSTRAINT ck_paiflow_variable_revision CHECK(revision>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE paiflow_variable_request (
  run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  attempt INT NOT NULL,
  request_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  scope_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision BIGINT NOT NULL,
  PRIMARY KEY(run_id,attempt,request_key_hash),
  CONSTRAINT fk_paiflow_variable_request_run FOREIGN KEY(run_id) REFERENCES paiflow_run(id),
  CONSTRAINT fk_paiflow_variable_request_scope FOREIGN KEY(scope_key) REFERENCES paiflow_runtime_variable(scope_key),
  CONSTRAINT ck_paiflow_variable_request_revision CHECK(revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
