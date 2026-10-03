-- PaiCLI V4：按功能显式业务字段、查询索引和提示词覆盖。
-- 兼容V1–V3；payload仍为唯一字段来源，业务生成列不可独立更新，避免第二份事实。
-- MySQL8/InnoDB；运维显式迁移，不连接、创建或克隆数据库。原SQLite仅派生codebase缓存留Runner。
-- 原SQLite runtime_threads/events/tasks及JSON配置/记忆/账本的字段映射见functional-schema-v4.json。

CREATE TABLE pc_prompt_template (
  id BIGINT NOT NULL AUTO_INCREMENT, workspace_id BIGINT NULL, owner_id BIGINT NULL,
  parent_id BIGINT NULL, session_id BIGINT NULL, runner_id BIGINT NULL,
  kind VARCHAR(64) NOT NULL, state VARCHAR(40) NOT NULL,
  unique_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL, payload JSON NOT NULL,
  version BIGINT NOT NULL DEFAULT 1, sequence_number BIGINT NOT NULL DEFAULT 0,
  fencing_token BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL, expires_at TIMESTAMP(3) NULL,
  PRIMARY KEY(id), UNIQUE KEY uq_pc_prompt_template_key(unique_key),
  KEY ix_pc_prompt_template_scope(workspace_id,owner_id,state,id),
  CONSTRAINT ck_pc_prompt_owner CHECK(owner_id IS NOT NULL),
  CONSTRAINT ck_pc_prompt_versions CHECK(version >= 1 AND sequence_number >= 0 AND fencing_token >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- pc_workspace：name, rootFingerprint, rootPath, policyVersion, searchConfig.provider, embeddingConfig.provider, embeddingConfig.model, usageDay, dailyTokenUsed
ALTER TABLE pc_workspace
  ADD COLUMN workspace_name VARCHAR(128) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.name')), 'null')) STORED,
  ADD COLUMN root_fingerprint CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.rootFingerprint')), 'null')) STORED,
  ADD COLUMN root_path VARCHAR(4096) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.rootPath')), 'null')) STORED,
  ADD COLUMN policy_version BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.policyVersion')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.policyVersion')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN search_provider VARCHAR(32) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.searchConfig.provider')), 'null')) STORED,
  ADD COLUMN embedding_provider VARCHAR(32) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.embeddingConfig.provider')), 'null')) STORED,
  ADD COLUMN embedding_model VARCHAR(256) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.embeddingConfig.model')), 'null')) STORED,
  ADD COLUMN usage_day VARCHAR(10) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.usageDay')), 'null')) STORED,
  ADD COLUMN daily_token_used BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.dailyTokenUsed')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.dailyTokenUsed')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD INDEX ix_pc_workspace_workspace_name (workspace_id, workspace_name, state, id);

-- pc_workspace_member：role, userId
ALTER TABLE pc_workspace_member
  ADD COLUMN member_role VARCHAR(16) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.role')), 'null')) STORED,
  ADD COLUMN member_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.userId')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.userId')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD INDEX ix_pc_workspace_member_member_role (workspace_id, member_role, state, id);

-- pc_runner：name, platform, rootFingerprint, administrationVersion, capacity, sandboxVerified, distributionVersion
ALTER TABLE pc_runner
  ADD COLUMN device_name VARCHAR(128) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.name')), 'null')) STORED,
  ADD COLUMN platform VARCHAR(128) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.platform')), 'null')) STORED,
  ADD COLUMN root_fingerprint CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.rootFingerprint')), 'null')) STORED,
  ADD COLUMN administration_version BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.administrationVersion')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.administrationVersion')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN capacity INT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.capacity')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.capacity')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN sandbox_verified TINYINT GENERATED ALWAYS AS (CASE LOWER(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.sandboxVerified')), 'null')) WHEN 'true' THEN 1 WHEN '1' THEN 1 WHEN 'false' THEN 0 WHEN '0' THEN 0 ELSE NULL END) STORED,
  ADD COLUMN distribution_version VARCHAR(128) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.distributionVersion')), 'null')) STORED,
  ADD INDEX ix_pc_runner_administration_version (workspace_id, administration_version, state, id);

-- pc_runner_credential：kind是凭证类型、unique_key是不可逆token哈希；不另存原值。
ALTER TABLE pc_runner_credential
  ADD COLUMN credential_type VARCHAR(32) GENERATED ALWAYS AS (kind) STORED,
  ADD COLUMN token_hash CHAR(64) GENERATED ALWAYS AS (unique_key) STORED,
  ADD COLUMN administration_version BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.administrationVersion')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.administrationVersion')), 'null') AS SIGNED) ELSE NULL END) STORED;

