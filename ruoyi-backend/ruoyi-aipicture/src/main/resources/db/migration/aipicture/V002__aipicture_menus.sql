-- 沿用若依后台菜单和按钮权限；只新增本项目记录，不创建账号/角色，不自动授权普通角色。
-- 950000–950005、950100–950119 已于 2026-10-03 在原 ry-vue 核对为空；应用前仍须核对冲突。
INSERT INTO sys_menu
 (menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT 950000,'AI 图片编辑',0,30,'aipicture','','','AiPicture',1,0,'M','0','0','','image','aipicture-migration',NOW(),'原 React 业务画布，由若依登录与菜单提供入口'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=950000 OR (parent_id=0 AND path='aipicture'));

INSERT INTO sys_menu
 (menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT v.menu_id,v.menu_name,950000,v.order_num,v.path,v.component,'',v.route_name,1,0,'C','0','0',v.perms,v.icon,'aipicture-migration',NOW(),'AI 图片编辑业务页面'
FROM (
 SELECT 950001 menu_id,'图片创作' menu_name,1 order_num,'create' path,'aipicture/create/index' component,'AiPictureCreate' route_name,'aipicture:image:generate' perms,'picture' icon
 UNION ALL SELECT 950002,'图片工作台',2,'editor','aipicture/editor/index','AiPictureEditor','aipicture:session:list','edit'
 UNION ALL SELECT 950003,'营销物料',3,'marketing','aipicture/marketing/index','AiPictureMarketing','aipicture:session:query','shopping'
 UNION ALL SELECT 950004,'批量处理',4,'batch','aipicture/batch/index','AiPictureBatch','aipicture:batch:list','list'
 UNION ALL SELECT 950005,'能力与评测',5,'evaluation','aipicture/evaluation/index','AiPictureEvaluation','aipicture:evaluation:list','monitor'
) v
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=950000 AND path='aipicture' AND menu_type='M')
 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=v.menu_id OR (parent_id=950000 AND path=v.path));

INSERT INTO sys_menu
 (menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT v.menu_id,v.menu_name,v.parent_id,v.menu_id-950100,'','','','',1,0,'F','0','0',v.perms,'#','aipicture-migration',NOW(),'AI 图片编辑按钮权限'
FROM (
 SELECT 950100 menu_id,'素材列表' menu_name,950002 parent_id,'aipicture:asset:list' perms
 UNION ALL SELECT 950101,'查看素材',950002,'aipicture:asset:query'
 UNION ALL SELECT 950102,'上传素材',950002,'aipicture:asset:upload'
 UNION ALL SELECT 950103,'创建会话',950002,'aipicture:session:create'
 UNION ALL SELECT 950104,'编辑会话',950002,'aipicture:session:edit'
 UNION ALL SELECT 950105,'执行图片工具',950002,'aipicture:tool:execute'
 UNION ALL SELECT 950106,'确认计划',950002,'aipicture:plan:confirm'
 UNION ALL SELECT 950107,'重试计划',950002,'aipicture:plan:retry'
 UNION ALL SELECT 950108,'取消计划',950002,'aipicture:plan:cancel'
 UNION ALL SELECT 950109,'执行批量处理',950004,'aipicture:batch:execute'
 UNION ALL SELECT 950110,'重试批量失败项',950004,'aipicture:batch:retry'
 UNION ALL SELECT 950111,'取消批量处理',950004,'aipicture:batch:cancel'
 UNION ALL SELECT 950112,'下载导出',950002,'aipicture:export:download'
 UNION ALL SELECT 950113,'执行评测',950005,'aipicture:evaluation:execute'
 UNION ALL SELECT 950114,'查询能力状态',950005,'aipicture:capability:query'
) v
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=v.parent_id AND parent_id=950000 AND menu_type='C')
 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=v.menu_id OR perms=v.perms);
