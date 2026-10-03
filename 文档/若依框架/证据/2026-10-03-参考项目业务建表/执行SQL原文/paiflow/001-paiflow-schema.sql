-- PaiFlow增量MySQL结构：当前先落实身份偏好与空间关系，后续实体追加至同一脚本。
-- 仅生成可审查脚本；未执行。须核对用户指定原库/MySQL版本/备份后另授权运行。
-- 不CREATE DATABASE，不复制平台账号/密码/token，不建立跨库外键，不初始化业务数据。

CREATE TABLE paiflow_identity_map (
  id BIGINT NOT NULL AUTO_INCREMENT,
  source VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  old_uid VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
  platform_user_id BIGINT NULL,
  status VARCHAR(24) NOT NULL COMMENT 'mapped/conflict/unclaimed',
  mapping_version BIGINT NOT NULL DEFAULT 1,
  source_hash CHAR(64) NULL,
  batch_id BIGINT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0,
  create_by VARCHAR(64) NOT NULL,
  update_by VARCHAR(64) NOT NULL,
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY uq_paiflow_source_uid (source, old_uid),
  KEY ix_paiflow_platform_user (platform_user_id, status),
  CONSTRAINT ck_paiflow_identity_state CHECK (status IN ('mapped','conflict','unclaimed')),
  CONSTRAINT ck_paiflow_identity_mapped CHECK (status <> 'mapped' OR platform_user_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE paiflow_user_preference (
  user_id BIGINT NOT NULL COMMENT '平台稳定用户ID，不复制账号',
  user_agreement TINYINT NOT NULL DEFAULT 0,
  agreed_at DATETIME(3) NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0,
  create_by VARCHAR(64) NOT NULL,
  update_by VARCHAR(64) NOT NULL,
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (user_id),
  CONSTRAINT ck_paiflow_agreement CHECK (user_agreement IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE paiflow_space (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(200) NOT NULL,
  description VARCHAR(2000) NULL,
  avatar_url VARCHAR(2048) NULL,
  owner_user_id BIGINT NOT NULL,
  dept_id BIGINT NULL,
  team_id BIGINT NULL,
  kind VARCHAR(16) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0,
  active_name VARCHAR(200) GENERATED ALWAYS AS (IF(deleted = 0, name, NULL)) STORED,
  name_scope VARCHAR(64) GENERATED ALWAYS AS (IF(team_id IS NULL, CONCAT('user:',owner_user_id), CONCAT('team:',team_id))) STORED,
  create_by VARCHAR(64) NOT NULL,
  update_by VARCHAR(64) NOT NULL,
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY uq_paiflow_space_name (name_scope, kind, active_name),
  KEY ix_paiflow_space_owner (owner_user_id, kind, deleted), KEY ix_paiflow_space_team (team_id, deleted),
  CONSTRAINT ck_paiflow_space_kind CHECK (kind IN ('personal','team')),
  CONSTRAINT ck_paiflow_space_team CHECK ((kind = 'personal' AND team_id IS NULL) OR (kind = 'team' AND team_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE paiflow_space_member (
  space_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
  role TINYINT NOT NULL COMMENT '1所有者/2管理员/3成员，只进一步收窄平台权限',
  status VARCHAR(16) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  create_by VARCHAR(64) NOT NULL, update_by VARCHAR(64) NOT NULL,
  create_time DATETIME(3) NOT NULL, update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (space_id, user_id), KEY ix_paiflow_member_user (user_id, status, space_id),
  CONSTRAINT ck_paiflow_member_role CHECK (role IN (1,2,3)),
  CONSTRAINT ck_paiflow_member_status CHECK (status IN ('active','removed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE paiflow_space_visit (
  space_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
  last_visit_time DATETIME(3) NOT NULL,
  PRIMARY KEY (space_id, user_id), KEY ix_paiflow_recent_visit (user_id, last_visit_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
