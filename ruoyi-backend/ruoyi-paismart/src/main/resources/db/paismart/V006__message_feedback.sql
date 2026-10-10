-- 仅由批准的显式升级入口执行；启动与SchemaReadiness不会执行DDL。
-- 执行前确认V001—V005完整且无version_no=6、以下新增列或同名索引。
-- MySQL DDL可能逐句提交：任一句失败即停止，不直接整份重跑或补记版本。
-- 部分执行须按元数据逐项核对类型/NULL/默认值及索引后，仅显式执行未完成语句。
-- 不推断历史消息、反馈与生成任务的对应关系；旧记录新增关联列及updated_at保留NULL。
ALTER TABLE ps_chat_message ADD COLUMN generation_id VARCHAR(36) NULL;
ALTER TABLE ps_tool_feedback ADD COLUMN message_id VARCHAR(36) NULL;
ALTER TABLE ps_tool_feedback ADD COLUMN generation_id VARCHAR(36) NULL;
ALTER TABLE ps_tool_feedback ADD COLUMN row_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE ps_tool_feedback ADD COLUMN updated_at DATETIME NULL;
-- 完整ID不使用前缀；NULL历史记录允许共存，不回填或去重删除旧反馈。
CREATE UNIQUE INDEX uk_ps_feedback_user_message ON ps_tool_feedback(user_id,message_id);
-- 仅在以上全部成功后记录版本；不使用IGNORE掩盖重复或失败。
INSERT INTO paismart_schema_history(version_no,description) VALUES(6,'Paismart message-level feedback and generation ownership');
