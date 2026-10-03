-- 仅由批准的显式迁移执行；保留已执行项和来源行，重试不重复业务写入。
CREATE TABLE ps_operation_item (
 plan_id VARCHAR(36) NOT NULL, item_key VARCHAR(160) NOT NULL,
 payload_hash CHAR(64) NOT NULL, state VARCHAR(24) NOT NULL,
 result_json TEXT NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(plan_id,item_key)
);
CREATE TABLE ps_legacy_migration_batch (
 id VARCHAR(64) PRIMARY KEY, manifest_hash CHAR(64) NOT NULL,
 source_snapshot VARCHAR(128) NOT NULL, source_hash CHAR(64) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE ps_legacy_migration_row (
 source_table VARCHAR(64) NOT NULL, source_id VARCHAR(160) NOT NULL,
 source_hash CHAR(64) NOT NULL, target_table VARCHAR(64) NOT NULL,
 target_id VARCHAR(160) NOT NULL, migration_batch_id VARCHAR(64) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(source_table,source_id)
);
CREATE TABLE ps_legacy_archive (
 source_table VARCHAR(64) NOT NULL, source_id VARCHAR(160) NOT NULL,
 sanitized_json LONGTEXT NOT NULL, migration_batch_id VARCHAR(64) NOT NULL,
 PRIMARY KEY(source_table,source_id)
);
CREATE TABLE ps_bootstrap_document (
 logical_key CHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL, space_id VARCHAR(36),
 document_id VARCHAR(36) NOT NULL, file_name VARCHAR(255) NOT NULL, sha256 CHAR(64) NOT NULL,
 row_version BIGINT NOT NULL DEFAULT 1, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS paismart_schema_history (version_no INT PRIMARY KEY, description VARCHAR(128) NOT NULL, installed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);
INSERT INTO paismart_schema_history(version_no,description) VALUES(4,'Paismart operation and migration receipts');
