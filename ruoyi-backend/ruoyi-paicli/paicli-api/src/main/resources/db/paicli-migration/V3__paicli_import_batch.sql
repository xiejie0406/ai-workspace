-- explicit authorized migration only; source import ledger and version-safe rollback.
CREATE TABLE pc_import_batch (
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
  UNIQUE KEY uq_pc_import_batch_key(unique_key),
  KEY ix_pc_import_batch_scope(workspace_id,owner_id,state,id),
  KEY ix_pc_import_batch_parent(parent_id,state),
  KEY ix_pc_import_batch_session(session_id,sequence_number),
  KEY ix_pc_import_batch_expiry(state,expires_at),
  KEY ix_pc_import_batch_runner(runner_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '迁移预检查询',(SELECT menu_id FROM (SELECT menu_id FROM sys_menu WHERE path='audit' AND component='paicli/audit/index' LIMIT 1) parent),80,'','',1,0,'F','0','0','paicli:import:read','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI迁移批次权限；不自动分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:import:read');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '迁移提交回撤',(SELECT menu_id FROM (SELECT menu_id FROM sys_menu WHERE path='audit' AND component='paicli/audit/index' LIMIT 1) parent),81,'','',1,0,'F','0','0','paicli:import:manage','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI迁移批次权限；不自动分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:import:manage');
