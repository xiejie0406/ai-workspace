-- 只注册服务器授权菜单/按钮/字典；不分配角色，不改变navType设置。

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'PaiCLI智能编码',0,8,'paicli',NULL,1,0,'M','0','0','', 'code','paicli-migration',UTC_TIMESTAMP(),'独立业务；若依授权导航' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE path='paicli' AND parent_id=0);

SET @pc_root=(SELECT menu_id FROM sys_menu WHERE path='paicli' AND parent_id=0 ORDER BY menu_id LIMIT 1);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '编码工作台',@pc_root,1,'workbench','paicli/workbench/index',1,0,'C','0','0','paicli:session:read','documentation','paicli-migration',UTC_TIMESTAMP(),'PaiCLI业务页面' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@pc_root AND path='workbench');

SET @pc_workbench=(SELECT menu_id FROM sys_menu WHERE parent_id=@pc_root AND path='workbench' ORDER BY menu_id LIMIT 1);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '后台任务',@pc_root,2,'tasks','paicli/tasks/index',1,0,'C','0','0','paicli:task:read','documentation','paicli-migration',UTC_TIMESTAMP(),'PaiCLI业务页面' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@pc_root AND path='tasks');

SET @pc_tasks=(SELECT menu_id FROM sys_menu WHERE parent_id=@pc_root AND path='tasks' ORDER BY menu_id LIMIT 1);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '知识与上下文',@pc_root,3,'knowledge','paicli/knowledge/index',1,0,'C','0','0','paicli:knowledge:read','documentation','paicli-migration',UTC_TIMESTAMP(),'PaiCLI业务页面' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@pc_root AND path='knowledge');

SET @pc_knowledge=(SELECT menu_id FROM sys_menu WHERE parent_id=@pc_root AND path='knowledge' ORDER BY menu_id LIMIT 1);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '连接与模型',@pc_root,4,'connections','paicli/connections/index',1,0,'C','0','0','paicli:connection:read','documentation','paicli-migration',UTC_TIMESTAMP(),'PaiCLI业务页面' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@pc_root AND path='connections');

SET @pc_connections=(SELECT menu_id FROM sys_menu WHERE parent_id=@pc_root AND path='connections' ORDER BY menu_id LIMIT 1);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '审计与评测',@pc_root,5,'audit','paicli/audit/index',1,0,'C','0','0','paicli:audit:read','documentation','paicli-migration',UTC_TIMESTAMP(),'PaiCLI业务页面' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@pc_root AND path='audit');

SET @pc_audit=(SELECT menu_id FROM sys_menu WHERE parent_id=@pc_root AND path='audit' ORDER BY menu_id LIMIT 1);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:workspace:list',@pc_workbench,1,'',NULL,1,0,'F','0','0','paicli:workspace:list','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:workspace:list');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:session:write',@pc_workbench,2,'',NULL,1,0,'F','0','0','paicli:session:write','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:session:write');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:session:export',@pc_workbench,3,'',NULL,1,0,'F','0','0','paicli:session:export','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:session:export');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:approval:decide',@pc_workbench,4,'',NULL,1,0,'F','0','0','paicli:approval:decide','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:approval:decide');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:snapshot:read',@pc_workbench,5,'',NULL,1,0,'F','0','0','paicli:snapshot:read','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:snapshot:read');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:snapshot:restore',@pc_workbench,6,'',NULL,1,0,'F','0','0','paicli:snapshot:restore','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:snapshot:restore');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:artifact:read',@pc_workbench,7,'',NULL,1,0,'F','0','0','paicli:artifact:read','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:artifact:read');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:artifact:write',@pc_workbench,8,'',NULL,1,0,'F','0','0','paicli:artifact:write','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:artifact:write');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:task:write',@pc_tasks,1,'',NULL,1,0,'F','0','0','paicli:task:write','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:task:write');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:task:cancel',@pc_tasks,2,'',NULL,1,0,'F','0','0','paicli:task:cancel','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:task:cancel');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:task:recover',@pc_tasks,3,'',NULL,1,0,'F','0','0','paicli:task:recover','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:task:recover');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:knowledge:manage',@pc_knowledge,1,'',NULL,1,0,'F','0','0','paicli:knowledge:manage','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:knowledge:manage');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:workspace:manage',@pc_connections,1,'',NULL,1,0,'F','0','0','paicli:workspace:manage','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:workspace:manage');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:runner:list',@pc_connections,2,'',NULL,1,0,'F','0','0','paicli:runner:list','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:runner:list');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:runner:manage',@pc_connections,3,'',NULL,1,0,'F','0','0','paicli:runner:manage','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:runner:manage');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:connection:manage',@pc_connections,4,'',NULL,1,0,'F','0','0','paicli:connection:manage','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:connection:manage');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:secret:write',@pc_connections,5,'',NULL,1,0,'F','0','0','paicli:secret:write','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:secret:write');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:audit:raw',@pc_audit,1,'',NULL,1,0,'F','0','0','paicli:audit:raw','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:audit:raw');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:audit:export',@pc_audit,2,'',NULL,1,0,'F','0','0','paicli:audit:export','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:audit:export');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:evaluation:read',@pc_audit,3,'',NULL,1,0,'F','0','0','paicli:evaluation:read','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:evaluation:read');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:evaluation:run',@pc_audit,4,'',NULL,1,0,'F','0','0','paicli:evaluation:run','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:evaluation:run');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'paicli:import:manage',@pc_audit,5,'',NULL,1,0,'F','0','0','paicli:import:manage','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI按钮权限；由管理员明确分配角色' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:import:manage');