-- pc_session：title, mode, approvalMode, modelId, contextStartSequence, inputHistoryClearedAt
ALTER TABLE pc_session
  ADD COLUMN title VARCHAR(256) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.title')), 'null')) STORED,
  ADD COLUMN execution_mode VARCHAR(16) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.mode')), 'null')) STORED,
  ADD COLUMN approval_mode VARCHAR(16) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.approvalMode')), 'null')) STORED,
  ADD COLUMN model_record_id BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.modelId')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.modelId')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN context_start_sequence BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.contextStartSequence')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.contextStartSequence')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN input_history_cleared_at VARCHAR(40) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.inputHistoryClearedAt')), 'null')) STORED,
  ADD INDEX ix_pc_session_model_record_id (workspace_id, model_record_id, state, id),
  ADD INDEX ix_pc_session_execution_mode (workspace_id, execution_mode, state, id);

-- pc_turn：text, mode, modelId
ALTER TABLE pc_turn
  ADD COLUMN input_text LONGTEXT GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.text')), 'null')) STORED,
  ADD COLUMN execution_mode VARCHAR(16) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.mode')), 'null')) STORED,
  ADD COLUMN model_record_id BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.modelId')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.modelId')), 'null') AS SIGNED) ELSE NULL END) STORED;

-- pc_message：role, content, toolCallId, _ledger.event, _ledger.actor, _ledger.source, _ledger.timestamp
ALTER TABLE pc_message
  ADD COLUMN message_role VARCHAR(16) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.role')), 'null')) STORED,
  ADD COLUMN message_content LONGTEXT GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.content')), 'null')) STORED,
  ADD COLUMN tool_call_id VARCHAR(128) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.toolCallId')), 'null')) STORED,
  ADD COLUMN ledger_event VARCHAR(128) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$._ledger.event')), 'null')) STORED,
  ADD COLUMN ledger_actor VARCHAR(128) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$._ledger.actor')), 'null')) STORED,
  ADD COLUMN ledger_source VARCHAR(256) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$._ledger.source')), 'null')) STORED,
  ADD COLUMN ledger_timestamp_ms BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$._ledger.timestamp')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$._ledger.timestamp')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD INDEX ix_pc_message_message_role (workspace_id, message_role, state, id),
  ADD INDEX ix_pc_message_tool_call_id (workspace_id, tool_call_id, state, id);

-- pc_task：title, mode, operation, input, result, error, startedAt, finishedAt, durationMs, source
ALTER TABLE pc_task
  ADD COLUMN task_title VARCHAR(256) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.title')), 'null')) STORED,
  ADD COLUMN execution_mode VARCHAR(16) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.mode')), 'null')) STORED,
  ADD COLUMN operation_name VARCHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.operation')), 'null')) STORED,
  ADD COLUMN prompt_text LONGTEXT GENERATED ALWAYS AS (COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.input')), 'null'),NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.prompt')), 'null'))) STORED,
  ADD COLUMN result_text LONGTEXT GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.result')), 'null')) STORED,
  ADD COLUMN error_text LONGTEXT GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.error')), 'null')) STORED,
  ADD COLUMN started_at_iso VARCHAR(40) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.startedAt')), 'null')) STORED,
  ADD COLUMN finished_at_iso VARCHAR(40) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.finishedAt')), 'null')) STORED,
  ADD COLUMN duration_ms BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.durationMs')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.durationMs')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN task_source VARCHAR(32) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.source')), 'null')) STORED,
  ADD INDEX ix_pc_task_operation_name (workspace_id, operation_name, state, id),
  ADD INDEX ix_pc_task_execution_mode (workspace_id, execution_mode, state, id),
  ADD CONSTRAINT ck_pc_task_duration CHECK(duration_ms IS NULL OR duration_ms >= 0);

