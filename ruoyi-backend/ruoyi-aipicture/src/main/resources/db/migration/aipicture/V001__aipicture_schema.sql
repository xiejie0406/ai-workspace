-- 候选迁移：只可在明确批准的图片业务 MySQL schema 执行。应用启动不执行 DDL。
-- UTC 时间使用毫秒 BIGINT；正式身份沿用若依 user_id，不迁移密码。
CREATE TABLE aip_asset (
 id CHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,kind VARCHAR(32) NOT NULL,source VARCHAR(64) NOT NULL,
 object_key VARCHAR(255) NOT NULL,sha256 CHAR(64) NOT NULL,format VARCHAR(12) NOT NULL,width INT NOT NULL,height INT NOT NULL,
 size_bytes BIGINT NOT NULL,has_alpha BOOLEAN NOT NULL,provider JSON,created_at BIGINT NOT NULL,status VARCHAR(24) NOT NULL DEFAULT 'ready',
 run_id CHAR(36),attempt INT,artifact_index INT,
 UNIQUE KEY uk_asset_object(object_key),UNIQUE KEY uk_artifact(run_id,attempt,artifact_index),KEY ix_asset_owner(user_id,created_at)
) ENGINE=InnoDB;
CREATE TABLE aip_session (
 id CHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,title VARCHAR(80) NOT NULL,original_asset_id CHAR(36) NOT NULL,
 current_asset_id CHAR(36) NOT NULL,document JSON NOT NULL,revision BIGINT NOT NULL DEFAULT 0,row_version BIGINT NOT NULL DEFAULT 0,
 history_seq BIGINT NOT NULL DEFAULT 0,history_floor BIGINT NOT NULL DEFAULT 0,active_run_id CHAR(36),created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,
 KEY ix_session_owner(user_id,updated_at)
) ENGINE=InnoDB;
CREATE TABLE aip_session_asset (
 session_id CHAR(36) NOT NULL,asset_id CHAR(36) NOT NULL,user_id BIGINT NOT NULL,position INT NOT NULL,
 latest_document JSON,document_version BIGINT NOT NULL DEFAULT 0,PRIMARY KEY(session_id,asset_id),KEY ix_wall(session_id,position)
) ENGINE=InnoDB;
CREATE TABLE aip_history (
 id CHAR(36) PRIMARY KEY,session_id CHAR(36) NOT NULL,user_id BIGINT NOT NULL,seq BIGINT NOT NULL,action VARCHAR(64) NOT NULL,
 params JSON,result JSON,before_document JSON NOT NULL,after_document JSON NOT NULL,
 before_asset_id CHAR(36) NOT NULL,after_asset_id CHAR(36) NOT NULL,created_at BIGINT NOT NULL,
 UNIQUE KEY uk_history(session_id,seq),KEY ix_history_owner(user_id,session_id)
) ENGINE=InnoDB;
CREATE TABLE aip_selection (
 id CHAR(36) PRIMARY KEY,session_id CHAR(36) NOT NULL,user_id BIGINT NOT NULL,revision BIGINT NOT NULL,mask_asset_id CHAR(36) NOT NULL,
 markers JSON,strokes JSON,expires_at BIGINT NOT NULL,state VARCHAR(20) NOT NULL DEFAULT 'ready',
 UNIQUE KEY uk_selection(session_id)
) ENGINE=InnoDB;
CREATE TABLE aip_run (
 id CHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,session_id CHAR(36),tool VARCHAR(64) NOT NULL,status VARCHAR(32) NOT NULL,
 progress INT NOT NULL DEFAULT 0,stage VARCHAR(160),params JSON NOT NULL,result JSON,input_snapshot JSON NOT NULL,
 input_version BIGINT,input_revision BIGINT,attempt INT NOT NULL DEFAULT 0,provider_task_id VARCHAR(255),provider JSON,
 lease_token CHAR(36),lease_expires_at BIGINT,heartbeat_at BIGINT,error_code VARCHAR(64),message VARCHAR(1000),
 idempotency_key VARCHAR(128) NOT NULL,request_hash CHAR(64) NOT NULL,event_seq BIGINT NOT NULL DEFAULT 0,
 turn_id CHAR(36),step_id VARCHAR(64),batch_id CHAR(36),batch_item_id CHAR(36),created_at BIGINT NOT NULL,started_at BIGINT,finished_at BIGINT,
 UNIQUE KEY uk_run_idempotency(user_id,idempotency_key),KEY ix_run_status(status,lease_expires_at),KEY ix_run_owner(user_id,created_at)
) ENGINE=InnoDB;
CREATE TABLE aip_dispatch (
 run_id CHAR(36) PRIMARY KEY,available_at BIGINT NOT NULL,attempt INT NOT NULL DEFAULT 0,worker_id VARCHAR(128),
 status VARCHAR(24) NOT NULL DEFAULT 'pending',KEY ix_dispatch(status,available_at)
) ENGINE=InnoDB;
CREATE TABLE aip_run_event (
 run_id CHAR(36) NOT NULL,event_seq BIGINT NOT NULL,user_id BIGINT NOT NULL,payload JSON NOT NULL,created_at BIGINT NOT NULL,
 PRIMARY KEY(run_id,event_seq),KEY ix_event_owner(user_id,created_at)
) ENGINE=InnoDB;
CREATE TABLE aip_agent_turn (
 id CHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,session_id CHAR(36) NOT NULL,goal VARCHAR(1500) NOT NULL,reply TEXT,
 status VARCHAR(32) NOT NULL,base_revision BIGINT NOT NULL,base_version BIGINT NOT NULL,plan_hash CHAR(64),steps JSON,
 mask_asset_id CHAR(36),mask_width INT,mask_height INT,error_code VARCHAR(64),message VARCHAR(1000),command_key VARCHAR(128) NOT NULL,request_hash CHAR(64) NOT NULL,
 created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,KEY ix_turn(session_id,created_at),UNIQUE KEY uk_turn_command(user_id,command_key)
) ENGINE=InnoDB;
CREATE TABLE aip_plan_step (
 turn_id CHAR(36) NOT NULL,step_id VARCHAR(64) NOT NULL,position INT NOT NULL,tool VARCHAR(64) NOT NULL,params JSON NOT NULL,
 dependencies JSON NOT NULL,status VARCHAR(32) NOT NULL,run_id CHAR(36),attempt INT NOT NULL DEFAULT 0,
 PRIMARY KEY(turn_id,step_id),KEY ix_step_order(turn_id,position)
) ENGINE=InnoDB;
CREATE TABLE aip_batch (
 id CHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,run_id CHAR(36),status VARCHAR(32) NOT NULL,pipeline JSON NOT NULL,
 pipeline_hash CHAR(64) NOT NULL,formats JSON NOT NULL,created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,KEY ix_batch_owner(user_id,created_at)
) ENGINE=InnoDB;
CREATE TABLE aip_batch_item (
 id CHAR(36) PRIMARY KEY,batch_id CHAR(36) NOT NULL,user_id BIGINT NOT NULL,position INT NOT NULL,source_asset_id CHAR(36) NOT NULL,
 run_id CHAR(36),status VARCHAR(32) NOT NULL,output_ids JSON,error_code VARCHAR(64),message VARCHAR(1000),attempt INT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_batch_source(batch_id,source_asset_id),KEY ix_batch_item(batch_id,position)
) ENGINE=InnoDB;
CREATE TABLE aip_export (
 id CHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,session_id CHAR(36),run_id CHAR(36) NOT NULL,revision BIGINT,
 manifest JSON NOT NULL,object_key VARCHAR(255) NOT NULL,sha256 CHAR(64) NOT NULL,format VARCHAR(12) NOT NULL,created_at BIGINT NOT NULL,
 UNIQUE KEY uk_export_run(run_id),KEY ix_export_owner(user_id,created_at)
) ENGINE=InnoDB;
CREATE TABLE aip_evaluation (
 id CHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,run_id CHAR(36) NOT NULL,status VARCHAR(32) NOT NULL,
 manifest JSON NOT NULL,dataset_hash CHAR(64) NOT NULL,metrics JSON,provider JSON,created_at BIGINT NOT NULL,
 UNIQUE KEY uk_evaluation_run(run_id),KEY ix_eval_owner(user_id,created_at)
) ENGINE=InnoDB;
CREATE TABLE aip_import_map (
 source_dataset VARCHAR(128) NOT NULL,entity_type VARCHAR(32) NOT NULL,source_id VARCHAR(128) NOT NULL,target_id CHAR(36) NOT NULL,
 user_id BIGINT NOT NULL,sha256 CHAR(64),status VARCHAR(32) NOT NULL,created_at BIGINT NOT NULL,
 PRIMARY KEY(source_dataset,entity_type,source_id)
) ENGINE=InnoDB;
CREATE TABLE aip_worker_nonce (
 service_id VARCHAR(64) NOT NULL,nonce VARCHAR(80) NOT NULL,created_at BIGINT NOT NULL,
 PRIMARY KEY(service_id,nonce),KEY ix_nonce_expiry(created_at)
) ENGINE=InnoDB;
CREATE TABLE aip_command (
 user_id BIGINT NOT NULL,command VARCHAR(100) NOT NULL,idempotency_key VARCHAR(128) NOT NULL,
 request_hash CHAR(64) NOT NULL,result JSON NOT NULL,created_at BIGINT NOT NULL,
 PRIMARY KEY(user_id,command,idempotency_key),KEY ix_command_age(created_at)
) ENGINE=InnoDB;
