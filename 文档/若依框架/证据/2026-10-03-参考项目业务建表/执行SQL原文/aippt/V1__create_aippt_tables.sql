-- AI 演示文稿正式 MySQL schema，人工审核并授权后执行；禁止自动创建新数据库。

-- body 保存完整 typed snake_case 文档；revision/status/owner/project 为事务和访问裁决索引列。

CREATE TABLE IF NOT EXISTS aippt_project (
  event_seq BIGINT NOT NULL DEFAULT 0,
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  owner_user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  body JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY(id),
  KEY ix_project_project(project_id,created_at,id),
  KEY ix_project_owner(owner_user_id,updated_at,id),
  KEY ix_project_state(status,created_at),
  CONSTRAINT ck_project_revision CHECK(revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS aippt_source (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  owner_user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  body JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY(id),
  KEY ix_source_project(project_id,created_at,id),
  KEY ix_source_owner(owner_user_id,updated_at,id),
  KEY ix_source_state(status,created_at),
  CONSTRAINT ck_source_revision CHECK(revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS aippt_source_section (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  owner_user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  body JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY(id),
  KEY ix_source_section_project(project_id,created_at,id),
  KEY ix_source_section_owner(owner_user_id,updated_at,id),
  KEY ix_source_section_state(status,created_at),
  CONSTRAINT ck_source_section_revision CHECK(revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS aippt_outline (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  owner_user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  body JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY(id),
  KEY ix_outline_project(project_id,created_at,id),
  KEY ix_outline_owner(owner_user_id,updated_at,id),
  KEY ix_outline_state(status,created_at),
  UNIQUE KEY uk_outline_project(project_id),
  CONSTRAINT ck_outline_revision CHECK(revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS aippt_slide (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  owner_user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  body JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY(id),
  KEY ix_slide_project(project_id,created_at,id),
  KEY ix_slide_owner(owner_user_id,updated_at,id),
  KEY ix_slide_state(status,created_at),
  CONSTRAINT ck_slide_revision CHECK(revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS aippt_task (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  owner_user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  body JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY(id),
  KEY ix_task_project(project_id,created_at,id),
  KEY ix_task_owner(owner_user_id,updated_at,id),
  KEY ix_task_state(status,created_at),
  CONSTRAINT ck_task_revision CHECK(revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS aippt_proposal (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  owner_user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  body JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY(id),
  KEY ix_proposal_project(project_id,created_at,id),
  KEY ix_proposal_owner(owner_user_id,updated_at,id),
  KEY ix_proposal_state(status,created_at),
  CONSTRAINT ck_proposal_revision CHECK(revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS aippt_export (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  owner_user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  body JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY(id),
  KEY ix_export_project(project_id,created_at,id),
  KEY ix_export_owner(owner_user_id,updated_at,id),
  KEY ix_export_state(status,created_at),
  CONSTRAINT ck_export_revision CHECK(revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS aippt_asset (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  owner_user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  body JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY(id),
  KEY ix_asset_project(project_id,created_at,id),
  KEY ix_asset_owner(owner_user_id,updated_at,id),
  KEY ix_asset_state(status,created_at),
  CONSTRAINT ck_asset_revision CHECK(revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS aippt_event (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  owner_user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  body JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY(id),
  KEY ix_event_project(project_id,created_at,id),
  KEY ix_event_owner(owner_user_id,updated_at,id),
  KEY ix_event_state(status,created_at),
  UNIQUE KEY uk_event_sequence(project_id,revision),
  CONSTRAINT ck_event_revision CHECK(revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS aippt_audit (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  owner_user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  body JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY(id),
  KEY ix_audit_project(project_id,created_at,id),
  KEY ix_audit_owner(owner_user_id,updated_at,id),
  KEY ix_audit_state(status,created_at),
  CONSTRAINT ck_audit_revision CHECK(revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS aippt_outbox (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  owner_user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  body JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY(id),
  KEY ix_outbox_project(project_id,created_at,id),
  KEY ix_outbox_owner(owner_user_id,updated_at,id),
  KEY ix_outbox_state(status,created_at),
  CONSTRAINT ck_outbox_revision CHECK(revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS aippt_idempotency (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  owner_user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  body JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY(id),
  KEY ix_idempotency_project(project_id,created_at,id),
  KEY ix_idempotency_owner(owner_user_id,updated_at,id),
  KEY ix_idempotency_state(status,created_at),
  CONSTRAINT ck_idempotency_revision CHECK(revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS aippt_snapshot (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  owner_user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  body JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY(id),
  KEY ix_snapshot_project(project_id,created_at,id),
  KEY ix_snapshot_owner(owner_user_id,updated_at,id),
  KEY ix_snapshot_state(status,created_at),
  CONSTRAINT ck_snapshot_revision CHECK(revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS aippt_dispatch_gate(id INT PRIMARY KEY) ENGINE=InnoDB;

INSERT IGNORE INTO aippt_dispatch_gate(id) VALUES(1);
