-- 仅补齐既有导入历史GET接口的只读权限；不创建业务数据、不绑定任何角色。
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '读取PaiCLI导入历史',m.menu_id,10,'',NULL,1,0,'F','0','0','paicli:import:read','#','paicli-migration',UTC_TIMESTAMP(),'PaiCLI离线导入批次与预检历史只读权限'
FROM sys_menu m WHERE m.path='audit' AND m.perms='paicli:audit:read'
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paicli:import:read');