-- pc_plan：goal, mode, definitionVersion, approvedVersion, replanCount, steps
ALTER TABLE pc_plan
  ADD COLUMN plan_goal LONGTEXT GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.goal')), 'null')) STORED,
  ADD COLUMN execution_mode VARCHAR(16) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.mode')), 'null')) STORED,
  ADD COLUMN definition_version BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.definitionVersion')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.definitionVersion')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN approved_version BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.approvedVersion')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.approvedVersion')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN replan_count INT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.replanCount')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.replanCount')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN plan_steps JSON GENERATED ALWAYS AS (JSON_EXTRACT(payload, '$.steps')) STORED,
  ADD INDEX ix_pc_plan_definition_version (workspace_id, definition_version, state, id);

-- pc_plan_step：id, description, status, reviewStatus, result, dependencies
ALTER TABLE pc_plan_step
  ADD COLUMN step_id VARCHAR(128) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.id')), 'null')) STORED,
  ADD COLUMN step_description LONGTEXT GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.description')), 'null')) STORED,
  ADD COLUMN step_status VARCHAR(40) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.status')), 'null')) STORED,
  ADD COLUMN review_status VARCHAR(40) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reviewStatus')), 'null')) STORED,
  ADD COLUMN step_result LONGTEXT GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.result')), 'null')) STORED,
  ADD COLUMN step_dependencies JSON GENERATED ALWAYS AS (JSON_EXTRACT(payload, '$.dependencies')) STORED,
  ADD INDEX ix_pc_plan_step_step_id (workspace_id, step_id, state, id);

-- pc_task_lease：taskId, usedTokens, outputBytes, contextEndSequence
ALTER TABLE pc_task_lease
  ADD COLUMN task_record_id BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.taskId')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.taskId')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN used_tokens BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.usedTokens')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.usedTokens')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN output_bytes BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.outputBytes')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.outputBytes')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN context_end_sequence BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.contextEndSequence')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.contextEndSequence')), 'null') AS SIGNED) ELSE NULL END) STORED;

-- pc_approval：state是审批决定；decisionAt是中心核定时间。
ALTER TABLE pc_approval
  ADD COLUMN tool_call_id VARCHAR(128) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.toolCallId')), 'null')) STORED,
  ADD COLUMN tool_name VARCHAR(128) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.toolName')), 'null')) STORED,
  ADD COLUMN args_hash CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.argsHash')), 'null')) STORED,
  ADD COLUMN resource_hash CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.resourceHash')), 'null')) STORED,
  ADD COLUMN approval_decision VARCHAR(40) GENERATED ALWAYS AS (state) STORED,
  ADD COLUMN approval_feedback LONGTEXT GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.feedback')), 'null')) STORED,
  ADD COLUMN decided_at_iso VARCHAR(40) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.decisionAt')), 'null')) STORED,
  ADD INDEX ix_pc_approval_tool_call_id (workspace_id, tool_call_id, state, id);

