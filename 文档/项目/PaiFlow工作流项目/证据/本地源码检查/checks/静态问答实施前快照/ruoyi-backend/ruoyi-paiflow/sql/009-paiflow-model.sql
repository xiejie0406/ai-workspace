-- 模型中心增量审查稿，未执行；先核对001空间结构，复用原平台菜单权限。
-- 不导入任何真实配置/凭据，不部署模型，不批量改画布或删除既有快照。
CREATE TABLE paiflow_model (
 id BIGINT NOT NULL AUTO_INCREMENT,space_id BIGINT NOT NULL,owner_user_id BIGINT NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,latest_version_id BIGINT NULL,type TINYINT NOT NULL,
 name VARCHAR(200) NOT NULL,state VARCHAR(32) NOT NULL,enabled TINYINT NOT NULL DEFAULT 0,
 configuration_json MEDIUMTEXT NOT NULL,candidate_hash CHAR(64) NOT NULL,
 credential_version VARCHAR(255) NULL,receipt_json MEDIUMTEXT NULL,deleted TINYINT NOT NULL DEFAULT 0,
 active_name VARCHAR(200) GENERATED ALWAYS AS (CASE WHEN deleted=0 THEN name ELSE NULL END) STORED,
 create_by VARCHAR(64) NOT NULL,update_by VARCHAR(64) NOT NULL,create_time DATETIME(3) NOT NULL,update_time DATETIME(3) NOT NULL,
 PRIMARY KEY(id),UNIQUE KEY uq_paiflow_model_name(space_id,active_name),KEY ix_paiflow_model_scope(space_id,deleted,type),
 CONSTRAINT ck_paiflow_model_type CHECK(type IN (1,2)),CONSTRAINT ck_paiflow_model_flags CHECK(enabled IN (0,1) AND deleted IN (0,1)),
 CONSTRAINT ck_paiflow_model_ready CHECK(enabled=0 OR state='VALIDATED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE paiflow_model_version (
 id BIGINT NOT NULL AUTO_INCREMENT,model_id BIGINT NOT NULL,space_id BIGINT NOT NULL,owner_user_id BIGINT NOT NULL,
 revision BIGINT NOT NULL,type TINYINT NOT NULL,state VARCHAR(32) NOT NULL,enabled TINYINT NOT NULL,
 configuration_json MEDIUMTEXT NOT NULL,candidate_hash CHAR(64) NOT NULL,credential_version VARCHAR(255) NULL,
 receipt_json MEDIUMTEXT NULL,create_by VARCHAR(64) NOT NULL,create_time DATETIME(3) NOT NULL,
 PRIMARY KEY(id),UNIQUE KEY uq_paiflow_model_version(model_id,revision),KEY ix_paiflow_model_version_scope(space_id,model_id),
 CONSTRAINT fk_paiflow_model_version FOREIGN KEY(model_id) REFERENCES paiflow_model(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
