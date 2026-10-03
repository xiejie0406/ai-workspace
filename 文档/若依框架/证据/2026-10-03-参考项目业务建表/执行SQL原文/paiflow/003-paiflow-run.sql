-- 未执行增量草案：必须在用户指定原业务MySQL和已批准备份/迁移步骤中执行。
-- 不创建数据库，不写共享账号，不由应用自动初始化。
CREATE TABLE paiflow_run (
 id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 workflow_id BIGINT NOT NULL,workflow_version_id BIGINT NOT NULL,space_id BIGINT NOT NULL,owner_user_id BIGINT NOT NULL,
 engine_profile VARCHAR(16) NOT NULL,state VARCHAR(24) NOT NULL,
 assignment_json LONGTEXT NOT NULL,input_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 worker_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,lease_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 attempt INT NOT NULL DEFAULT 0,lease_expires_at DATETIME(6) NULL,deadline DATETIME(6) NOT NULL,
 last_event_seq BIGINT NOT NULL DEFAULT 0,revision BIGINT NOT NULL DEFAULT 0,
 result_json LONGTEXT NULL,result_hash CHAR(64) CHARACTER SET ascii NULL,
 create_by VARCHAR(64) NOT NULL,create_time DATETIME(6) NOT NULL,update_time DATETIME(6) NOT NULL,
 PRIMARY KEY(id),UNIQUE KEY uq_paiflow_run_request(owner_user_id,idempotency_key),
 KEY ix_paiflow_run_queue(engine_profile,state,create_time),KEY ix_paiflow_run_space(space_id,create_time),
 CONSTRAINT ck_paiflow_run_engine CHECK(engine_profile IN('java','python')),
 CONSTRAINT ck_paiflow_run_state CHECK(state IN('QUEUED','RUNNING','CANCEL_REQUESTED','COMPLETED','FAILED','CANCELLED','EFFECT_UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE paiflow_run_event (
 run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,seq BIGINT NOT NULL,
 event_json LONGTEXT NOT NULL,create_time DATETIME(6) NOT NULL,
 PRIMARY KEY(run_id,seq),CONSTRAINT ck_paiflow_event_seq CHECK(seq > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
