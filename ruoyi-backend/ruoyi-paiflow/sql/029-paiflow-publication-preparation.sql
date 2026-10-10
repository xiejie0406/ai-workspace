-- 发布准备与固定调试证据增量草案；只在隔离H2验证，不自动执行真实库。
-- 依赖028以及工作流/运行表；没有外部渠道回执，不存ACTIVE或调用凭据。
CREATE TABLE paiflow_publication_preparation (
 id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 application_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 application_revision BIGINT NOT NULL,space_id BIGINT NOT NULL,owner_user_id BIGINT NOT NULL,
 agent_id BIGINT NOT NULL,workflow_id BIGINT NOT NULL,workflow_version_id BIGINT NOT NULL,
 dsl_hash CHAR(64) CHARACTER SET ascii NOT NULL,engine_profile VARCHAR(16) NOT NULL,
 channel VARCHAR(24) NOT NULL,state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 debug_run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,debug_workflow_version_id BIGINT NOT NULL,
 proof_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 fixed_snapshot_revision BIGINT NOT NULL,debug_snapshot_revision BIGINT NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,request_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,create_by VARCHAR(64) NOT NULL,create_time DATETIME(3) NOT NULL,
 PRIMARY KEY(id),UNIQUE KEY uq_paiflow_publication_prepare_request(application_id,owner_user_id,request_key),
 KEY ix_paiflow_publication_prepare_owner(space_id,owner_user_id,create_time),
 CONSTRAINT fk_paiflow_publication_prepare_app FOREIGN KEY(application_id) REFERENCES paiflow_application(id),
 CONSTRAINT fk_paiflow_publication_prepare_space FOREIGN KEY(space_id) REFERENCES paiflow_space(id),
 CONSTRAINT fk_paiflow_publication_prepare_agent FOREIGN KEY(agent_id) REFERENCES paiflow_agent(id),
 CONSTRAINT fk_paiflow_publication_prepare_version FOREIGN KEY(workflow_version_id) REFERENCES paiflow_workflow_version(id),
 CONSTRAINT fk_paiflow_publication_prepare_debug_version FOREIGN KEY(debug_workflow_version_id) REFERENCES paiflow_workflow_version(id),
 CONSTRAINT fk_paiflow_publication_prepare_debug_run FOREIGN KEY(debug_run_id) REFERENCES paiflow_run(id),
 CONSTRAINT ck_paiflow_publication_prepare_state CHECK(state IN('PENDING','WITHDRAWN')),
 CONSTRAINT ck_paiflow_publication_prepare_channel CHECK(channel IN('MARKET','API','MCP','WECHAT','FEISHU','IFLYTEK_AIUI','IFLYTEK_OPEN')),
 CONSTRAINT ck_paiflow_publication_prepare_revision CHECK(revision>=0 AND application_revision>=0 AND fixed_snapshot_revision>=0 AND debug_snapshot_revision>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
