-- 未执行增量草案：在001之后执行。真实MySQL兼容、备份与旧成员ID回填需迁移环境另行核验。
-- 业务团队与空间独立，平台账号仅引用user_id；没有商业套餐、认证、通知送达的伪造种子。
ALTER TABLE paiflow_space_member ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT,
  ADD UNIQUE KEY uq_paiflow_space_member_id (id);

CREATE TABLE paiflow_team (
  id BIGINT NOT NULL AUTO_INCREMENT, name VARCHAR(200) NOT NULL,
  avatar_url VARCHAR(2048) NULL, logo_url VARCHAR(2048) NULL,
  owner_user_id BIGINT NOT NULL, service_type TINYINT NULL, expire_time DATETIME(3) NULL,
  revision BIGINT NOT NULL DEFAULT 0, deleted TINYINT NOT NULL DEFAULT 0,
  active_name VARCHAR(200) GENERATED ALWAYS AS (IF(deleted=0,name,NULL)) STORED,
  active_owner BIGINT GENERATED ALWAYS AS (IF(deleted=0,owner_user_id,NULL)) STORED,
  create_by VARCHAR(64) NOT NULL, update_by VARCHAR(64) NOT NULL,
  create_time DATETIME(3) NOT NULL, update_time DATETIME(3) NOT NULL,
  PRIMARY KEY(id), UNIQUE KEY uq_paiflow_team_name(active_name), UNIQUE KEY uq_paiflow_team_owner(active_owner),
  CONSTRAINT ck_paiflow_team_service CHECK(service_type IS NULL OR service_type IN(1,2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE paiflow_team_member (
  id BIGINT NOT NULL AUTO_INCREMENT, team_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
  role TINYINT NOT NULL, status VARCHAR(16) NOT NULL, revision BIGINT NOT NULL DEFAULT 0,
  create_by VARCHAR(64) NOT NULL, update_by VARCHAR(64) NOT NULL,
  create_time DATETIME(3) NOT NULL, update_time DATETIME(3) NOT NULL,
  PRIMARY KEY(id), UNIQUE KEY uq_paiflow_team_member(team_id,user_id), KEY ix_paiflow_team_member_user(user_id,status,team_id),
  CONSTRAINT ck_paiflow_team_member_role CHECK(role IN(1,2,3)),
  CONSTRAINT ck_paiflow_team_member_status CHECK(status IN('active','removed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE paiflow_team_visit (
  user_id BIGINT NOT NULL, team_id BIGINT NOT NULL, last_visit_time DATETIME(3) NOT NULL,
  PRIMARY KEY(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 个人邀请容量跨所属空间计数；此行只用于串行化，不能作为商业配额事实。
CREATE TABLE paiflow_membership_scope_lock (owner_user_id BIGINT NOT NULL, PRIMARY KEY(owner_user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE paiflow_membership_invite (
  id BIGINT NOT NULL AUTO_INCREMENT, type TINYINT NOT NULL,
  space_id BIGINT NULL, team_id BIGINT NULL, owner_user_id BIGINT NOT NULL,
  invitee_user_id BIGINT NOT NULL, role TINYINT NOT NULL, inviter_user_id BIGINT NOT NULL,
  status TINYINT NOT NULL DEFAULT 1, revision BIGINT NOT NULL DEFAULT 0,
  notification_status VARCHAR(32) NOT NULL DEFAULT 'not_connected',
  expire_time DATETIME(3) NOT NULL, create_time DATETIME(3) NOT NULL, update_time DATETIME(3) NOT NULL,
  pending_scope VARCHAR(64) GENERATED ALWAYS AS (IF(status=1,IF(type=1,CONCAT('space:',space_id),CONCAT('team:',team_id)),NULL)) STORED,
  PRIMARY KEY(id), UNIQUE KEY uq_paiflow_pending_invite(pending_scope,invitee_user_id),
  KEY ix_paiflow_invite_space(space_id,status,expire_time), KEY ix_paiflow_invite_team(team_id,status,expire_time),
  CONSTRAINT ck_paiflow_invite_type CHECK((type=1 AND space_id IS NOT NULL) OR (type=2 AND space_id IS NULL AND team_id IS NOT NULL)),
  CONSTRAINT ck_paiflow_invite_role CHECK(role IN(2,3)), CONSTRAINT ck_paiflow_invite_status CHECK(status IN(1,2,3,4,5))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE paiflow_membership_apply (
  id BIGINT NOT NULL AUTO_INCREMENT, team_id BIGINT NOT NULL, space_id BIGINT NOT NULL,
  applicant_user_id BIGINT NOT NULL, status TINYINT NOT NULL DEFAULT 1,
  audit_user_id BIGINT NULL, audit_time DATETIME(3) NULL, revision BIGINT NOT NULL DEFAULT 0,
  create_time DATETIME(3) NOT NULL, update_time DATETIME(3) NOT NULL,
  pending_user BIGINT GENERATED ALWAYS AS (IF(status=1,applicant_user_id,NULL)) STORED,
  PRIMARY KEY(id), UNIQUE KEY uq_paiflow_pending_apply(space_id,pending_user),
  KEY ix_paiflow_apply_space(space_id,status,id), CONSTRAINT ck_paiflow_apply_status CHECK(status IN(1,2,3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