-- pc_tool_call：toolCallId, modelToolCallIdHash, toolName, argsHash, resourceHash, permitHash, readOnly, arguments
ALTER TABLE pc_tool_call
  ADD COLUMN tool_call_id VARCHAR(128) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.toolCallId')), 'null')) STORED,
  ADD COLUMN model_tool_call_id_hash CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.modelToolCallIdHash')), 'null')) STORED,
  ADD COLUMN tool_name VARCHAR(128) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.toolName')), 'null')) STORED,
  ADD COLUMN args_hash CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.argsHash')), 'null')) STORED,
  ADD COLUMN resource_hash CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.resourceHash')), 'null')) STORED,
  ADD COLUMN permit_hash CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.permitHash')), 'null')) STORED,
  ADD COLUMN read_only TINYINT GENERATED ALWAYS AS (CASE LOWER(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.readOnly')), 'null')) WHEN 'true' THEN 1 WHEN '1' THEN 1 WHEN 'false' THEN 0 WHEN '0' THEN 0 ELSE NULL END) STORED,
  ADD COLUMN tool_arguments JSON GENERATED ALWAYS AS (JSON_EXTRACT(payload, '$.arguments')) STORED,
  ADD INDEX ix_pc_tool_call_tool_call_id (workspace_id, tool_call_id, state, id),
  ADD INDEX ix_pc_tool_call_model_tool_call_id_hash (workspace_id, model_tool_call_id_hash, state, id);

-- pc_tool_receipt：toolCallId, modelToolCallIdHash, leaseId, beforeHash, afterHash, result
ALTER TABLE pc_tool_receipt
  ADD COLUMN tool_call_id VARCHAR(128) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.toolCallId')), 'null')) STORED,
  ADD COLUMN model_tool_call_id_hash CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.modelToolCallIdHash')), 'null')) STORED,
  ADD COLUMN original_lease_id BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.leaseId')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.leaseId')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN before_hash CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.beforeHash')), 'null')) STORED,
  ADD COLUMN after_hash CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.afterHash')), 'null')) STORED,
  ADD COLUMN receipt_result JSON GENERATED ALWAYS AS (JSON_EXTRACT(payload, '$.result')) STORED,
  ADD INDEX ix_pc_tool_receipt_tool_call_id (workspace_id, tool_call_id, state, id);

-- pc_policy_version：taskTokenBudget, dailyTokenBudget, maxReadTools, maxWriteTasks, retentionDays
ALTER TABLE pc_policy_version
  ADD COLUMN task_token_budget BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.taskTokenBudget')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.taskTokenBudget')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN daily_token_budget BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.dailyTokenBudget')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.dailyTokenBudget')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN max_read_tools INT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.maxReadTools')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.maxReadTools')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN max_write_tasks INT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.maxWriteTasks')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.maxWriteTasks')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN retention_days INT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.retentionDays')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.retentionDays')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD CONSTRAINT ck_pc_policy_parallel CHECK((max_read_tools IS NULL OR max_read_tools BETWEEN 1 AND 4) AND (max_write_tasks IS NULL OR max_write_tasks = 1)),
  ADD CONSTRAINT ck_pc_policy_retention CHECK(retention_days IS NULL OR retention_days BETWEEN 1 AND 3650);

-- pc_memory：content, type, scope, source, timestamp, lastVerifiedAt, verifiedAt, tokenCount, metadata
ALTER TABLE pc_memory
  ADD COLUMN memory_content LONGTEXT GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.content')), 'null')) STORED,
  ADD COLUMN memory_type VARCHAR(32) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.type')), 'null')) STORED,
  ADD COLUMN memory_scope VARCHAR(16) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.scope')), 'null')) STORED,
  ADD COLUMN memory_source VARCHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.source')), 'null')) STORED,
  ADD COLUMN written_at_iso VARCHAR(40) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.timestamp')), 'null')) STORED,
  ADD COLUMN last_verified_at_iso VARCHAR(40) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.lastVerifiedAt')), 'null')) STORED,
  ADD COLUMN verified_at_iso VARCHAR(40) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.verifiedAt')), 'null')) STORED,
  ADD COLUMN token_count BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.tokenCount')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.tokenCount')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN memory_metadata JSON GENERATED ALWAYS AS (JSON_EXTRACT(payload, '$.metadata')) STORED,
  ADD INDEX ix_pc_memory_memory_scope (workspace_id, memory_scope, state, id),
  ADD INDEX ix_pc_memory_memory_type (workspace_id, memory_type, state, id);

-- pc_memory_version：content, type, scope, lastVerifiedAt, metadata
ALTER TABLE pc_memory_version
  ADD COLUMN memory_content LONGTEXT GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.content')), 'null')) STORED,
  ADD COLUMN memory_type VARCHAR(32) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.type')), 'null')) STORED,
  ADD COLUMN memory_scope VARCHAR(16) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.scope')), 'null')) STORED,
  ADD COLUMN last_verified_at_iso VARCHAR(40) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.lastVerifiedAt')), 'null')) STORED,
  ADD COLUMN memory_metadata JSON GENERATED ALWAYS AS (JSON_EXTRACT(payload, '$.metadata')) STORED;

-- pc_context_summary：startSequence, endSequence, summary, messages
ALTER TABLE pc_context_summary
  ADD COLUMN start_sequence BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.startSequence')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.startSequence')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN end_sequence BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.endSequence')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.endSequence')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN summary_text LONGTEXT GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.summary')), 'null')) STORED,
  ADD COLUMN derived_messages JSON GENERATED ALWAYS AS (JSON_EXTRACT(payload, '$.messages')) STORED;

