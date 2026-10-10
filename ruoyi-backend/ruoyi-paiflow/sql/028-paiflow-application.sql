-- PaiFlow中心应用登记和运行关联增量草案；只在隔离H2验证，不能自动执行真实库。
-- 依赖001/002/003/007；不创建数据库，不调用Tenant/Maas，不复制或生成外部渠道凭据。
-- 平台权限由既有菜单机制授权 paiflow:application:query/create/edit/remove/run。
CREATE TABLE paiflow_application (
 id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 space_id BIGINT NOT NULL,owner_user_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL,description VARCHAR(500) NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'UNBOUND',
 agent_id BIGINT NULL,workflow_id BIGINT NULL,workflow_version_id BIGINT NULL,
 dsl_hash CHAR(64) CHARACTER SET ascii NULL,engine_profile VARCHAR(16) NULL,
 revision BIGINT NOT NULL DEFAULT 0,deleted TINYINT NOT NULL DEFAULT 0,
 active_name VARCHAR(100) GENERATED ALWAYS AS (CASE WHEN deleted=0 THEN name ELSE NULL END) STORED,
 create_by VARCHAR(64) NOT NULL,update_by VARCHAR(64) NOT NULL,
 create_time DATETIME(3) NOT NULL,update_time DATETIME(3) NOT NULL,
 PRIMARY KEY(id),UNIQUE KEY uq_paiflow_application_name(active_name),KEY ix_paiflow_application_owner(space_id,owner_user_id,deleted,create_time),
 CONSTRAINT fk_paiflow_application_space FOREIGN KEY(space_id) REFERENCES paiflow_space(id),
 CONSTRAINT fk_paiflow_application_agent FOREIGN KEY(agent_id) REFERENCES paiflow_agent(id),
 CONSTRAINT fk_paiflow_application_workflow FOREIGN KEY(workflow_id) REFERENCES paiflow_workflow(id),
 CONSTRAINT fk_paiflow_application_version FOREIGN KEY(workflow_version_id) REFERENCES paiflow_workflow_version(id),
 CONSTRAINT ck_paiflow_application_state CHECK(state IN('UNBOUND','READY','DISABLED')),
 CONSTRAINT ck_paiflow_application_deleted CHECK(deleted IN(0,1)),
 CONSTRAINT ck_paiflow_application_engine CHECK(engine_profile IS NULL OR engine_profile IN('java','python')),
 CONSTRAINT ck_paiflow_application_binding CHECK(
  (agent_id IS NULL AND workflow_id IS NULL AND workflow_version_id IS NULL AND dsl_hash IS NULL AND engine_profile IS NULL AND state<>'READY')
  OR (agent_id IS NOT NULL AND workflow_id IS NOT NULL AND workflow_version_id IS NOT NULL AND dsl_hash IS NOT NULL AND engine_profile IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE paiflow_application_run (
 run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 application_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 owner_user_id BIGINT NOT NULL,workflow_version_id BIGINT NOT NULL,application_revision BIGINT NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 create_time DATETIME(6) NOT NULL,
 PRIMARY KEY(run_id),UNIQUE KEY uq_paiflow_application_run_request(application_id,owner_user_id,request_key),
 CONSTRAINT fk_paiflow_application_run_app FOREIGN KEY(application_id) REFERENCES paiflow_application(id),
 CONSTRAINT fk_paiflow_application_run_run FOREIGN KEY(run_id) REFERENCES paiflow_run(id),
 CONSTRAINT fk_paiflow_application_run_version FOREIGN KEY(workflow_version_id) REFERENCES paiflow_workflow_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
