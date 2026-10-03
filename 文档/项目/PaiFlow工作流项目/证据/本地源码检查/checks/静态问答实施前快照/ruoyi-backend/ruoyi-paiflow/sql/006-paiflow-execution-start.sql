-- 未执行增量草案：沿用用户指定原业务MySQL，不创建数据库或自动升级。
-- 应先暂停派发并核对既有运行；已派发旧记录保守视为签发过许可，不能清空字段后重放。
-- execution_started_at表示中心曾签发许可，不是Worker执行完成或外部副作用回执。
ALTER TABLE paiflow_run ADD COLUMN execution_started_at DATETIME(6) NULL AFTER lease_expires_at;
UPDATE paiflow_run SET execution_started_at=COALESCE(update_time,create_time) WHERE attempt>0;
