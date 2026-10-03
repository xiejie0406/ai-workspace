-- PaiSmart 智能链路正式状态；只由显式迁移入口执行，不在启动时自动建表。
CREATE TABLE ps_intelligence_lock (
 id VARCHAR(50) PRIMARY KEY, owner VARCHAR(100) NULL, fence_revision BIGINT NOT NULL DEFAULT 0,
 lease_until DATETIME(6) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO ps_intelligence_lock(id) VALUES('KNOWLEDGE_INDEX');
CREATE TABLE ps_document_chunk (
 id VARCHAR(36) PRIMARY KEY, document_id VARCHAR(36) NOT NULL, content_version INT NOT NULL,
 chunk_no INT NOT NULL, page_no INT NULL, text_content MEDIUMTEXT NOT NULL, context_text TEXT NULL,
 anchor_text VARCHAR(256) NOT NULL, token_count BIGINT NOT NULL DEFAULT 0,
 vector_json MEDIUMTEXT NULL, embedding_revision VARCHAR(36) NULL, embedding_input_hash CHAR(64) NULL, created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_ps_chunk_version (document_id,content_version,chunk_no), INDEX ix_ps_chunk_revision(embedding_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ps_provider (
 id VARCHAR(36) PRIMARY KEY, scope VARCHAR(20) NOT NULL, name VARCHAR(100) NOT NULL,
 endpoint VARCHAR(1000) NOT NULL, model VARCHAR(200) NOT NULL, dimension INT NULL,
 secret_ref VARCHAR(200) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT FALSE, active BOOLEAN NOT NULL DEFAULT FALSE,
 revision BIGINT NOT NULL DEFAULT 1, config_json TEXT NOT NULL, updated_at DATETIME(6) NOT NULL,
 INDEX ix_ps_provider_scope(scope,active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ps_model_call (
 id VARCHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,operation_id VARCHAR(160) NOT NULL,scope VARCHAR(20) NOT NULL,
 request_hash VARCHAR(64) NOT NULL,status VARCHAR(25) NOT NULL,reservation_id VARCHAR(36) NOT NULL,
 response_json MEDIUMTEXT NULL,actual_tokens BIGINT NULL,estimated BOOLEAN NOT NULL DEFAULT FALSE,
 reason VARCHAR(500) NULL,created_at DATETIME(6) NOT NULL,updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_ps_model_operation(user_id,scope,operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ps_index_revision (
 id VARCHAR(36) PRIMARY KEY, index_name VARCHAR(150) NOT NULL UNIQUE, provider_snapshot MEDIUMTEXT NOT NULL,
 state VARCHAR(30) NOT NULL, checkpoint_document VARCHAR(36) NULL, processed_count BIGINT NOT NULL DEFAULT 0,
 source_watermark DATETIME(6) NULL, validation_json MEDIUMTEXT NULL, error_message VARCHAR(500) NULL, row_version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ps_index_switch (
 id VARCHAR(36) PRIMARY KEY, from_revision VARCHAR(36) NULL, to_revision VARCHAR(36) NOT NULL,
 state VARCHAR(30) NOT NULL, error_message VARCHAR(500) NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ps_document_conflict (
 id VARCHAR(36) PRIMARY KEY, new_document_id VARCHAR(36) NOT NULL, old_document_id VARCHAR(36) NOT NULL,
 new_version INT NOT NULL, old_version INT NOT NULL, new_chunk_id VARCHAR(36) NOT NULL, old_chunk_id VARCHAR(36) NOT NULL,
 pair_key VARCHAR(160) NOT NULL UNIQUE, reason_json TEXT NOT NULL, status VARCHAR(25) NOT NULL DEFAULT 'PENDING',
 action VARCHAR(25) NULL, resolved_by BIGINT NULL, note VARCHAR(1000) NULL, row_version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL, resolved_at DATETIME(6) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ps_conversation (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, title VARCHAR(160) NOT NULL, archived BOOLEAN NOT NULL DEFAULT FALSE,
 space_id VARCHAR(36) NULL,
 row_version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, INDEX ix_ps_conversation_owner(user_id,archived)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ps_chat_message (
 id VARCHAR(36) PRIMARY KEY, conversation_id VARCHAR(36) NOT NULL, role VARCHAR(20) NOT NULL,
 content MEDIUMTEXT NOT NULL, references_json MEDIUMTEXT NULL, state VARCHAR(25) NOT NULL,
 created_at DATETIME(6) NOT NULL, INDEX ix_ps_message_conversation(conversation_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ps_chat_generation (
 id VARCHAR(36) PRIMARY KEY, conversation_id VARCHAR(36) NOT NULL, user_id BIGINT NOT NULL,
 assistant_message_id VARCHAR(36) NOT NULL, client_request_id VARCHAR(100) NOT NULL,
 state VARCHAR(25) NOT NULL, execution_attempt INT NOT NULL DEFAULT 0, next_sequence BIGINT NOT NULL DEFAULT 1, stop_requested BOOLEAN NOT NULL DEFAULT FALSE,
 request_json MEDIUMTEXT NOT NULL, error_message VARCHAR(500) NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_ps_generation_request(user_id,client_request_id), INDEX ix_ps_generation_state(state,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ps_chat_event (
 generation_id VARCHAR(36) NOT NULL, sequence_no BIGINT NOT NULL, event_json MEDIUMTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL, PRIMARY KEY(generation_id,sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ps_ws_ticket (
 ticket_hash VARCHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL, origin VARCHAR(500) NOT NULL,
 platform_session VARCHAR(100) NOT NULL,
 expires_at DATETIME(6) NOT NULL, consumed_at DATETIME(6) NULL, created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ps_reference (
 id VARCHAR(36) PRIMARY KEY, message_id VARCHAR(36) NOT NULL, document_id VARCHAR(36) NOT NULL,
 content_version INT NOT NULL, chunk_id VARCHAR(36) NOT NULL, reference_no INT NOT NULL,
 page_no INT NULL, anchor_text VARCHAR(256) NOT NULL, evidence_text MEDIUMTEXT NOT NULL,
 verification_json TEXT NULL, metadata_json MEDIUMTEXT NULL, created_at DATETIME(6) NOT NULL, UNIQUE KEY uk_ps_reference_no(message_id,reference_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ps_tool_feedback (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, conversation_id VARCHAR(36) NOT NULL,
 rating VARCHAR(20) NOT NULL, note VARCHAR(1000) NOT NULL, created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ps_ocr_ticket (
 token_hash VARCHAR(64) PRIMARY KEY,job_id VARCHAR(36) NOT NULL,document_id VARCHAR(36) NOT NULL,
 content_version INT NOT NULL,lease_token VARCHAR(36) NOT NULL,max_uses INT NOT NULL,used_count INT NOT NULL DEFAULT 0,
 expires_at DATETIME(6) NOT NULL,created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ps_ocr_call (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, operation_id VARCHAR(160) NOT NULL,
 job_id VARCHAR(36) NULL, document_id VARCHAR(36) NULL, content_version INT NULL,
 image_hash VARCHAR(64) NOT NULL, image_bytes BIGINT NOT NULL, state VARCHAR(25) NOT NULL,
 actual_pages INT NULL, provider_request_id VARCHAR(160) NULL, response_json MEDIUMTEXT NULL,
 reason VARCHAR(500) NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_ps_ocr_operation(user_id,operation_id), INDEX ix_ps_ocr_job(job_id), INDEX ix_ps_ocr_state(state,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ps_evaluation (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, label VARCHAR(160) NOT NULL, state VARCHAR(25) NOT NULL,
 job_id VARCHAR(36) NULL,
 config_json MEDIUMTEXT NOT NULL, dataset_json MEDIUMTEXT NOT NULL, report_json MEDIUMTEXT NULL,
 row_version BIGINT NOT NULL DEFAULT 1, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ps_evaluation_case (
 evaluation_id VARCHAR(36) NOT NULL, case_id VARCHAR(100) NOT NULL, result_json MEDIUMTEXT NOT NULL,
 PRIMARY KEY(evaluation_id,case_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ps_evaluation_review (
 id VARCHAR(36) PRIMARY KEY, evaluation_id VARCHAR(36) NOT NULL, case_id VARCHAR(100) NOT NULL,
 reviewer_id BIGINT NOT NULL, correct BOOLEAN NULL, score INT NULL, reason VARCHAR(2000) NOT NULL,
 revision BIGINT NOT NULL, created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_ps_review_revision(evaluation_id,case_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 仅由人工批准的显式迁移执行；模块启动不会自动创建表或标记历史。
CREATE TABLE IF NOT EXISTS paismart_schema_history (version_no INT PRIMARY KEY, description VARCHAR(128) NOT NULL, installed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);
INSERT INTO paismart_schema_history(version_no,description) VALUES(3,'Paismart intelligence, conversations, model and OCR calls');
