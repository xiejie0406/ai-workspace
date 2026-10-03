-- AI 演示文稿菜单增量：只生成脚本，不自动执行；不创建/授权账号，不写 sys_role_menu。

-- 先由部署方核定与平台完全同源的公开 origin（协议、主机、端口）；不得从开发端口推定。
-- 将下一行 NULL 显式替换为已批准 origin，不含路径、末尾斜杠、查询、片段或凭据。
-- SQL 无法判断浏览器实际 origin；同源和静态托管/深链回退须部署审核。默认全部菜单 DML 不写入。
SET @aippt_public_origin = NULL;
SET @aippt_origin_valid = COALESCE(
    @aippt_public_origin REGEXP '^https?://[^/[:space:]?#@]+$'
    AND (BINARY LEFT(@aippt_public_origin, 7) = BINARY 'http://' OR BINARY LEFT(@aippt_public_origin, 8) = BINARY 'https://')
    AND LOCATE(CHAR(92), @aippt_public_origin) = 0
    AND CHAR_LENGTH(CONCAT(@aippt_public_origin, '/aippt-web/projects')) <= 200,
    0
);
SET @aippt_projects_url = CONCAT(@aippt_public_origin, '/aippt-web/projects');
SET @aippt_create_url = CONCAT(@aippt_public_origin, '/aippt-web/create');
SELECT IF(@aippt_origin_valid, '同源 origin 格式守卫通过；仍须审核角色与部署', '未核定有效同源 origin；本脚本菜单 DML 均不写入') AS aippt_menu_plan;

-- sys_menu 自增ID避免碰撞；保留已有菜单 ID/角色关系；绝对 HTTP(S) 外链须 is_frame=0。
-- is_frame=1 会被 getRouterPath 转为 InnerLink，不能用于此独立 React 工作台。

START TRANSACTION;

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'AI 演示文稿',0,35,'aippt',NULL,'','Aippt',1,0,'M','0','0','','education','migration',NOW(),'AI演示文稿若依入口' WHERE @aippt_origin_valid=1 AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=0 AND path='aippt');

SET @aippt_root=(SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='aippt' AND menu_type='M' ORDER BY menu_id LIMIT 1);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '我的演示文稿',@aippt_root,1,@aippt_projects_url,NULL,'','AipptProjects',0,0,'C','0','0','aippt:project:list','list','migration',NOW(),'原React工作台授权外链' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@aippt_root AND (path IN ('projects',@aippt_projects_url) OR route_name='AipptProjects'));

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '新建演示文稿',@aippt_root,2,@aippt_create_url,NULL,'','AipptCreate',0,0,'C','1','0','aippt:project:add','list','migration',NOW(),'原React工作台授权外链' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@aippt_root AND (path IN ('create',@aippt_create_url) OR route_name='AipptCreate'));




INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '查看项目',@aippt_root,101,'',NULL,'','',1,0,'F','1','0','aippt:project:query','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:project:query');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '新增项目',@aippt_root,102,'',NULL,'','',1,0,'F','1','0','aippt:project:add','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:project:add');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '修改项目',@aippt_root,103,'',NULL,'','',1,0,'F','1','0','aippt:project:edit','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:project:edit');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '删除项目',@aippt_root,104,'',NULL,'','',1,0,'F','1','0','aippt:project:remove','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:project:remove');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '管理他人项目',@aippt_root,105,'',NULL,'','',1,0,'F','1','0','aippt:project:manage','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:project:manage');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '查看材料',@aippt_root,106,'',NULL,'','',1,0,'F','1','0','aippt:source:query','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:source:query');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '添加材料',@aippt_root,107,'',NULL,'','',1,0,'F','1','0','aippt:source:add','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:source:add');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '删除材料',@aippt_root,108,'',NULL,'','',1,0,'F','1','0','aippt:source:remove','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:source:remove');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '查看提纲',@aippt_root,109,'',NULL,'','',1,0,'F','1','0','aippt:outline:query','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:outline:query');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '生成提纲',@aippt_root,110,'',NULL,'','',1,0,'F','1','0','aippt:outline:generate','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:outline:generate');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '编辑提纲',@aippt_root,111,'',NULL,'','',1,0,'F','1','0','aippt:outline:edit','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:outline:edit');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '确认提纲',@aippt_root,112,'',NULL,'','',1,0,'F','1','0','aippt:outline:confirm','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:outline:confirm');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '查看页面',@aippt_root,113,'',NULL,'','',1,0,'F','1','0','aippt:deck:query','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:deck:query');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '生成页面',@aippt_root,114,'',NULL,'','',1,0,'F','1','0','aippt:deck:generate','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:deck:generate');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '取消生成',@aippt_root,115,'',NULL,'','',1,0,'F','1','0','aippt:deck:cancel','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:deck:cancel');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '编辑页面',@aippt_root,116,'',NULL,'','',1,0,'F','1','0','aippt:slide:edit','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:slide:edit');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '编辑主题',@aippt_root,117,'',NULL,'','',1,0,'F','1','0','aippt:theme:edit','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:theme:edit');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT 'AI修改',@aippt_root,118,'',NULL,'','',1,0,'F','1','0','aippt:ai:edit','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:ai:edit');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '上传图片',@aippt_root,119,'',NULL,'','',1,0,'F','1','0','aippt:image:upload','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:image:upload');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '质量检查',@aippt_root,120,'',NULL,'','',1,0,'F','1','0','aippt:export:check','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:export:check');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '创建导出',@aippt_root,121,'',NULL,'','',1,0,'F','1','0','aippt:export:create','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:export:create');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '下载导出',@aippt_root,122,'',NULL,'','',1,0,'F','1','0','aippt:export:download','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:export:download');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '查看目录',@aippt_root,123,'',NULL,'','',1,0,'F','1','0','aippt:design:query','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:design:query');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) SELECT '查看运行状态',@aippt_root,124,'',NULL,'','',1,0,'F','1','0','aippt:operations:query','#','migration',NOW(),'AI演示文稿操作权限' WHERE @aippt_origin_valid=1 AND @aippt_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aippt:operations:query');

-- 保留现有菜单 ID 与角色关联，只切换原 React 入口；该候选尚未执行。
UPDATE sys_menu
SET path=CASE WHEN path='create' OR route_name='AipptCreate' THEN @aippt_create_url ELSE @aippt_projects_url END,
    component=NULL, query='', is_frame='0', is_cache=0, remark='原React工作台授权外链'
WHERE @aippt_origin_valid=1 AND parent_id=@aippt_root AND menu_type='C'
  AND (
    (path IN ('projects','create') AND component IN ('aippt/projects/index','aippt/create/index','aippt-entry/index'))
    OR (route_name IN ('AipptProjects','AipptCreate') AND (component IS NULL OR component='' OR component='aippt-entry/index'))
  );

-- 材料、提纲、编辑、主题和目录继续位于原 React 项目详情/新建流程，不再注册重写页面。
-- 旧候选独立页面仅停用，保留 ID、角色关系和所有业务 F 权限供审核/恢复。
UPDATE sys_menu SET visible='1', status='1', remark='原Vue独立页已废止；业务在原React页面保留' WHERE @aippt_origin_valid=1 AND parent_id=@aippt_root AND menu_type='C' AND path IN ('detail','editor','design') AND component IN ('aippt/detail/index','aippt/editor/index','aippt/design/index');

COMMIT;
