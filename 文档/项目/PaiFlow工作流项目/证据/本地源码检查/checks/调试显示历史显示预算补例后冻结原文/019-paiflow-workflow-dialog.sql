-- 未执行增量草案：只在获批的原业务MySQL、备份与迁移步骤中执行，不创建数据库。
-- 仅调试显示历史；真实Run、事件、检查点与结果仍由已有中心表持真相。
-- 不导入旧UID/Spark SID，不以内容hash、Run或chatId去重；原每次POST均追加。
CREATE TABLE paiflow_workflow_dialog (
 id BIGINT NOT NULL AUTO_INCREMENT,
 space_id BIGINT NOT NULL,owner_user_id BIGINT NOT NULL,workflow_id BIGINT NOT NULL,workflow_version_id BIGINT NOT NULL,
 run_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,event_watermark BIGINT NOT NULL,
 question MEDIUMTEXT NULL,answer MEDIUMTEXT NULL,question_item MEDIUMTEXT NULL,answer_item MEDIUMTEXT NULL,data MEDIUMTEXT NULL,
 sid VARCHAR(255) NOT NULL,chat_id VARCHAR(100) NULL,type TINYINT NOT NULL DEFAULT 1,deleted TINYINT NOT NULL DEFAULT 0,
 create_time DATETIME(3) NOT NULL,
 PRIMARY KEY(id),
 KEY ix_paiflow_dialog_owned(space_id,workflow_id,owner_user_id,type,deleted,create_time,id),
 CONSTRAINT ck_paiflow_dialog_type CHECK(type=1),
 CONSTRAINT ck_paiflow_dialog_deleted CHECK(deleted IN(0,1)),
 CONSTRAINT ck_paiflow_dialog_watermark CHECK(event_watermark>=0),
 CONSTRAINT ck_paiflow_dialog_scope CHECK(space_id>0 AND owner_user_id>0 AND workflow_id>0 AND workflow_version_id>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 跨表绑定由现中心访问/行锁路径核验，不新增跨表FK或第二运行状态。