-- pc_index_manifest：manifestHash, index.embeddingModel, index.fileCount, index.chunkCount, index.relationCount, query, mode, indexVersion
ALTER TABLE pc_index_manifest
  ADD COLUMN manifest_hash CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.manifestHash')), 'null')) STORED,
  ADD COLUMN embedding_model VARCHAR(256) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.index.embeddingModel')), 'null')) STORED,
  ADD COLUMN file_count BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.index.fileCount')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.index.fileCount')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN chunk_count BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.index.chunkCount')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.index.chunkCount')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN relation_count BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.index.relationCount')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.index.relationCount')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN query_text VARCHAR(2048) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.query')), 'null')) STORED,
  ADD COLUMN query_mode VARCHAR(16) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.mode')), 'null')) STORED,
  ADD COLUMN index_version BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.indexVersion')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.indexVersion')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD INDEX ix_pc_index_manifest_index_version (workspace_id, index_version, state, id),
  ADD INDEX ix_pc_index_manifest_query_mode (workspace_id, query_mode, state, id);

-- pc_snapshot：commitId, phase, turnId, summary, manifestHash
ALTER TABLE pc_snapshot
  ADD COLUMN commit_id VARCHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.commitId')), 'null')) STORED,
  ADD COLUMN snapshot_phase VARCHAR(32) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.phase')), 'null')) STORED,
  ADD COLUMN legacy_turn_id VARCHAR(128) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.turnId')), 'null')) STORED,
  ADD COLUMN snapshot_summary LONGTEXT GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.summary')), 'null')) STORED,
  ADD COLUMN manifest_hash CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.manifestHash')), 'null')) STORED,
  ADD INDEX ix_pc_snapshot_commit_id (workspace_id, commit_id, state, id);

-- pc_artifact：purpose, fileName, mimeType, sha256, size, lines
ALTER TABLE pc_artifact
  ADD COLUMN artifact_purpose VARCHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.purpose')), 'null')) STORED,
  ADD COLUMN file_name VARCHAR(512) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.fileName')), 'null')) STORED,
  ADD COLUMN mime_type VARCHAR(128) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.mimeType')), 'null')) STORED,
  ADD COLUMN artifact_sha256 CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.sha256')), 'null')) STORED,
  ADD COLUMN byte_size BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.size')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.size')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN line_count BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.lines')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.lines')), 'null') AS SIGNED) ELSE NULL END) STORED;

-- pc_provider_config：provider, baseUrl, secretRef, model, loraId, temperature, maxTokens, configurationVersion
ALTER TABLE pc_provider_config
  ADD COLUMN provider_name VARCHAR(32) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.provider')), 'null')) STORED,
  ADD COLUMN base_url VARCHAR(4096) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.baseUrl')), 'null')) STORED,
  ADD COLUMN secret_ref VARCHAR(255) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.secretRef')), 'null')) STORED,
  ADD COLUMN default_model VARCHAR(256) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.model')), 'null')) STORED,
  ADD COLUMN lora_id VARCHAR(256) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.loraId')), 'null')) STORED,
  ADD COLUMN temperature DECIMAL(10,6) GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.temperature')), 'null') REGEXP '^[0-9]+([.][0-9]+)?$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.temperature')), 'null') AS DECIMAL(10,6)) ELSE NULL END) STORED,
  ADD COLUMN max_tokens BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.maxTokens')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.maxTokens')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN definition_version BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.configurationVersion')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.configurationVersion')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD INDEX ix_pc_provider_config_provider_name (workspace_id, provider_name, state, id);

