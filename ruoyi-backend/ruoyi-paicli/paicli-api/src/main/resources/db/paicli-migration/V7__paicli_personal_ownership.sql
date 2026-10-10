-- FEAT-PAICLI-003：个人所有权、独立目录上下文及历史只读档案。应用不自动执行。

-- 执行前：原库备份、V1-V6校验、停止旧Runner/PTY/任务；不得创建或克隆数据库。

-- DDL隐式提交，失败保留原库并由批准的显式迁移流程恢复；禁止无备份直接repair。

-- 回滚先停止新写入并独立保全V7后新增记录；恢复原表/菜单备份和原运行包，不删除新增用户内容。

CREATE TEMPORARY TABLE pc_personal_migration_guard(ready INT NOT NULL PRIMARY KEY);

INSERT INTO pc_personal_migration_guard(ready) VALUES(1);
INSERT INTO pc_personal_migration_guard(ready) SELECT 1 WHERE EXISTS(SELECT 1 FROM pc_task WHERE state IN ('WAITING_DIRECTORY','ENQUEUED','LEASED','RUNNING','WAITING_APPROVAL','CANCELLING','RECONCILE_REQUIRED')) OR EXISTS(SELECT 1 FROM pc_terminal_session WHERE state IN ('STARTING','RUNNING','STOPPING'));

DROP TEMPORARY TABLE pc_personal_migration_guard;

