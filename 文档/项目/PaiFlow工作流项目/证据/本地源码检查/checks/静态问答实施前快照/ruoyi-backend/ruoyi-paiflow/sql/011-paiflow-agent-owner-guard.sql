-- 未执行MySQL8增量草案，须在007 Agent表之后、按已批准的真实迁移步骤执行。
-- 仅作为同owner智能体插入的事务互斥；不持套餐、审批或任务真相，不自动初始化数据库。
CREATE TABLE paiflow_agent_owner_guard (
 owner_user_id BIGINT NOT NULL,
 PRIMARY KEY(owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX ix_paiflow_agent_owner_live ON paiflow_agent(owner_user_id,deleted);