-- pc_model_config：providerId, modelId, template, contextWindow, maxOutputTokens, supportsImages, thinking, reasoningEffort, reasoningHistory, dsml, enabled
ALTER TABLE pc_model_config
  ADD COLUMN provider_record_id BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.providerId')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.providerId')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN model_id VARCHAR(256) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.modelId')), 'null')) STORED,
  ADD COLUMN template_model VARCHAR(256) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.template')), 'null')) STORED,
  ADD COLUMN context_window BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.contextWindow')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.contextWindow')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN max_output_tokens BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.maxOutputTokens')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.maxOutputTokens')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN supports_images TINYINT GENERATED ALWAYS AS (CASE LOWER(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.supportsImages')), 'null')) WHEN 'true' THEN 1 WHEN '1' THEN 1 WHEN 'false' THEN 0 WHEN '0' THEN 0 ELSE NULL END) STORED,
  ADD COLUMN thinking_mode VARCHAR(16) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.thinking')), 'null')) STORED,
  ADD COLUMN reasoning_effort VARCHAR(16) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reasoningEffort')), 'null')) STORED,
  ADD COLUMN reasoning_history TINYINT GENERATED ALWAYS AS (CASE LOWER(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reasoningHistory')), 'null')) WHEN 'true' THEN 1 WHEN '1' THEN 1 WHEN 'false' THEN 0 WHEN '0' THEN 0 ELSE NULL END) STORED,
  ADD COLUMN dsml_enabled TINYINT GENERATED ALWAYS AS (CASE LOWER(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.dsml')), 'null')) WHEN 'true' THEN 1 WHEN '1' THEN 1 WHEN 'false' THEN 0 WHEN '0' THEN 0 ELSE NULL END) STORED,
  ADD COLUMN model_enabled TINYINT GENERATED ALWAYS AS (CASE LOWER(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.enabled')), 'null')) WHEN 'true' THEN 1 WHEN '1' THEN 1 WHEN 'false' THEN 0 WHEN '0' THEN 0 ELSE NULL END) STORED,
  ADD INDEX ix_pc_model_config_provider_record_id (workspace_id, provider_record_id, state, id),
  ADD INDEX ix_pc_model_config_model_id (workspace_id, model_id, state, id),
  ADD CONSTRAINT ck_pc_model_context CHECK(context_window IS NULL OR context_window BETWEEN 1024 AND 10000000),
  ADD CONSTRAINT ck_pc_model_output CHECK(max_output_tokens IS NULL OR (max_output_tokens BETWEEN 1 AND 1000000 AND (context_window IS NULL OR max_output_tokens <= context_window)));

-- pc_mcp_config：name, transport, url, command, configHash, trustedHash, configurationVersion, enabled, envSecretRefs, headerSecretRefs
ALTER TABLE pc_mcp_config
  ADD COLUMN server_name VARCHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.name')), 'null')) STORED,
  ADD COLUMN transport_type VARCHAR(16) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.transport')), 'null')) STORED,
  ADD COLUMN endpoint_url VARCHAR(4096) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.url')), 'null')) STORED,
  ADD COLUMN command_name VARCHAR(4096) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.command')), 'null')) STORED,
  ADD COLUMN configuration_hash CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.configHash')), 'null')) STORED,
  ADD COLUMN trusted_hash CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.trustedHash')), 'null')) STORED,
  ADD COLUMN definition_version BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.configurationVersion')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.configurationVersion')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN server_enabled TINYINT GENERATED ALWAYS AS (CASE LOWER(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.enabled')), 'null')) WHEN 'true' THEN 1 WHEN '1' THEN 1 WHEN 'false' THEN 0 WHEN '0' THEN 0 ELSE NULL END) STORED,
  ADD COLUMN env_secret_refs JSON GENERATED ALWAYS AS (JSON_EXTRACT(payload, '$.envSecretRefs')) STORED,
  ADD COLUMN header_secret_refs JSON GENERATED ALWAYS AS (JSON_EXTRACT(payload, '$.headerSecretRefs')) STORED,
  ADD INDEX ix_pc_mcp_config_server_name (workspace_id, server_name, state, id),
  ADD INDEX ix_pc_mcp_config_transport_type (workspace_id, transport_type, state, id);

-- pc_skill_config：name, scope, source, sha256, manifestApproved, enabled, content
ALTER TABLE pc_skill_config
  ADD COLUMN skill_name VARCHAR(128) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.name')), 'null')) STORED,
  ADD COLUMN skill_scope VARCHAR(32) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.scope')), 'null')) STORED,
  ADD COLUMN skill_source VARCHAR(32) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.source')), 'null')) STORED,
  ADD COLUMN skill_sha256 CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.sha256')), 'null')) STORED,
  ADD COLUMN manifest_approved TINYINT GENERATED ALWAYS AS (CASE LOWER(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.manifestApproved')), 'null')) WHEN 'true' THEN 1 WHEN '1' THEN 1 WHEN 'false' THEN 0 WHEN '0' THEN 0 ELSE NULL END) STORED,
  ADD COLUMN skill_enabled TINYINT GENERATED ALWAYS AS (CASE LOWER(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.enabled')), 'null')) WHEN 'true' THEN 1 WHEN '1' THEN 1 WHEN 'false' THEN 0 WHEN '0' THEN 0 ELSE NULL END) STORED,
  ADD COLUMN skill_content LONGTEXT GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.content')), 'null')) STORED,
  ADD INDEX ix_pc_skill_config_skill_name (workspace_id, skill_name, state, id),
  ADD INDEX ix_pc_skill_config_skill_scope (workspace_id, skill_scope, state, id);

-- pc_browser_config：mode, endpoint, connected, mcpConfigId
ALTER TABLE pc_browser_config
  ADD COLUMN browser_mode VARCHAR(32) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.mode')), 'null')) STORED,
  ADD COLUMN endpoint_url VARCHAR(4096) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.endpoint')), 'null')) STORED,
  ADD COLUMN browser_connected TINYINT GENERATED ALWAYS AS (CASE LOWER(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.connected')), 'null')) WHEN 'true' THEN 1 WHEN '1' THEN 1 WHEN 'false' THEN 0 WHEN '0' THEN 0 ELSE NULL END) STORED,
  ADD COLUMN mcp_config_id BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.mcpConfigId')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.mcpConfigId')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD INDEX ix_pc_browser_config_mcp_config_id (workspace_id, mcp_config_id, state, id);

-- pc_wechat_binding：accountId, boundUserId, senderHash, dailyBudget, dailyUsed, channelVersion, syncBuf
ALTER TABLE pc_wechat_binding
  ADD COLUMN account_id VARCHAR(256) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.accountId')), 'null')) STORED,
  ADD COLUMN bound_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.boundUserId')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.boundUserId')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN sender_hash CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.senderHash')), 'null')) STORED,
  ADD COLUMN daily_budget BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.dailyBudget')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.dailyBudget')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN daily_used BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.dailyUsed')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.dailyUsed')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN channel_version BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.channelVersion')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.channelVersion')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN sync_cursor LONGTEXT GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.syncBuf')), 'null')) STORED,
  ADD INDEX ix_pc_wechat_binding_sender_hash (workspace_id, sender_hash, state, id);

-- pc_event：type, payload.taskId
ALTER TABLE pc_event
  ADD COLUMN event_type VARCHAR(128) GENERATED ALWAYS AS (kind) STORED,
  ADD COLUMN event_task_id BIGINT GENERATED ALWAYS AS (parent_id) STORED;

-- pc_dispatch_outbox：taskId, attempts, leaseId
ALTER TABLE pc_dispatch_outbox
  ADD COLUMN task_record_id BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.taskId')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.taskId')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN attempts BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.attempts')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.attempts')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN delivered_lease_id BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.leaseId')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.leaseId')), 'null') AS SIGNED) ELSE NULL END) STORED;

-- pc_security_audit：action, decision, details.toolName, details.toolCallId, details.taskId
ALTER TABLE pc_security_audit
  ADD COLUMN audit_action VARCHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.action')), 'null')) STORED,
  ADD COLUMN audit_decision VARCHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.decision')), 'null')) STORED,
  ADD COLUMN tool_name VARCHAR(128) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.details.toolName')), 'null')) STORED,
  ADD COLUMN tool_call_id VARCHAR(128) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.details.toolCallId')), 'null')) STORED,
  ADD COLUMN audit_task_id BIGINT GENERATED ALWAYS AS (parent_id) STORED,
  ADD INDEX ix_pc_security_audit_audit_action (workspace_id, audit_action, state, id),
  ADD INDEX ix_pc_security_audit_audit_decision (workspace_id, audit_decision, state, id);

-- pc_evaluation_run：reportArtifactId, formalScores, publishable, paused, sourceDigest
ALTER TABLE pc_evaluation_run
  ADD COLUMN report_artifact_id BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reportArtifactId')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reportArtifactId')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN formal_scores JSON GENERATED ALWAYS AS (JSON_EXTRACT(payload, '$.formalScores')) STORED,
  ADD COLUMN publishable TINYINT GENERATED ALWAYS AS (CASE LOWER(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.publishable')), 'null')) WHEN 'true' THEN 1 WHEN '1' THEN 1 WHEN 'false' THEN 0 WHEN '0' THEN 0 ELSE NULL END) STORED,
  ADD COLUMN evaluation_paused TINYINT GENERATED ALWAYS AS (CASE LOWER(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.paused')), 'null')) WHEN 'true' THEN 1 WHEN '1' THEN 1 WHEN 'false' THEN 0 WHEN '0' THEN 0 ELSE NULL END) STORED,
  ADD COLUMN source_digest CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.sourceDigest')), 'null')) STORED,
  ADD INDEX ix_pc_evaluation_run_report_artifact_id (workspace_id, report_artifact_id, state, id);

-- pc_harness_run：mode, taskId, reportArtifactId
ALTER TABLE pc_harness_run
  ADD COLUMN harness_mode VARCHAR(16) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.mode')), 'null')) STORED,
  ADD COLUMN harness_task_id BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.taskId')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.taskId')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN report_artifact_id BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reportArtifactId')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reportArtifactId')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD INDEX ix_pc_harness_run_harness_mode (workspace_id, harness_mode, state, id);

-- pc_idempotency：fingerprint
ALTER TABLE pc_idempotency
  ADD COLUMN request_fingerprint CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.fingerprint')), 'null')) STORED;

-- pc_import_batch：fingerprint, sourceArtifactId, canImport, committedAt, revokedAt
ALTER TABLE pc_import_batch
  ADD COLUMN source_fingerprint CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.fingerprint')), 'null')) STORED,
  ADD COLUMN source_artifact_id BIGINT GENERATED ALWAYS AS (CASE WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.sourceArtifactId')), 'null') REGEXP '^[0-9]{1,18}$' THEN CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.sourceArtifactId')), 'null') AS SIGNED) ELSE NULL END) STORED,
  ADD COLUMN import_allowed TINYINT GENERATED ALWAYS AS (CASE LOWER(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.canImport')), 'null')) WHEN 'true' THEN 1 WHEN '1' THEN 1 WHEN 'false' THEN 0 WHEN '0' THEN 0 ELSE NULL END) STORED,
  ADD COLUMN committed_at_iso VARCHAR(40) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.committedAt')), 'null')) STORED,
  ADD COLUMN revoked_at_iso VARCHAR(40) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.revokedAt')), 'null')) STORED;

-- pc_prompt_template：relativePath, scope, sha256, content, enabled
ALTER TABLE pc_prompt_template
  ADD COLUMN template_path VARCHAR(256) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.relativePath')), 'null')) STORED,
  ADD COLUMN template_scope VARCHAR(16) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.scope')), 'null')) STORED,
  ADD COLUMN template_sha256 CHAR(64) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.sha256')), 'null')) STORED,
  ADD COLUMN template_content LONGTEXT GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.content')), 'null')) STORED,
  ADD COLUMN template_enabled TINYINT GENERATED ALWAYS AS (CASE LOWER(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.enabled')), 'null')) WHEN 'true' THEN 1 WHEN '1' THEN 1 WHEN 'false' THEN 0 WHEN '0' THEN 0 ELSE NULL END) STORED,
  ADD INDEX ix_pc_prompt_template_template_scope (workspace_id, template_scope, state, id),
  ADD INDEX ix_pc_prompt_template_template_path (workspace_id, template_path, state, id),
  ADD CONSTRAINT ck_pc_prompt_scope CHECK((template_scope = 'PERSONAL' AND workspace_id IS NULL) OR (template_scope = 'PROJECT' AND workspace_id IS NOT NULL));

-- 原始提示词独立授权；只登记按钮，由管理员明确分配角色。
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '读取PaiCLI原始提示词',m.menu_id,9,'',NULL,1,0,'F','0','0','paicli:context:raw','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI原始提示词和覆盖全文独立权限'
FROM sys_menu m WHERE m.path='workbench' AND m.perms='paicli:session:read'
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:context:raw');
