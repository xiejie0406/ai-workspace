-- 评测样本与知识文档分离；只在人工批准的显式迁移中执行。
CREATE TABLE ps_evaluation_dataset (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, label VARCHAR(160) NOT NULL,
 object_key VARCHAR(500) NOT NULL, sha256 CHAR(64) NOT NULL,
 byte_size INT NOT NULL, case_count INT NOT NULL, created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_ps_eval_dataset_object(object_key), INDEX ix_ps_eval_dataset_owner(user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS paismart_schema_history (version_no INT PRIMARY KEY, description VARCHAR(128) NOT NULL, installed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);
INSERT INTO paismart_schema_history(version_no,description) VALUES(5,'Paismart private evaluation dataset objects');
