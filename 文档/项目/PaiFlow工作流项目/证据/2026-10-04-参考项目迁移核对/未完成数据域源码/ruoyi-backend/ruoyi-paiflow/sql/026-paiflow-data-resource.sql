-- 原数据库资源迁移：现有MySQL中的逻辑库/表及测试、正式行；未执行。
CREATE TABLE paiflow_data_database (
  id BIGINT NOT NULL AUTO_INCREMENT,space_id BIGINT NOT NULL,owner_user_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,description VARCHAR(2000) NOT NULL DEFAULT '',avatar_icon VARCHAR(1024),avatar_color VARCHAR(128),
  revision BIGINT NOT NULL DEFAULT 0,deleted TINYINT NOT NULL DEFAULT 0,create_time DATETIME(3) NOT NULL,update_time DATETIME(3) NOT NULL,
  PRIMARY KEY(id),KEY ix_paiflow_data_scope(space_id,deleted,id),
  CONSTRAINT fk_paiflow_data_space FOREIGN KEY(space_id) REFERENCES paiflow_space(id),
  CONSTRAINT ck_paiflow_data_db_revision CHECK(revision>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE paiflow_data_table (
  id BIGINT NOT NULL AUTO_INCREMENT,db_id BIGINT NOT NULL,name VARCHAR(63) NOT NULL,description VARCHAR(2000) NOT NULL DEFAULT '',fields_json MEDIUMTEXT NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,deleted TINYINT NOT NULL DEFAULT 0,create_time DATETIME(3) NOT NULL,update_time DATETIME(3) NOT NULL,
  PRIMARY KEY(id),KEY ix_paiflow_data_table(db_id,deleted,id),CONSTRAINT fk_paiflow_data_table_db FOREIGN KEY(db_id) REFERENCES paiflow_data_database(id),
  CONSTRAINT ck_paiflow_data_table_revision CHECK(revision>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE paiflow_data_row (
  id BIGINT NOT NULL AUTO_INCREMENT,table_id BIGINT NOT NULL,exec_env TINYINT NOT NULL,row_json MEDIUMTEXT NOT NULL,
  PRIMARY KEY(id),KEY ix_paiflow_data_rows(table_id,exec_env,id),CONSTRAINT fk_paiflow_data_row_table FOREIGN KEY(table_id) REFERENCES paiflow_data_table(id),
  CONSTRAINT ck_paiflow_data_env CHECK(exec_env IN(1,2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE paiflow_data_request (
  run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,attempt INT NOT NULL,
  request_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,response_json MEDIUMTEXT NOT NULL,
  PRIMARY KEY(run_id,attempt,request_key_hash),CONSTRAINT fk_paiflow_data_request_run FOREIGN KEY(run_id) REFERENCES paiflow_run(id),CONSTRAINT ck_paiflow_data_attempt CHECK(attempt>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
