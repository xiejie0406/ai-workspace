-- 未执行增量草案；仅适用MySQL 8，须在既有001/004表和已批准迁移步骤之后执行。
-- 不创建数据库、不自动运行、不选择最近具名空间；NULL+原UID的资源需按已核对账号映射另行迁入。
ALTER TABLE paiflow_space DROP CHECK ck_paiflow_space_kind;
ALTER TABLE paiflow_space DROP CHECK ck_paiflow_space_team;
ALTER TABLE paiflow_space ADD CONSTRAINT ck_paiflow_space_kind CHECK (kind IN ('personal','team','default_personal'));
ALTER TABLE paiflow_space ADD CONSTRAINT ck_paiflow_space_team CHECK (
  (kind IN ('personal','default_personal') AND team_id IS NULL) OR (kind='team' AND team_id IS NOT NULL));
-- 私域映射不可由用户删除后重新分配；唯一键不因deleted改变而解锁旧ID。
ALTER TABLE paiflow_space ADD COLUMN default_owner_user_id BIGINT GENERATED ALWAYS AS
  (CASE WHEN kind='default_personal' THEN owner_user_id ELSE NULL END) STORED;
ALTER TABLE paiflow_space ADD UNIQUE KEY uq_paiflow_default_owner (default_owner_user_id);
ALTER TABLE paiflow_space ADD CONSTRAINT ck_paiflow_default_live CHECK (kind<>'default_personal' OR deleted=0);
-- 不为所有用户批量造空间或成员。首次明确visit/default资源创建的短事务只materialize内置私域。
-- 真实迁移应先核对旧UID、NULL空间资源、具名/团队归属、重复名称及访问时间，再单独批准写入。
