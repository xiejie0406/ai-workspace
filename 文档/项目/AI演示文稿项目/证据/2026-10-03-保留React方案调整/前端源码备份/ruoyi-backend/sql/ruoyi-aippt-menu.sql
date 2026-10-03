-- AI 演示文稿菜单增量：只生成脚本，不自动执行；不创建/授权账号，不写 sys_role_menu。

-- sys_menu 自增ID避免碰撞；按路径和权限幂等；执行前审核已有 aippt 路径与角色范围。

START TRANSACTION;

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'AI 演示文稿',0,35,'aippt',NULL,'','Aippt',1,0,'M','0','0','','education','migration',NOW(),'AI演示文稿若依入口' WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=0 AND path='aippt');

SET @aippt_root=(SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='aippt' AND menu_type='M' ORDER BY menu_id LIMIT 1);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '我的演示文稿',@aippt_root,1,'projects','aippt/projects/index','','AipptProjects',1,0,'C','0','0','aippt:project:list','list','migration',NOW(),'AI演示文稿业务页面' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@aippt_root AND path='projects');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '新建演示文稿',@aippt_root,2,'create','aippt/create/index','','AipptCreate',1,0,'C','1','0','aippt:project:add','list','migration',NOW(),'AI演示文稿业务页面' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@aippt_root AND path='create');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '材料与提纲',@aippt_root,3,'detail','aippt/detail/index','','AipptDetail',1,0,'C','1','0','aippt:project:query','list','migration',NOW(),'AI演示文稿业务页面' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@aippt_root AND path='detail');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '演示文稿编辑',@aippt_root,4,'editor','aippt/editor/index','','AipptEditor',1,0,'C','1','0','aippt:deck:query','list','migration',NOW(),'AI演示文稿业务页面' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@aippt_root AND path='editor');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '设计目录',@aippt_root,5,'design','aippt/design/index','','AipptDesign',1,0,'C','0','0','aippt:design:query','list','migration',NOW(),'AI演示文稿业务页面' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@aippt_root AND path='design');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '查看项目',@aippt_root,101,'',NULL,'','',1,0,'F','1','0','aippt:project:query','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:project:query');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '新增项目',@aippt_root,102,'',NULL,'','',1,0,'F','1','0','aippt:project:add','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:project:add');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '修改项目',@aippt_root,103,'',NULL,'','',1,0,'F','1','0','aippt:project:edit','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:project:edit');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '删除项目',@aippt_root,104,'',NULL,'','',1,0,'F','1','0','aippt:project:remove','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:project:remove');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '管理他人项目',@aippt_root,105,'',NULL,'','',1,0,'F','1','0','aippt:project:manage','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:project:manage');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '查看材料',@aippt_root,106,'',NULL,'','',1,0,'F','1','0','aippt:source:query','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:source:query');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '添加材料',@aippt_root,107,'',NULL,'','',1,0,'F','1','0','aippt:source:add','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:source:add');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '删除材料',@aippt_root,108,'',NULL,'','',1,0,'F','1','0','aippt:source:remove','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:source:remove');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '查看提纲',@aippt_root,109,'',NULL,'','',1,0,'F','1','0','aippt:outline:query','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:outline:query');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '生成提纲',@aippt_root,110,'',NULL,'','',1,0,'F','1','0','aippt:outline:generate','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:outline:generate');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '编辑提纲',@aippt_root,111,'',NULL,'','',1,0,'F','1','0','aippt:outline:edit','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:outline:edit');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '确认提纲',@aippt_root,112,'',NULL,'','',1,0,'F','1','0','aippt:outline:confirm','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:outline:confirm');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '查看页面',@aippt_root,113,'',NULL,'','',1,0,'F','1','0','aippt:deck:query','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:deck:query');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '生成页面',@aippt_root,114,'',NULL,'','',1,0,'F','1','0','aippt:deck:generate','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:deck:generate');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '取消生成',@aippt_root,115,'',NULL,'','',1,0,'F','1','0','aippt:deck:cancel','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:deck:cancel');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '编辑页面',@aippt_root,116,'',NULL,'','',1,0,'F','1','0','aippt:slide:edit','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:slide:edit');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '编辑主题',@aippt_root,117,'',NULL,'','',1,0,'F','1','0','aippt:theme:edit','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:theme:edit');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'AI修改',@aippt_root,118,'',NULL,'','',1,0,'F','1','0','aippt:ai:edit','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:ai:edit');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '上传图片',@aippt_root,119,'',NULL,'','',1,0,'F','1','0','aippt:image:upload','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:image:upload');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '质量检查',@aippt_root,120,'',NULL,'','',1,0,'F','1','0','aippt:export:check','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:export:check');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '创建导出',@aippt_root,121,'',NULL,'','',1,0,'F','1','0','aippt:export:create','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:export:create');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '下载导出',@aippt_root,122,'',NULL,'','',1,0,'F','1','0','aippt:export:download','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:export:download');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '查看目录',@aippt_root,123,'',NULL,'','',1,0,'F','1','0','aippt:design:query','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:design:query');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '查看运行状态',@aippt_root,124,'',NULL,'','',1,0,'F','1','0','aippt:operations:query','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:operations:query');

COMMIT;
