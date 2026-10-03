-- 未执行的MySQL增量草案；沿用用户指定原业务库，不建/切库、不初始化原数据。
-- 执行前核对001空间结构、字符集及现有同名表；备份现有原文/数据后才可批准执行。
CREATE TABLE paiflow_workflow (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  flow_id VARCHAR(64) NOT NULL UNIQUE,
  space_id BIGINT NOT NULL,
  owner_user_id BIGINT NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  name VARCHAR(200) NOT NULL,
  description VARCHAR(2000) NULL,
  canvas_json LONGTEXT NOT NULL,
  metadata_json LONGTEXT NOT NULL,
  advanced_config_json LONGTEXT NOT NULL,
  flow_config_json LONGTEXT NOT NULL,
  engine_profile VARCHAR(16) NOT NULL,
  latest_version_id BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  active_name VARCHAR(200) GENERATED ALWAYS AS (CASE WHEN deleted=0 THEN name ELSE NULL END) STORED,
  create_by VARCHAR(64) NOT NULL, update_by VARCHAR(64) NOT NULL,
  create_time DATETIME(3) NOT NULL, update_time DATETIME(3) NOT NULL,
  UNIQUE KEY uk_paiflow_workflow_active_name(space_id,active_name),
  KEY ix_paiflow_workflow_space(space_id,deleted,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE paiflow_workflow_version (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  workflow_id BIGINT NOT NULL, space_id BIGINT NOT NULL, owner_user_id BIGINT NOT NULL,
  snapshot_revision BIGINT NOT NULL, flow_id VARCHAR(64) NOT NULL,
  version_num VARCHAR(64) NOT NULL, name VARCHAR(200) NOT NULL, description VARCHAR(2000) NULL,
  canvas_json LONGTEXT NOT NULL, execution_json LONGTEXT NOT NULL,
  dsl_hash CHAR(64) NOT NULL, canonical_dsl_base64 LONGTEXT NOT NULL,
  metadata_json LONGTEXT NOT NULL, advanced_config_json LONGTEXT NOT NULL, flow_config_json LONGTEXT NOT NULL,
  engine_profile VARCHAR(16) NOT NULL, create_by VARCHAR(64) NOT NULL, create_time DATETIME(3) NOT NULL,
  UNIQUE KEY uk_paiflow_workflow_version_num(workflow_id,version_num),
  UNIQUE KEY uk_paiflow_workflow_version_name(workflow_id,name),
  KEY ix_paiflow_workflow_version_space(space_id,workflow_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 不提供自动DROP回退：版本可能已被运行/发布引用，须先停写、核对引用并恢复原备份。
