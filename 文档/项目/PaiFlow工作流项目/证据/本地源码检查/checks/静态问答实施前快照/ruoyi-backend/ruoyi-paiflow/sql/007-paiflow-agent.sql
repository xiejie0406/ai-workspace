-- PaiFlow智能体元数据增量审查稿；未执行，不注册平台菜单、不导入账号或App凭据。
-- 须先应用001/002；新平台按钮权限由原菜单授权机制配置：
-- paiflow:agent:query/create/edit/remove/copy。资源引用与发布渠道仍须真实提供者。
CREATE TABLE paiflow_agent (
  id BIGINT NOT NULL AUTO_INCREMENT,
  space_id BIGINT NOT NULL,
  owner_user_id BIGINT NOT NULL,
  workflow_id BIGINT NULL COMMENT '唯一引用现中心画布；普通指令型为空',
  revision BIGINT NOT NULL DEFAULT 0,
  version TINYINT NOT NULL COMMENT '1指令型，3工作流型',
  bot_type INT NOT NULL DEFAULT 0,
  name VARCHAR(32) NOT NULL,
  description VARCHAR(200) NULL,
  avatar VARCHAR(2560) NULL,
  config_json MEDIUMTEXT NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  active_name VARCHAR(32) GENERATED ALWAYS AS (CASE WHEN deleted=0 THEN name ELSE NULL END) STORED,
  create_by VARCHAR(64) NOT NULL,update_by VARCHAR(64) NOT NULL,
  create_time DATETIME(3) NOT NULL,update_time DATETIME(3) NOT NULL,
  PRIMARY KEY(id),UNIQUE KEY uq_paiflow_agent_name(space_id,active_name),
  UNIQUE KEY uq_paiflow_agent_workflow(workflow_id),
  KEY ix_paiflow_agent_owner(space_id,owner_user_id,deleted),
  CONSTRAINT fk_paiflow_agent_workflow FOREIGN KEY(workflow_id) REFERENCES paiflow_workflow(id),
  CONSTRAINT ck_paiflow_agent_version CHECK(version IN (1,3)),
  CONSTRAINT ck_paiflow_agent_binding CHECK(version=3 OR workflow_id IS NULL),
  CONSTRAINT ck_paiflow_agent_deleted CHECK(deleted IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
