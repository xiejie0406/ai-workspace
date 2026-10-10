-- 仅生成评审迁移；禁止应用启动自动执行。VT输出与短期能力不存此表。
CREATE TABLE pc_terminal_session (
  id BIGINT NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NULL,
  owner_id BIGINT NULL,
  parent_id BIGINT NULL,
  session_id BIGINT NULL,
  runner_id BIGINT NULL,
  kind VARCHAR(64) NOT NULL,
  state VARCHAR(40) NOT NULL,
  unique_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  payload JSON NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  sequence_number BIGINT NOT NULL DEFAULT 0,
  fencing_token BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  expires_at TIMESTAMP(3) NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uq_pc_terminal_session_key(unique_key),
  KEY ix_pc_terminal_session_scope(workspace_id,owner_id,state,id),
  KEY ix_pc_terminal_session_parent(parent_id,state),
  KEY ix_pc_terminal_session_session(session_id,sequence_number),
  KEY ix_pc_terminal_session_expiry(state,expires_at),
  KEY ix_pc_terminal_session_runner(runner_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- 不自动分配角色。管理员在实际启用时按已批准对象赋权。
SET @pc_terminal_parent=(SELECT menu_id FROM sys_menu WHERE perms='paicli:session:read' LIMIT 1);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '网页终端查看',@pc_terminal_parent,80,'',NULL,1,0,'F','0','0','paicli:terminal:read','#','paicli-migration',UTC_TIMESTAMP(),'终端元数据与同owner画面查看'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:terminal:read');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '打开原终端',@pc_terminal_parent,81,'',NULL,1,0,'F','0','0','paicli:terminal:use','#','paicli-migration',UTC_TIMESTAMP(),'不替代任务、审批、连接等已有权限'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:terminal:use');