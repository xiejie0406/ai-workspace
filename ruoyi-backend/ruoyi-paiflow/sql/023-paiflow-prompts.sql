-- 未执行增量草案；仅使用用户指定的原PaiFlow业务库，不建数据库。
-- 原提示管理/版本/官方表在参考SQL均无种子；不伪造官方内容或迁入原UID/模型凭据。
CREATE TABLE paiflow_prompt (
 id BIGINT NOT NULL AUTO_INCREMENT, space_id BIGINT NULL, owner_user_id BIGINT NOT NULL,
 group_id BIGINT NULL, revision BIGINT NOT NULL DEFAULT 0, name VARCHAR(200) NOT NULL,
 prompt_key VARCHAR(64) NOT NULL, payload_json LONGTEXT NOT NULL,
 kind TINYINT NOT NULL, official TINYINT NOT NULL DEFAULT 0, deleted TINYINT NOT NULL DEFAULT 0,
 create_by VARCHAR(64) NOT NULL,update_by VARCHAR(64) NOT NULL,create_time DATETIME NOT NULL,update_time DATETIME NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_prompt_key(prompt_key),KEY ix_prompt_scope(space_id,official,deleted,group_id),
 CONSTRAINT ck_prompt_scope CHECK((official=1 AND space_id IS NULL) OR (official=0 AND space_id>0)),
 CONSTRAINT ck_prompt_kind CHECK(kind IN(1,2)),CONSTRAINT ck_prompt_revision CHECK(revision>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE paiflow_prompt_version (
 prompt_id BIGINT NOT NULL,version_num BIGINT NOT NULL,payload_json LONGTEXT NOT NULL,
 description VARCHAR(2000) NULL,actor_user_id BIGINT NOT NULL,create_time DATETIME NOT NULL,
 PRIMARY KEY(prompt_id,version_num),CONSTRAINT ck_prompt_version CHECK(version_num>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