INSERT INTO sys_dict_type(dict_name,dict_type,status,create_by,create_time,remark) SELECT 'PaiCLI任务状态','pc_task_state','0','paicli-migration',UTC_TIMESTAMP(),'PaiCLI业务字典' WHERE NOT EXISTS(SELECT 1 FROM sys_dict_type WHERE dict_type='pc_task_state');

INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,list_class,is_default,status,create_by,create_time) SELECT 0,'排队','ENQUEUED','pc_task_state','default','N','0','paicli-migration',UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data WHERE dict_type='pc_task_state' AND dict_value='ENQUEUED');

INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,list_class,is_default,status,create_by,create_time) SELECT 1,'执行中','RUNNING','pc_task_state','default','N','0','paicli-migration',UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data WHERE dict_type='pc_task_state' AND dict_value='RUNNING');

INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,list_class,is_default,status,create_by,create_time) SELECT 2,'等待批准','WAITING_APPROVAL','pc_task_state','default','N','0','paicli-migration',UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data WHERE dict_type='pc_task_state' AND dict_value='WAITING_APPROVAL');

INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,list_class,is_default,status,create_by,create_time) SELECT 3,'取消中','CANCELLING','pc_task_state','default','N','0','paicli-migration',UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data WHERE dict_type='pc_task_state' AND dict_value='CANCELLING');

INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,list_class,is_default,status,create_by,create_time) SELECT 4,'完成','COMPLETED','pc_task_state','default','N','0','paicli-migration',UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data WHERE dict_type='pc_task_state' AND dict_value='COMPLETED');

INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,list_class,is_default,status,create_by,create_time) SELECT 5,'失败','FAILED','pc_task_state','default','N','0','paicli-migration',UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data WHERE dict_type='pc_task_state' AND dict_value='FAILED');

INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,list_class,is_default,status,create_by,create_time) SELECT 6,'已取消','CANCELLED','pc_task_state','default','N','0','paicli-migration',UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data WHERE dict_type='pc_task_state' AND dict_value='CANCELLED');

INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,list_class,is_default,status,create_by,create_time) SELECT 7,'结果待核对','RECONCILE_REQUIRED','pc_task_state','default','N','0','paicli-migration',UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data WHERE dict_type='pc_task_state' AND dict_value='RECONCILE_REQUIRED');

INSERT INTO sys_dict_type(dict_name,dict_type,status,create_by,create_time,remark) SELECT 'PaiCLI执行模式','pc_mode','0','paicli-migration',UTC_TIMESTAMP(),'PaiCLI业务字典' WHERE NOT EXISTS(SELECT 1 FROM sys_dict_type WHERE dict_type='pc_mode');

INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,list_class,is_default,status,create_by,create_time) SELECT 0,'直接执行','REACT','pc_mode','default','N','0','paicli-migration',UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data WHERE dict_type='pc_mode' AND dict_value='REACT');

INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,list_class,is_default,status,create_by,create_time) SELECT 1,'计划执行','PLAN','pc_mode','default','N','0','paicli-migration',UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data WHERE dict_type='pc_mode' AND dict_value='PLAN');

INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,list_class,is_default,status,create_by,create_time) SELECT 2,'团队协作','TEAM','pc_mode','default','N','0','paicli-migration',UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data WHERE dict_type='pc_mode' AND dict_value='TEAM');
