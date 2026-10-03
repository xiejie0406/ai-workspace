-- 未执行MySQL8增量草案：依赖既有003运行表，012只保存工作流公开资源绑定。
-- 不创建数据库/账号，不写真实批准/凭据/费用，不以管理Hello授权替代逐次执行许可。
ALTER TABLE paiflow_run ADD COLUMN model_permit_json LONGTEXT NULL;
CREATE TABLE paiflow_model_operation (
 id VARCHAR(64) NOT NULL,run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,lease_id VARCHAR(64) NOT NULL,worker_id VARCHAR(64) NOT NULL,
 attempt INT NOT NULL,node_id VARCHAR(255) NOT NULL,iteration_id VARCHAR(255) NULL,
 workflow_version_id BIGINT NOT NULL,dsl_hash CHAR(64) NOT NULL,space_id BIGINT NOT NULL,owner_user_id BIGINT NOT NULL,
 resource_id VARCHAR(255) NOT NULL,resource_version VARCHAR(128) NOT NULL,provider VARCHAR(16) NOT NULL,
 request_key TEXT NOT NULL,request_key_hash CHAR(64) NOT NULL,request_hash CHAR(64) NOT NULL,parameters_json MEDIUMTEXT NOT NULL,
 approval_json MEDIUMTEXT NOT NULL,approval_ref VARCHAR(255) NOT NULL,approval_expires_at DATETIME(3) NOT NULL,deadline DATETIME(3) NOT NULL,
 state VARCHAR(32) NOT NULL,dispatch_started TINYINT NOT NULL DEFAULT 0,grant_acquired TINYINT NOT NULL DEFAULT 0,
 grant_id VARCHAR(64) NULL,grant_token_hash CHAR(64) NULL,last_seq BIGINT NOT NULL DEFAULT 0,finish_seq BIGINT NULL,
 finish_reason VARCHAR(32) NULL,response_bytes BIGINT NOT NULL DEFAULT 0,stop_confirmed TINYINT NOT NULL DEFAULT 0,
 stop_proof_id VARCHAR(255) NULL,failure_kind VARCHAR(32) NULL,usage_json MEDIUMTEXT NULL,revision BIGINT NOT NULL DEFAULT 0,
 create_time DATETIME(3) NOT NULL,update_time DATETIME(3) NOT NULL,
 PRIMARY KEY(id),UNIQUE KEY uq_paiflow_model_operation_request(run_id,attempt,request_key_hash),
 KEY ix_paiflow_model_operation_run(run_id,state),
 CONSTRAINT fk_paiflow_model_operation_run FOREIGN KEY(run_id) REFERENCES paiflow_run(id),
 CONSTRAINT ck_paiflow_model_operation_provider CHECK(provider IN ('openai','xinghuo')),
 CONSTRAINT ck_paiflow_model_operation_state CHECK(state IN ('PREPARED','RUNNING','COMPLETED','FAILED','CANCELLED','EFFECT_UNKNOWN')),
 CONSTRAINT ck_paiflow_model_operation_flags CHECK(dispatch_started IN (0,1) AND grant_acquired IN (0,1) AND stop_confirmed IN (0,1)),
 CONSTRAINT ck_paiflow_model_operation_count CHECK(attempt>0 AND workflow_version_id>0 AND space_id>0 AND owner_user_id>0 AND last_seq>=0 AND last_seq<=10000 AND (finish_seq IS NULL OR (finish_seq>0 AND finish_seq<=last_seq)) AND response_bytes>=0 AND response_bytes<=8388608)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE paiflow_model_frame (
 operation_id VARCHAR(64) NOT NULL,seq BIGINT NOT NULL,frame_json MEDIUMTEXT NOT NULL,frame_hash CHAR(64) NOT NULL,
 frame_bytes INT NOT NULL,create_time DATETIME(3) NOT NULL,
 PRIMARY KEY(operation_id,seq),
 CONSTRAINT fk_paiflow_model_frame_operation FOREIGN KEY(operation_id) REFERENCES paiflow_model_operation(id),
 CONSTRAINT ck_paiflow_model_frame_sequence CHECK(seq>0 AND seq<=10000 AND frame_bytes>0 AND frame_bytes<=65536)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- request_key保全原Worker实际字符串；唯一索引只用SHA256，重复仍比对完整字符串/请求，禁止哈希碰撞假等价。
-- grant_token只保存摘要。PRIVATE SDK key/base地址仅进入受控内存桥，绝不放assignment、响应、事件或本表。
-- 既有运行没有模型许可，不回填派发标记或重新发放已取得的许可；真实切换须核对在途费用与停止事实。
