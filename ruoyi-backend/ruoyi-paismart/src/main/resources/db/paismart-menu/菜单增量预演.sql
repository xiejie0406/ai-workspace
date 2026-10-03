-- 审核文件：未执行。使用 sys_menu AUTO_INCREMENT，沿用 getRouters 与 navType 1/2/3。
-- 不创建角色、账号或授权。执行前导出现有菜单；父目录 path 冲突时整个增量拒绝挂接。
START TRANSACTION;
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT 'PaiSmart 知识库',0,40,'paismart',NULL,'PaiSmart',1,0,'M','0','0','','documentation','paismart-migration-v1',CURRENT_TIMESTAMP,'完整迁移后台授权树'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=0 AND path='paismart');
SET @ps_root=(SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='paismart' AND menu_type='M' AND create_by='paismart-migration-v1');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '文档知识库',@ps_root,1,'documents','paismart/documents/index','PaiSmartDocuments',1,1,'C','0','0','paismart:document:list','list','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 业务页面'
WHERE @ps_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@ps_root AND path='documents');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '阅读原文',m.menu_id,1,'',1,0,'F','0','0','paismart:document:read','#','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 按钮权限'
FROM sys_menu m WHERE m.parent_id=@ps_root AND m.path='documents' AND m.create_by='paismart-migration-v1'
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paismart:document:read');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '上传文档',m.menu_id,1,'',1,0,'F','0','0','paismart:document:upload','#','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 按钮权限'
FROM sys_menu m WHERE m.parent_id=@ps_root AND m.path='documents' AND m.create_by='paismart-migration-v1'
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paismart:document:upload');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '删除与恢复文档',m.menu_id,1,'',1,0,'F','0','0','paismart:document:remove','#','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 按钮权限'
FROM sys_menu m WHERE m.parent_id=@ps_root AND m.path='documents' AND m.create_by='paismart-migration-v1'
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paismart:document:remove');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '重新处理文档',m.menu_id,1,'',1,0,'F','0','0','paismart:document:reindex','#','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 按钮权限'
FROM sys_menu m WHERE m.parent_id=@ps_root AND m.path='documents' AND m.create_by='paismart-migration-v1'
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paismart:document:reindex');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '混合检索',m.menu_id,1,'',1,0,'F','0','0','paismart:search:query','#','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 按钮权限'
FROM sys_menu m WHERE m.parent_id=@ps_root AND m.path='documents' AND m.create_by='paismart-migration-v1'
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paismart:search:query');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '知识问答',@ps_root,2,'chat','paismart/chat/index','PaiSmartChat',1,1,'C','0','0','paismart:chat:send','list','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 业务页面'
WHERE @ps_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@ps_root AND path='chat');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '会话历史',@ps_root,3,'history','paismart/history/index','PaiSmartHistory',1,1,'C','0','0','paismart:conversation:list','list','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 业务页面'
WHERE @ps_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@ps_root AND path='history');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '会话审计',m.menu_id,1,'',1,0,'F','0','0','paismart:conversation:audit','#','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 按钮权限'
FROM sys_menu m WHERE m.parent_id=@ps_root AND m.path='history' AND m.create_by='paismart-migration-v1'
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paismart:conversation:audit');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '知识空间',@ps_root,4,'spaces','paismart/spaces/index','PaiSmartSpaces',1,1,'C','0','0','paismart:space:list','list','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 业务页面'
WHERE @ps_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@ps_root AND path='spaces');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '编辑空间',m.menu_id,1,'',1,0,'F','0','0','paismart:space:edit','#','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 按钮权限'
FROM sys_menu m WHERE m.parent_id=@ps_root AND m.path='spaces' AND m.create_by='paismart-migration-v1'
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paismart:space:edit');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '成员与资格',@ps_root,5,'members','paismart/members/index','PaiSmartMembers',1,1,'C','0','0','paismart:space:member','list','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 业务页面'
WHERE @ps_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@ps_root AND path='members');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '调整额度与策略',m.menu_id,1,'',1,0,'F','0','0','paismart:usage:adjust','#','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 按钮权限'
FROM sys_menu m WHERE m.parent_id=@ps_root AND m.path='members' AND m.create_by='paismart-migration-v1'
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paismart:usage:adjust');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '知识冲突',@ps_root,6,'conflicts','paismart/governance/conflicts/index','PaiSmartConflicts',1,1,'C','0','0','paismart:conflict:list','list','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 业务页面'
WHERE @ps_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@ps_root AND path='conflicts');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '处理冲突',m.menu_id,1,'',1,0,'F','0','0','paismart:conflict:resolve','#','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 按钮权限'
FROM sys_menu m WHERE m.parent_id=@ps_root AND m.path='conflicts' AND m.create_by='paismart-migration-v1'
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paismart:conflict:resolve');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '索引治理',@ps_root,7,'indexes','paismart/governance/indexes/index','PaiSmartIndexes',1,1,'C','0','0','paismart:index:list','list','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 业务页面'
WHERE @ps_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@ps_root AND path='indexes');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '重建索引',m.menu_id,1,'',1,0,'F','0','0','paismart:index:rebuild','#','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 按钮权限'
FROM sys_menu m WHERE m.parent_id=@ps_root AND m.path='indexes' AND m.create_by='paismart-migration-v1'
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paismart:index:rebuild');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '回退与恢复索引',m.menu_id,1,'',1,0,'F','0','0','paismart:index:rollback','#','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 按钮权限'
FROM sys_menu m WHERE m.parent_id=@ps_root AND m.path='indexes' AND m.create_by='paismart-migration-v1'
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paismart:index:rollback');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '检索评测',@ps_root,8,'evaluations','paismart/governance/evaluations/index','PaiSmartEvaluations',1,1,'C','0','0','paismart:eval:list','list','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 业务页面'
WHERE @ps_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@ps_root AND path='evaluations');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '执行评测',m.menu_id,1,'',1,0,'F','0','0','paismart:eval:run','#','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 按钮权限'
FROM sys_menu m WHERE m.parent_id=@ps_root AND m.path='evaluations' AND m.create_by='paismart-migration-v1'
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paismart:eval:run');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '人工复核',@ps_root,9,'review','paismart/governance/evaluations/review','PaiSmartReview',1,1,'C','0','0','paismart:eval:review','list','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 业务页面'
WHERE @ps_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@ps_root AND path='review');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '用量与流水',@ps_root,10,'usage','paismart/usage/index','PaiSmartUsage',1,1,'C','0','0','paismart:usage:list','list','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 业务页面'
WHERE @ps_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@ps_root AND path='usage');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '审计用量',m.menu_id,1,'',1,0,'F','0','0','paismart:usage:audit','#','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 按钮权限'
FROM sys_menu m WHERE m.parent_id=@ps_root AND m.path='usage' AND m.create_by='paismart-migration-v1'
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paismart:usage:audit');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '充值与订单',@ps_root,11,'recharge','paismart/recharge/index','PaiSmartRecharge',1,1,'C','0','0','paismart:recharge:create','list','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 业务页面'
WHERE @ps_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@ps_root AND path='recharge');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '模型供应商',@ps_root,12,'providers','paismart/operations/providers/index','PaiSmartProviders',1,1,'C','0','0','paismart:provider:list','list','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 业务页面'
WHERE @ps_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@ps_root AND path='providers');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '编辑供应商',m.menu_id,1,'',1,0,'F','0','0','paismart:provider:edit','#','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 按钮权限'
FROM sys_menu m WHERE m.parent_id=@ps_root AND m.path='providers' AND m.create_by='paismart-migration-v1'
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paismart:provider:edit');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '测试供应商',m.menu_id,1,'',1,0,'F','0','0','paismart:provider:test','#','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 按钮权限'
FROM sys_menu m WHERE m.parent_id=@ps_root AND m.path='providers' AND m.create_by='paismart-migration-v1'
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paismart:provider:test');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '限流策略',@ps_root,13,'limits','paismart/operations/limits/index','PaiSmartLimits',1,1,'C','0','0','paismart:usage:adjust','list','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 业务页面'
WHERE @ps_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@ps_root AND path='limits');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '充值套餐',@ps_root,14,'packages','paismart/operations/packages/index','PaiSmartPackages',1,1,'C','0','0','paismart:recharge:manage','list','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 业务页面'
WHERE @ps_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@ps_root AND path='packages');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '邀请码',@ps_root,15,'invites','paismart/operations/invites/index','PaiSmartInvites',1,1,'C','0','0','paismart:invite:manage','list','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 业务页面'
WHERE @ps_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@ps_root AND path='invites');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '系统作业与审计',@ps_root,16,'jobs','paismart/operations/jobs/index','PaiSmartJobs',1,1,'C','0','0','paismart:operation:plan','list','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 业务页面'
WHERE @ps_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@ps_root AND path='jobs');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '审批与执行系统计划',m.menu_id,1,'',1,0,'F','0','0','paismart:operation:execute','#','paismart-migration-v1',CURRENT_TIMESTAMP,'Paismart 按钮权限'
FROM sys_menu m WHERE m.parent_id=@ps_root AND m.path='jobs' AND m.create_by='paismart-migration-v1'
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='paismart:operation:execute');

SELECT CASE WHEN @ps_root IS NULL THEN 'BLOCKED_PARENT_CONFLICT' ELSE 'REVIEW_ROWS_BEFORE_COMMIT' END readiness;
SELECT menu_id,menu_name,parent_id,path,component,perms FROM sys_menu WHERE create_by='paismart-migration-v1' ORDER BY parent_id,order_num;
-- 仅在输出逐条符合已批准菜单清单后提交；默认脚本结尾回滚，审阅者显式选择 COMMIT。
ROLLBACK;

