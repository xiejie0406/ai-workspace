-- 未执行增量草案：仅沿用已确认PaiFlow原业务库，不建数据库、不自动初始化或升级。
-- 原text_node_config为个人偏好；0是本表只读系统归属，不是平台账号。
-- 原画布保存separator字面值，删除目录项不改历史画布或不可变版本。
-- 排序规则沿用原schema.sql的MySQL8 utf8mb4_0900_ai_ci；实际目标版本仍须核实。
CREATE TABLE paiflow_text_config (
  id BIGINT NOT NULL AUTO_INCREMENT,
  owner_user_id BIGINT NOT NULL,
  `separator` VARCHAR(255) NOT NULL,
  `comment` VARCHAR(255) NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  create_by VARCHAR(64) NOT NULL,
  update_by VARCHAR(64) NOT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_text_config_owner_separator(owner_user_id,`separator`),
  CONSTRAINT ck_text_config_owner CHECK(owner_user_id>=0),
  CONSTRAINT ck_text_config_revision CHECK(revision>=0)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- 五项原种子来自参考schema.sql:3688。原id4为真实TAB；id5 SQL字面量为单反斜杠n，
-- 按原Docker未启用NO_BACKSLASH_ESCAPES的MySQL语义解码为LF，不是两个字符的反斜杠n。
-- hex固定解码后的值；真实旧库若曾改sql_mode，须以原库实际HEX(separator)核对迁移。
INSERT INTO paiflow_text_config(id,owner_user_id,`separator`,`comment`,revision,create_by,update_by,create_time,update_time) VALUES
  (1,0,CONVERT(0xE38082 USING utf8mb4),'句号',0,'reference','reference','2025-11-21 17:02:02','2025-11-21 17:02:02'),
  (2,0,CONVERT(0xEFBC9B USING utf8mb4),'分号',0,'reference','reference','2025-11-21 17:02:02','2025-11-21 17:02:02'),
  (3,0,CONVERT(0x20 USING utf8mb4),'空格',0,'reference','reference','2025-11-21 17:02:02','2025-11-21 17:02:02'),
  (4,0,CONVERT(0x09 USING utf8mb4),'制表符',0,'reference','reference','2025-11-21 17:02:02','2025-11-21 17:02:02'),
  (5,0,CONVERT(0x0A USING utf8mb4),'换行',0,'reference','reference','2025-11-21 17:02:02','2025-11-21 17:02:02');
