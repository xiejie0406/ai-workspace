-- 本迁移仅由显式 Paismart 迁移入口执行；不修改平台账号及其他项目表。
CREATE TABLE ps_space (
  id VARCHAR(36) PRIMARY KEY, parent_id VARCHAR(36), name VARCHAR(120) NOT NULL, description TEXT,
  system_key VARCHAR(40),
  type VARCHAR(20) NOT NULL DEFAULT 'ORGANIZATION', dept_id BIGINT, owner_id BIGINT,
  upload_limit INT NOT NULL DEFAULT -1, upload_max_size_bytes BIGINT, row_version BIGINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX ix_ps_space_parent(parent_id), UNIQUE KEY uq_ps_space_personal(owner_id), UNIQUE KEY uq_ps_space_system(system_key)
);
CREATE TABLE ps_member_space (
  user_id BIGINT NOT NULL, space_id VARCHAR(36) NOT NULL,
  primary_flag BOOLEAN NOT NULL DEFAULT FALSE, upload_allowed BOOLEAN NOT NULL DEFAULT FALSE,
  manager_allowed BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(user_id, space_id), INDEX ix_ps_member_space_space(space_id)
);
CREATE TABLE ps_document (
  id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, space_id VARCHAR(36),
  visibility VARCHAR(20) NOT NULL, file_name VARCHAR(255) NOT NULL,
  sha256 CHAR(64) NOT NULL, legacy_md5 CHAR(32), object_key VARCHAR(500) NOT NULL,
  byte_size BIGINT NOT NULL, mime_type VARCHAR(120) NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'QUEUED', version INT NOT NULL DEFAULT 1,
  content_text LONGTEXT, error_message VARCHAR(2000), row_version BIGINT NOT NULL DEFAULT 1,
  estimated_tokens BIGINT NOT NULL DEFAULT 0, actual_tokens BIGINT NOT NULL DEFAULT 0,
  page_count INT, process_job_id VARCHAR(36),
  deleted_at TIMESTAMP NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX ix_ps_document_owner(user_id,deleted_at), INDEX ix_ps_document_space(space_id,deleted_at),
  INDEX ix_ps_document_hash(sha256)
);
CREATE TABLE ps_upload (
  id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, space_id VARCHAR(36),
  visibility VARCHAR(20) NOT NULL, file_name VARCHAR(255) NOT NULL,
  byte_size BIGINT NOT NULL, sha256 CHAR(64) NOT NULL, legacy_md5 CHAR(32),
  chunk_size INT NOT NULL, total_chunks INT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'UPLOADING', document_id VARCHAR(36),
  request_key VARCHAR(120) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP NOT NULL, UNIQUE KEY uq_ps_upload_request(user_id,request_key)
);
CREATE TABLE ps_upload_chunk (
  upload_id VARCHAR(36) NOT NULL, chunk_no INT NOT NULL, byte_size BIGINT NOT NULL,
  sha256 CHAR(64) NOT NULL, object_key VARCHAR(500) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(upload_id,chunk_no)
);
CREATE TABLE ps_upload_request (
  user_id BIGINT NOT NULL, request_key VARCHAR(100) NOT NULL, upload_id VARCHAR(36) NOT NULL,
  payload_hash CHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(user_id,request_key), INDEX ix_ps_upload_request_upload(upload_id)
);
-- 只在全部本版本DDL成功后记录；孤立内存测试使用相同history结构。
CREATE TABLE IF NOT EXISTS paismart_schema_history (version_no INT PRIMARY KEY, description VARCHAR(128) NOT NULL, installed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);
INSERT INTO paismart_schema_history(version_no,description) VALUES(2,'Paismart documents and spaces');