CREATE TABLE IF NOT EXISTS pc_directory_context (
  id BIGINT NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NULL,
  directory_context_id BIGINT NULL,
  owner_id BIGINT NULL,
  parent_id BIGINT NULL,
  session_id BIGINT NULL,
  runner_id BIGINT NULL,
  kind VARCHAR(64) NOT NULL,
  state VARCHAR(40) NOT NULL,
  unique_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  payload JSON NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  sequence_number BIGINT NOT NULL DEFAULT 0,
  fencing_token BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  expires_at TIMESTAMP(3) NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uq_pc_directory_context_key(unique_key),
  KEY ix_pc_directory_context_scope(directory_context_id,owner_id,state,id),
  KEY ix_pc_directory_context_owner(owner_id,state,id),
  KEY ix_pc_directory_context_parent(parent_id,state),
  KEY ix_pc_directory_context_session(session_id,sequence_number),
  KEY ix_pc_directory_context_expiry(state,expires_at),
  KEY ix_pc_directory_context_runner(runner_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS pc_legacy_access_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NULL,
  directory_context_id BIGINT NULL,
  owner_id BIGINT NULL,
  parent_id BIGINT NULL,
  session_id BIGINT NULL,
  runner_id BIGINT NULL,
  kind VARCHAR(64) NOT NULL,
  state VARCHAR(40) NOT NULL,
  unique_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  payload JSON NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  sequence_number BIGINT NOT NULL DEFAULT 0,
  fencing_token BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  expires_at TIMESTAMP(3) NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uq_pc_legacy_access_snapshot_key(unique_key),
  KEY ix_pc_legacy_access_snapshot_scope(directory_context_id,owner_id,state,id),
  KEY ix_pc_legacy_access_snapshot_owner(owner_id,state,id),
  KEY ix_pc_legacy_access_snapshot_parent(parent_id,state),
  KEY ix_pc_legacy_access_snapshot_session(session_id,sequence_number),
  KEY ix_pc_legacy_access_snapshot_expiry(state,expires_at),
  KEY ix_pc_legacy_access_snapshot_runner(runner_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS pc_model_release (
  id BIGINT NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NULL,
  directory_context_id BIGINT NULL,
  owner_id BIGINT NULL,
  parent_id BIGINT NULL,
  session_id BIGINT NULL,
  runner_id BIGINT NULL,
  kind VARCHAR(64) NOT NULL,
  state VARCHAR(40) NOT NULL,
  unique_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  payload JSON NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  sequence_number BIGINT NOT NULL DEFAULT 0,
  fencing_token BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  expires_at TIMESTAMP(3) NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uq_pc_model_release_key(unique_key),
  KEY ix_pc_model_release_scope(directory_context_id,owner_id,state,id),
  KEY ix_pc_model_release_owner(owner_id,state,id),
  KEY ix_pc_model_release_parent(parent_id,state),
  KEY ix_pc_model_release_session(session_id,sequence_number),
  KEY ix_pc_model_release_expiry(state,expires_at),
  KEY ix_pc_model_release_runner(runner_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS pc_personal_settings (
  id BIGINT NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NULL,
  directory_context_id BIGINT NULL,
  owner_id BIGINT NULL,
  parent_id BIGINT NULL,
  session_id BIGINT NULL,
  runner_id BIGINT NULL,
  kind VARCHAR(64) NOT NULL,
  state VARCHAR(40) NOT NULL,
  unique_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  payload JSON NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  sequence_number BIGINT NOT NULL DEFAULT 0,
  fencing_token BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  expires_at TIMESTAMP(3) NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uq_pc_personal_settings_key(unique_key),
  KEY ix_pc_personal_settings_scope(directory_context_id,owner_id,state,id),
  KEY ix_pc_personal_settings_owner(owner_id,state,id),
  KEY ix_pc_personal_settings_parent(parent_id,state),
  KEY ix_pc_personal_settings_session(session_id,sequence_number),
  KEY ix_pc_personal_settings_expiry(state,expires_at),
  KEY ix_pc_personal_settings_runner(runner_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_workspace ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_workspace' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_workspace ADD INDEX ix_pc_workspace_personal_owner(owner_id,state,id), ADD INDEX ix_pc_workspace_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_workspace' AND INDEX_NAME='ix_pc_workspace_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_workspace_member ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_workspace_member' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_workspace_member ADD INDEX ix_pc_workspace_member_personal_owner(owner_id,state,id), ADD INDEX ix_pc_workspace_member_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_workspace_member' AND INDEX_NAME='ix_pc_workspace_member_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_runner ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_runner' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_runner ADD INDEX ix_pc_runner_personal_owner(owner_id,state,id), ADD INDEX ix_pc_runner_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_runner' AND INDEX_NAME='ix_pc_runner_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_runner_credential ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_runner_credential' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_runner_credential ADD INDEX ix_pc_runner_credential_personal_owner(owner_id,state,id), ADD INDEX ix_pc_runner_credential_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_runner_credential' AND INDEX_NAME='ix_pc_runner_credential_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_session ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_session' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_session ADD INDEX ix_pc_session_personal_owner(owner_id,state,id), ADD INDEX ix_pc_session_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_session' AND INDEX_NAME='ix_pc_session_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_turn ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_turn' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_turn ADD INDEX ix_pc_turn_personal_owner(owner_id,state,id), ADD INDEX ix_pc_turn_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_turn' AND INDEX_NAME='ix_pc_turn_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_message ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_message' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_message ADD INDEX ix_pc_message_personal_owner(owner_id,state,id), ADD INDEX ix_pc_message_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_message' AND INDEX_NAME='ix_pc_message_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_task ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_task' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_task ADD INDEX ix_pc_task_personal_owner(owner_id,state,id), ADD INDEX ix_pc_task_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_task' AND INDEX_NAME='ix_pc_task_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_plan ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_plan' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_plan ADD INDEX ix_pc_plan_personal_owner(owner_id,state,id), ADD INDEX ix_pc_plan_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_plan' AND INDEX_NAME='ix_pc_plan_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_plan_step ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_plan_step' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_plan_step ADD INDEX ix_pc_plan_step_personal_owner(owner_id,state,id), ADD INDEX ix_pc_plan_step_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_plan_step' AND INDEX_NAME='ix_pc_plan_step_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_task_lease ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_task_lease' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_task_lease ADD INDEX ix_pc_task_lease_personal_owner(owner_id,state,id), ADD INDEX ix_pc_task_lease_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_task_lease' AND INDEX_NAME='ix_pc_task_lease_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_approval ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_approval' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_approval ADD INDEX ix_pc_approval_personal_owner(owner_id,state,id), ADD INDEX ix_pc_approval_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_approval' AND INDEX_NAME='ix_pc_approval_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_tool_call ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_tool_call' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_tool_call ADD INDEX ix_pc_tool_call_personal_owner(owner_id,state,id), ADD INDEX ix_pc_tool_call_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_tool_call' AND INDEX_NAME='ix_pc_tool_call_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_tool_receipt ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_tool_receipt' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_tool_receipt ADD INDEX ix_pc_tool_receipt_personal_owner(owner_id,state,id), ADD INDEX ix_pc_tool_receipt_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_tool_receipt' AND INDEX_NAME='ix_pc_tool_receipt_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_policy_version ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_policy_version' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_policy_version ADD INDEX ix_pc_policy_version_personal_owner(owner_id,state,id), ADD INDEX ix_pc_policy_version_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_policy_version' AND INDEX_NAME='ix_pc_policy_version_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_memory ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_memory' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_memory ADD INDEX ix_pc_memory_personal_owner(owner_id,state,id), ADD INDEX ix_pc_memory_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_memory' AND INDEX_NAME='ix_pc_memory_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_memory_version ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_memory_version' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_memory_version ADD INDEX ix_pc_memory_version_personal_owner(owner_id,state,id), ADD INDEX ix_pc_memory_version_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_memory_version' AND INDEX_NAME='ix_pc_memory_version_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_context_summary ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_context_summary' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_context_summary ADD INDEX ix_pc_context_summary_personal_owner(owner_id,state,id), ADD INDEX ix_pc_context_summary_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_context_summary' AND INDEX_NAME='ix_pc_context_summary_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_index_manifest ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_index_manifest' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_index_manifest ADD INDEX ix_pc_index_manifest_personal_owner(owner_id,state,id), ADD INDEX ix_pc_index_manifest_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_index_manifest' AND INDEX_NAME='ix_pc_index_manifest_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_snapshot ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_snapshot' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_snapshot ADD INDEX ix_pc_snapshot_personal_owner(owner_id,state,id), ADD INDEX ix_pc_snapshot_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_snapshot' AND INDEX_NAME='ix_pc_snapshot_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_artifact ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_artifact' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_artifact ADD INDEX ix_pc_artifact_personal_owner(owner_id,state,id), ADD INDEX ix_pc_artifact_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_artifact' AND INDEX_NAME='ix_pc_artifact_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_terminal_session ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_terminal_session' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_terminal_session ADD INDEX ix_pc_terminal_session_personal_owner(owner_id,state,id), ADD INDEX ix_pc_terminal_session_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_terminal_session' AND INDEX_NAME='ix_pc_terminal_session_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_provider_config ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_provider_config' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_provider_config ADD INDEX ix_pc_provider_config_personal_owner(owner_id,state,id), ADD INDEX ix_pc_provider_config_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_provider_config' AND INDEX_NAME='ix_pc_provider_config_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_model_config ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_model_config' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_model_config ADD INDEX ix_pc_model_config_personal_owner(owner_id,state,id), ADD INDEX ix_pc_model_config_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_model_config' AND INDEX_NAME='ix_pc_model_config_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_mcp_config ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_mcp_config' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_mcp_config ADD INDEX ix_pc_mcp_config_personal_owner(owner_id,state,id), ADD INDEX ix_pc_mcp_config_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_mcp_config' AND INDEX_NAME='ix_pc_mcp_config_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_skill_config ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_skill_config' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_skill_config ADD INDEX ix_pc_skill_config_personal_owner(owner_id,state,id), ADD INDEX ix_pc_skill_config_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_skill_config' AND INDEX_NAME='ix_pc_skill_config_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_browser_config ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_browser_config' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_browser_config ADD INDEX ix_pc_browser_config_personal_owner(owner_id,state,id), ADD INDEX ix_pc_browser_config_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_browser_config' AND INDEX_NAME='ix_pc_browser_config_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_wechat_binding ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_wechat_binding' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_wechat_binding ADD INDEX ix_pc_wechat_binding_personal_owner(owner_id,state,id), ADD INDEX ix_pc_wechat_binding_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_wechat_binding' AND INDEX_NAME='ix_pc_wechat_binding_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_event ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_event' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_event ADD INDEX ix_pc_event_personal_owner(owner_id,state,id), ADD INDEX ix_pc_event_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_event' AND INDEX_NAME='ix_pc_event_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_dispatch_outbox ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_dispatch_outbox' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_dispatch_outbox ADD INDEX ix_pc_dispatch_outbox_personal_owner(owner_id,state,id), ADD INDEX ix_pc_dispatch_outbox_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_dispatch_outbox' AND INDEX_NAME='ix_pc_dispatch_outbox_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_security_audit ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_security_audit' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_security_audit ADD INDEX ix_pc_security_audit_personal_owner(owner_id,state,id), ADD INDEX ix_pc_security_audit_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_security_audit' AND INDEX_NAME='ix_pc_security_audit_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_evaluation_run ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_evaluation_run' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_evaluation_run ADD INDEX ix_pc_evaluation_run_personal_owner(owner_id,state,id), ADD INDEX ix_pc_evaluation_run_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_evaluation_run' AND INDEX_NAME='ix_pc_evaluation_run_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_harness_run ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_harness_run' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_harness_run ADD INDEX ix_pc_harness_run_personal_owner(owner_id,state,id), ADD INDEX ix_pc_harness_run_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_harness_run' AND INDEX_NAME='ix_pc_harness_run_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_idempotency ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_idempotency' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_idempotency ADD INDEX ix_pc_idempotency_personal_owner(owner_id,state,id), ADD INDEX ix_pc_idempotency_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_idempotency' AND INDEX_NAME='ix_pc_idempotency_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_import_batch ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_import_batch' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_import_batch ADD INDEX ix_pc_import_batch_personal_owner(owner_id,state,id), ADD INDEX ix_pc_import_batch_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_import_batch' AND INDEX_NAME='ix_pc_import_batch_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_prompt_template ADD COLUMN directory_context_id BIGINT NULL','SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_prompt_template' AND COLUMN_NAME='directory_context_id');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

SET @pc_sql=(SELECT IF(COUNT(*)=0,'ALTER TABLE pc_prompt_template ADD INDEX ix_pc_prompt_template_personal_owner(owner_id,state,id), ADD INDEX ix_pc_prompt_template_directory(directory_context_id,state,id)','SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pc_prompt_template' AND INDEX_NAME='ix_pc_prompt_template_personal_owner');

PREPARE pc_personal_stmt FROM @pc_sql; EXECUTE pc_personal_stmt; DEALLOCATE PREPARE pc_personal_stmt;

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'WORKSPACE','FROZEN',CONCAT('legacy:WORKSPACE:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','WORKSPACE','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_workspace r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'WORKSPACE' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:WORKSPACE:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'WORKSPACE_MEMBER','FROZEN',CONCAT('legacy:WORKSPACE_MEMBER:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','WORKSPACE_MEMBER','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_workspace_member r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'WORKSPACE_MEMBER' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:WORKSPACE_MEMBER:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'RUNNER','FROZEN',CONCAT('legacy:RUNNER:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','RUNNER','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_runner r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'RUNNER' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:RUNNER:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'RUNNER_CREDENTIAL','FROZEN',CONCAT('legacy:RUNNER_CREDENTIAL:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','RUNNER_CREDENTIAL','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_runner_credential r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'RUNNER_CREDENTIAL' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:RUNNER_CREDENTIAL:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'SESSION','FROZEN',CONCAT('legacy:SESSION:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','SESSION','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_session r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'SESSION' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:SESSION:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'TURN','FROZEN',CONCAT('legacy:TURN:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','TURN','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_turn r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'TURN' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:TURN:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'MESSAGE','FROZEN',CONCAT('legacy:MESSAGE:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','MESSAGE','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_message r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'MESSAGE' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:MESSAGE:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'TASK','FROZEN',CONCAT('legacy:TASK:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','TASK','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_task r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'TASK' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:TASK:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'PLAN','FROZEN',CONCAT('legacy:PLAN:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','PLAN','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_plan r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'PLAN' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:PLAN:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'PLAN_STEP','FROZEN',CONCAT('legacy:PLAN_STEP:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','PLAN_STEP','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_plan_step r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'PLAN_STEP' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:PLAN_STEP:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'TASK_LEASE','FROZEN',CONCAT('legacy:TASK_LEASE:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','TASK_LEASE','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_task_lease r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'TASK_LEASE' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:TASK_LEASE:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'APPROVAL','FROZEN',CONCAT('legacy:APPROVAL:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','APPROVAL','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_approval r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'APPROVAL' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:APPROVAL:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'TOOL_CALL','FROZEN',CONCAT('legacy:TOOL_CALL:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','TOOL_CALL','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_tool_call r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'TOOL_CALL' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:TOOL_CALL:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'TOOL_RECEIPT','FROZEN',CONCAT('legacy:TOOL_RECEIPT:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','TOOL_RECEIPT','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_tool_receipt r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'TOOL_RECEIPT' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:TOOL_RECEIPT:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'POLICY_VERSION','FROZEN',CONCAT('legacy:POLICY_VERSION:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','POLICY_VERSION','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_policy_version r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'POLICY_VERSION' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:POLICY_VERSION:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'MEMORY','FROZEN',CONCAT('legacy:MEMORY:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','MEMORY','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_memory r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'MEMORY' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:MEMORY:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT m.owner_id,r.id,'MEMORY','FROZEN',CONCAT('legacy:MEMORY:',r.id,':user:',m.owner_id),r.content_hash,JSON_OBJECT('resourceType','MEMORY','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(r.workspace_id AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_SHARED_READ'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_memory r JOIN pc_workspace_member m ON m.workspace_id=r.workspace_id AND m.state='ACTIVE' WHERE NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:MEMORY:',r.id,':user:',m.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'MEMORY_VERSION','FROZEN',CONCAT('legacy:MEMORY_VERSION:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','MEMORY_VERSION','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_memory_version r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'MEMORY_VERSION' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:MEMORY_VERSION:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT m.owner_id,r.id,'MEMORY_VERSION','FROZEN',CONCAT('legacy:MEMORY_VERSION:',r.id,':user:',m.owner_id),r.content_hash,JSON_OBJECT('resourceType','MEMORY_VERSION','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(r.workspace_id AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_SHARED_READ'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_memory_version r JOIN pc_workspace_member m ON m.workspace_id=r.workspace_id AND m.state='ACTIVE' WHERE NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:MEMORY_VERSION:',r.id,':user:',m.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'CONTEXT_SUMMARY','FROZEN',CONCAT('legacy:CONTEXT_SUMMARY:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','CONTEXT_SUMMARY','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_context_summary r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'CONTEXT_SUMMARY' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:CONTEXT_SUMMARY:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'INDEX_MANIFEST','FROZEN',CONCAT('legacy:INDEX_MANIFEST:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','INDEX_MANIFEST','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_index_manifest r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'INDEX_MANIFEST' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:INDEX_MANIFEST:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT m.owner_id,r.id,'INDEX_MANIFEST','FROZEN',CONCAT('legacy:INDEX_MANIFEST:',r.id,':user:',m.owner_id),r.content_hash,JSON_OBJECT('resourceType','INDEX_MANIFEST','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(r.workspace_id AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_SHARED_READ'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_index_manifest r JOIN pc_workspace_member m ON m.workspace_id=r.workspace_id AND m.state='ACTIVE' WHERE NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:INDEX_MANIFEST:',r.id,':user:',m.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'SNAPSHOT','FROZEN',CONCAT('legacy:SNAPSHOT:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','SNAPSHOT','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_snapshot r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'SNAPSHOT' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:SNAPSHOT:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT m.owner_id,r.id,'SNAPSHOT','FROZEN',CONCAT('legacy:SNAPSHOT:',r.id,':user:',m.owner_id),r.content_hash,JSON_OBJECT('resourceType','SNAPSHOT','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(r.workspace_id AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_SHARED_READ'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_snapshot r JOIN pc_workspace_member m ON m.workspace_id=r.workspace_id AND m.state='ACTIVE' WHERE NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:SNAPSHOT:',r.id,':user:',m.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'ARTIFACT','FROZEN',CONCAT('legacy:ARTIFACT:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','ARTIFACT','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_artifact r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'ARTIFACT' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:ARTIFACT:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'TERMINAL_SESSION','FROZEN',CONCAT('legacy:TERMINAL_SESSION:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','TERMINAL_SESSION','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_terminal_session r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'TERMINAL_SESSION' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:TERMINAL_SESSION:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'PROVIDER_CONFIG','FROZEN',CONCAT('legacy:PROVIDER_CONFIG:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','PROVIDER_CONFIG','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_provider_config r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'PROVIDER_CONFIG' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:PROVIDER_CONFIG:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'MODEL_CONFIG','FROZEN',CONCAT('legacy:MODEL_CONFIG:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','MODEL_CONFIG','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_model_config r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'MODEL_CONFIG' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:MODEL_CONFIG:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'MCP_CONFIG','FROZEN',CONCAT('legacy:MCP_CONFIG:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','MCP_CONFIG','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_mcp_config r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'MCP_CONFIG' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:MCP_CONFIG:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'SKILL_CONFIG','FROZEN',CONCAT('legacy:SKILL_CONFIG:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','SKILL_CONFIG','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_skill_config r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'SKILL_CONFIG' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:SKILL_CONFIG:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'BROWSER_CONFIG','FROZEN',CONCAT('legacy:BROWSER_CONFIG:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','BROWSER_CONFIG','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_browser_config r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'BROWSER_CONFIG' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:BROWSER_CONFIG:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'WECHAT_BINDING','FROZEN',CONCAT('legacy:WECHAT_BINDING:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','WECHAT_BINDING','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_wechat_binding r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'WECHAT_BINDING' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:WECHAT_BINDING:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'EVENT','FROZEN',CONCAT('legacy:EVENT:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','EVENT','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_event r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'EVENT' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:EVENT:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'DISPATCH_OUTBOX','FROZEN',CONCAT('legacy:DISPATCH_OUTBOX:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','DISPATCH_OUTBOX','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_dispatch_outbox r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'DISPATCH_OUTBOX' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:DISPATCH_OUTBOX:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'SECURITY_AUDIT','FROZEN',CONCAT('legacy:SECURITY_AUDIT:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','SECURITY_AUDIT','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_security_audit r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'SECURITY_AUDIT' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:SECURITY_AUDIT:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'EVALUATION_RUN','FROZEN',CONCAT('legacy:EVALUATION_RUN:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','EVALUATION_RUN','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_evaluation_run r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'EVALUATION_RUN' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:EVALUATION_RUN:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'HARNESS_RUN','FROZEN',CONCAT('legacy:HARNESS_RUN:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','HARNESS_RUN','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_harness_run r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'HARNESS_RUN' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:HARNESS_RUN:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'IDEMPOTENCY','FROZEN',CONCAT('legacy:IDEMPOTENCY:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','IDEMPOTENCY','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_idempotency r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'IDEMPOTENCY' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:IDEMPOTENCY:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'IMPORT_BATCH','FROZEN',CONCAT('legacy:IMPORT_BATCH:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','IMPORT_BATCH','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_import_batch r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'IMPORT_BATCH' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:IMPORT_BATCH:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT r.owner_id,r.id,'PROMPT_TEMPLATE','FROZEN',CONCAT('legacy:PROMPT_TEMPLATE:',r.id,':user:',r.owner_id),r.content_hash,JSON_OBJECT('resourceType','PROMPT_TEMPLATE','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(COALESCE(r.workspace_id,r.id) AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_OWNER'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_prompt_template r WHERE r.owner_id IS NOT NULL AND (r.workspace_id IS NOT NULL OR 'PROMPT_TEMPLATE' IN ('WORKSPACE','WORKSPACE_MEMBER')) AND NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:PROMPT_TEMPLATE:',r.id,':user:',r.owner_id));

INSERT INTO pc_legacy_access_snapshot(owner_id,parent_id,kind,state,unique_key,content_hash,payload,created_at,updated_at) SELECT m.owner_id,r.id,'PROMPT_TEMPLATE','FROZEN',CONCAT('legacy:PROMPT_TEMPLATE:',r.id,':user:',m.owner_id),r.content_hash,JSON_OBJECT('resourceType','PROMPT_TEMPLATE','resourceId',CAST(r.id AS CHAR),'legacyWorkspaceId',CAST(r.workspace_id AS CHAR),'originalState',r.state,'originalVersion',r.version,'access','READ_ONLY','source','FROZEN_SHARED_READ'),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM pc_prompt_template r JOIN pc_workspace_member m ON m.workspace_id=r.workspace_id AND m.state='ACTIVE' WHERE NOT EXISTS(SELECT 1 FROM pc_legacy_access_snapshot s WHERE s.unique_key=CONCAT('legacy:PROMPT_TEMPLATE:',r.id,':user:',m.owner_id));

-- 不复制、不重新归属任何历史供应商凭据；旧对象只读，旧凭证一律撤销。

UPDATE pc_runner_credential SET state='REVOKED',version=version+1 WHERE workspace_id IS NOT NULL AND state!='REVOKED';

UPDATE pc_runner SET state='REVOKED',version=version+1 WHERE workspace_id IS NOT NULL AND state!='REVOKED';

UPDATE pc_workspace SET state='FROZEN',version=version+1 WHERE (workspace_id IS NOT NULL OR 1=1) AND state!='FROZEN';

UPDATE pc_workspace_member SET state='FROZEN',version=version+1 WHERE (workspace_id IS NOT NULL) AND state!='FROZEN';

UPDATE pc_session SET state='FROZEN',version=version+1 WHERE (workspace_id IS NOT NULL) AND state!='FROZEN';

UPDATE pc_provider_config SET state='FROZEN',version=version+1 WHERE (workspace_id IS NOT NULL) AND state!='FROZEN';

UPDATE pc_model_config SET state='FROZEN',version=version+1 WHERE (workspace_id IS NOT NULL) AND state!='FROZEN';

UPDATE pc_mcp_config SET state='FROZEN',version=version+1 WHERE (workspace_id IS NOT NULL) AND state!='FROZEN';

UPDATE pc_skill_config SET state='FROZEN',version=version+1 WHERE (workspace_id IS NOT NULL) AND state!='FROZEN';

UPDATE pc_browser_config SET state='FROZEN',version=version+1 WHERE (workspace_id IS NOT NULL) AND state!='FROZEN';

UPDATE pc_wechat_binding SET state='FROZEN',version=version+1 WHERE (workspace_id IS NOT NULL) AND state!='FROZEN';

UPDATE pc_memory SET state='FROZEN',version=version+1 WHERE (workspace_id IS NOT NULL) AND state!='FROZEN';

UPDATE pc_prompt_template SET state='FROZEN',version=version+1 WHERE (workspace_id IS NOT NULL) AND state!='FROZEN';

UPDATE pc_policy_version SET state='FROZEN',version=version+1 WHERE (workspace_id IS NOT NULL) AND state!='FROZEN';

-- 仍使用既有若依授权菜单，隐藏技术详情入口，不改navType和既有角色/按钮分配。

SET @pc_root=(SELECT menu_id FROM sys_menu WHERE path='paicli' AND parent_id=0 ORDER BY menu_id LIMIT 1);

UPDATE sys_menu SET menu_name='智能编码',order_num=1 WHERE parent_id=@pc_root AND path='workbench' AND menu_type='C';

UPDATE sys_menu SET menu_name='我的文件',order_num=2,component='paicli/knowledge/index' WHERE parent_id=@pc_root AND path='knowledge' AND menu_type='C';

UPDATE sys_menu SET menu_name='模型与设置',order_num=3 WHERE parent_id=@pc_root AND path='connections' AND menu_type='C';

UPDATE sys_menu SET visible='1' WHERE parent_id=@pc_root AND path IN ('tasks','audit') AND menu_type='C';

