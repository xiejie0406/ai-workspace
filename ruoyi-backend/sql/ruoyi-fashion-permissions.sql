-- FEAT-FASHION-001 权限与设置菜单增量。
-- 只写 sys_menu；不创建账号、角色，也不写 sys_role_menu。
-- 920000～920038 是当前 Fashion 保留段；ID 冲突时跳过并由管理员先处理冲突。

insert into sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time,
     update_by, update_time, remark)
select 920000, '智能选品', 0, 20, 'fashion', null, '', 'Fashion',
       1, 0, 'M', '0', '0', '', 'shopping', 'migration', now(), '', null,
       'FEAT-FASHION-001 root menu'
where not exists (select 1 from sys_menu where menu_id = 920000)
  and not exists (select 1 from sys_menu where parent_id = 0 and path = 'fashion');

insert into sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time,
     update_by, update_time, remark)
select 920001, '系统设置', 920000, 90, 'settings', 'fashion/settings/index', '', 'FashionSettings',
       1, 0, 'C', '0', '0', 'fashion:settings:list', 'edit', 'migration', now(), '', null,
       'Fashion typed settings'
where exists (select 1 from sys_menu where menu_id = 920000 and path = 'fashion')
  and not exists (select 1 from sys_menu where menu_id = 920001)
  and not exists (select 1 from sys_menu where perms = 'fashion:settings:list');

insert into sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time,
     update_by, update_time, remark)
select v.menu_id, v.menu_name, 920000, v.order_num, v.path, v.component, '', v.route_name,
       1, 0, 'C', '0', '0', v.perms, v.icon, 'migration', now(), '', null, 'FEAT-FASHION-001 page'
from (
    select 920003 menu_id, '客户中心' menu_name, 5 order_num, 'customer' path,
           'fashion/customer/index' component, 'FashionCustomer' route_name,
           'fashion:customer:list' perms, 'peoples' icon
    union all select 920015, '方案管理', 45, 'quote', 'fashion/quote/index',
           'FashionQuote', 'fashion:quote:list', 'list'
    union all select 920024, '方案工作台', 50, 'workbench', 'fashion/workbench/index',
           'FashionWorkbench', 'fashion:ai:run:list', 'guide'
    union all select 920032, '图片工作台', 55, 'image', 'fashion/image/index',
           'FashionImage', 'fashion:image:list', 'picture-filled'
    union all select 920021, 'Agent 版本', 60, 'agent', 'fashion/agent/index',
           'FashionAgent', 'fashion:ai:agent:list', 'tree-table'
    union all select 920034, '运行与保留', 80, 'operations', 'fashion/operations/index',
           'FashionOperations', 'fashion:operations:query', 'monitor'
    union all
    select 920007 menu_id, '商品中心' menu_name, 10 order_num, 'product' path,
           'fashion/product/index' component, 'FashionProduct' route_name,
           'fashion:product:list' perms, 'shopping' icon
    union all select 920009, '商品导入', 20, 'import', 'fashion/import/index',
           'FashionProductImport', 'fashion:product:import', 'upload'
    union all select 920011, '图片素材', 30, 'material', 'fashion/material/index',
           'FashionProductMaterial', 'fashion:product:image', 'picture'
    union all select 920030, '价格全量更新', 35, 'price-import', 'fashion/import/price/index',
           'FashionPriceImport', 'fashion:price:import', 'money'
) v
where exists (select 1 from sys_menu where menu_id = 920000 and path = 'fashion')
  and not exists (select 1 from sys_menu m where m.menu_id = v.menu_id)
  and not exists (select 1 from sys_menu m where m.parent_id = 920000 and m.path = v.path);

-- 兼容 IMP-02 已先执行：把本脚本创建的同 ID 功能权限提升为页面，不新增重复权限。
update sys_menu set menu_name='商品中心',parent_id=920000,order_num=10,path='product',
       component='fashion/product/index',route_name='FashionProduct',menu_type='C',visible='0',icon='shopping'
 where menu_id=920007 and create_by='migration' and perms='fashion:product:list';
update sys_menu set menu_name='商品导入',parent_id=920000,order_num=20,path='import',
       component='fashion/import/index',route_name='FashionProductImport',menu_type='C',visible='0',icon='upload'
 where menu_id=920009 and create_by='migration' and perms='fashion:product:import';
update sys_menu set menu_name='图片素材',parent_id=920000,order_num=30,path='material',
       component='fashion/material/index',route_name='FashionProductMaterial',menu_type='C',visible='0',icon='picture'
 where menu_id=920011 and create_by='migration' and perms='fashion:product:image';
update sys_menu set menu_name='客户中心',parent_id=920000,order_num=5,path='customer',
       component='fashion/customer/index',route_name='FashionCustomer',menu_type='C',visible='0',icon='peoples'
 where menu_id=920003 and create_by='migration' and perms='fashion:customer:list';
update sys_menu set menu_name='方案管理',parent_id=920000,order_num=45,path='quote',
       component='fashion/quote/index',route_name='FashionQuote',menu_type='C',visible='0',icon='list'
 where menu_id=920015 and create_by='migration' and perms='fashion:quote:list';
update sys_menu set menu_name='Agent 版本',parent_id=920000,order_num=60,path='agent',
       component='fashion/agent/index',route_name='FashionAgent',menu_type='C',visible='0',icon='tree-table'
 where menu_id=920021 and create_by='migration' and perms='fashion:ai:agent:list';
update sys_menu set menu_name='方案工作台',parent_id=920000,order_num=50,path='workbench',
       component='fashion/workbench/index',route_name='FashionWorkbench',menu_type='C',visible='0',icon='guide'
 where menu_id=920024 and create_by='migration' and perms='fashion:ai:run:list';
insert into sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time,
     update_by, update_time, remark)
select v.menu_id, v.menu_name, v.parent_id, v.order_num, '', '', '', '', 1, 0, 'F', '1', '0',
       v.perms, '#', 'migration', now(), '', null, 'FEAT-FASHION-001 permission'
from (
    select 920002 menu_id, '设置修改' menu_name, 920001 parent_id, 1 order_num, 'fashion:settings:edit' perms
    union all select 920003, '客户查看', 0, 2, 'fashion:customer:list'
    union all select 920004, '客户详情', 0, 3, 'fashion:customer:query'
    union all select 920005, '客户新增', 0, 4, 'fashion:customer:add'
    union all select 920006, '客户修改', 0, 5, 'fashion:customer:edit'
    union all select 920007, '商品查看', 0, 6, 'fashion:product:list'
    union all select 920008, '商品详情', 0, 7, 'fashion:product:query'
    union all select 920009, '商品导入', 0, 8, 'fashion:product:import'
    union all select 920010, '商品修改', 0, 9, 'fashion:product:edit'
    union all select 920011, '商品图片', 0, 10, 'fashion:product:image'
    union all select 920012, '库存查看', 0, 11, 'fashion:stock:list'
    union all select 920013, '库存导入', 0, 12, 'fashion:stock:import'
    union all select 920014, '库存核实', 0, 13, 'fashion:stock:verify'
    union all select 920015, '报价查看', 0, 14, 'fashion:quote:list'
    union all select 920016, '报价详情', 0, 15, 'fashion:quote:query'
    union all select 920017, '报价新增', 0, 16, 'fashion:quote:add'
    union all select 920018, '报价修改', 0, 17, 'fashion:quote:edit'
    union all select 920019, '报价确认', 0, 18, 'fashion:quote:confirm'
    union all select 920020, '报价导出', 0, 19, 'fashion:quote:export'
    union all select 920021, 'Agent 查看', 0, 20, 'fashion:ai:agent:list'
    union all select 920022, 'Agent 修改', 0, 21, 'fashion:ai:agent:edit'
    union all select 920023, 'Agent 发布', 0, 22, 'fashion:ai:agent:publish'
    union all select 920024, 'Run 查看', 0, 23, 'fashion:ai:run:list'
    union all select 920025, 'Run 执行', 0, 24, 'fashion:ai:run:execute'
    union all select 920026, 'Run 取消', 0, 25, 'fashion:ai:run:cancel'
    union all select 920027, '需求建议采用', 0, 26, 'fashion:ai:run:apply'
    union all select 920028, '图片任务创建', 0, 27, 'fashion:image:create'
    union all select 920029, '图片审核', 0, 28, 'fashion:image:review'
    union all select 920030, '价格全量更新', 920000, 29, 'fashion:price:import'
    union all select 920031, '导入历史恢复', 920000, 30, 'fashion:import:restore'
    union all select 920033, '商务例外批准', 920015, 31, 'fashion:quote:approve'
    union all select 920035, '保留策略管理', 920034, 32, 'fashion:operations:retention'
) v
where not exists (select 1 from sys_menu m where m.perms = v.perms)
  and not exists (select 1 from sys_menu m where m.menu_id = v.menu_id);

-- 若 IMP-02 已先执行，下面只整理本脚本创建的功能权限父级，不改管理员自建菜单。
update sys_menu set parent_id = 920007
 where create_by = 'migration' and perms in ('fashion:product:query','fashion:product:edit');
update sys_menu set menu_name='库存中心',parent_id=920000,order_num=40,path='stock',
       component='fashion/stock/index',route_name='FashionStock',menu_type='C',visible='0',icon='storage'
 where menu_id=920012 and create_by='migration' and perms='fashion:stock:list';
update sys_menu set parent_id = 920012
 where create_by = 'migration' and perms in ('fashion:stock:import','fashion:stock:verify');
update sys_menu set parent_id = 920003
 where create_by = 'migration' and perms in ('fashion:customer:query','fashion:customer:add','fashion:customer:edit');
update sys_menu set parent_id = 920015
 where create_by = 'migration' and perms in ('fashion:quote:query','fashion:quote:add','fashion:quote:edit','fashion:quote:confirm','fashion:quote:approve');
update sys_menu set parent_id = 920021
 where create_by = 'migration' and perms in ('fashion:ai:agent:edit','fashion:ai:agent:publish');
update sys_menu set menu_name='Run 执行',perms='fashion:ai:run:execute'
 where menu_id=920025 and create_by='migration' and perms='fashion:ai:run:query';
update sys_menu set menu_name='需求建议采用',perms='fashion:ai:run:apply'
 where menu_id=920027 and create_by='migration' and perms='fashion:ai:run:approve';
update sys_menu set parent_id = 920024
 where create_by = 'migration' and perms in ('fashion:ai:run:execute','fashion:ai:run:cancel','fashion:ai:run:apply');
update sys_menu set menu_name='交付中心',parent_id=920000,order_num=56,path='delivery',
       component='fashion/delivery/index',route_name='FashionDelivery',menu_type='C',visible='0',icon='download'
 where menu_id=920020 and create_by='migration' and perms='fashion:quote:export';
update sys_menu set parent_id=920034
 where create_by='migration' and perms='fashion:operations:retention';

-- 管理员必须在 RuoYi 中显式把权限授给具体角色；本脚本不扩大任何人员范围。

-- 设计1.2工作区：只调整本脚本菜单。独立图片/价格/旧报价等保持路由与权限兼容，收进商品或设置入口。
-- 本次仅提交脚本文本，未对任何数据库执行；不会自动授予角色或取消旧业务数据权限。
update sys_menu set menu_name='商品资料',order_num=10 where menu_id=920007 and create_by='migration' and perms='fashion:product:list';
update sys_menu set menu_name='资料导入',order_num=20 where menu_id=920009 and create_by='migration' and perms='fashion:product:import';
update sys_menu set menu_name='客户需求',order_num=50 where menu_id=920003 and create_by='migration' and perms='fashion:customer:list';
update sys_menu set menu_name='提案交付',order_num=80 where menu_id=920020 and create_by='migration' and perms='fashion:quote:export';
update sys_menu set visible='1' where create_by='migration' and parent_id=920000 and (
 (menu_id=920011 and path='material' and perms='fashion:product:image') or
 (menu_id=920030 and path='price-import' and perms='fashion:price:import') or
 (menu_id=920015 and path='quote' and perms='fashion:quote:list') or
 (menu_id=920024 and path='workbench' and perms='fashion:ai:run:list') or
 (menu_id=920032 and path='image' and perms='fashion:image:list'));
update sys_menu set menu_name='库存中心',order_num=40,path='stock',component='fashion/stock/index',route_name='FashionStock'
 where menu_id=920012 and parent_id=920000 and create_by='migration' and perms='fashion:stock:list' and path in ('stock-import','stock');
-- 设计1.3：管理入口由若依菜单树保留，取消独立导航后仍可按原权限访问。
update sys_menu set visible='0',order_num=90 where create_by='migration' and menu_id=920001 and parent_id=920000 and path='settings' and perms='fashion:settings:list';
update sys_menu set visible='0',order_num=91 where create_by='migration' and menu_id=920021 and parent_id=920000 and path='agent' and perms='fashion:ai:agent:list';
update sys_menu set visible='0',order_num=92 where create_by='migration' and menu_id=920034 and parent_id=920000 and path='operations' and perms='fashion:operations:query';

insert into sys_menu
(menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark)
select v.id,v.title,920000,v.ord,v.path,v.component,'',v.route_name,1,0,'C','0','0',v.perms,v.icon,'migration',now(),'',null,'设计1.2：复用现有业务权限，不自动授权'
from (
 select 920036 id,'仓库管理' title,30 ord,'warehouse' path,'fashion/warehouse/index' component,'FashionWarehouse' route_name,'fashion:stock:list' perms,'storage' icon
 union all select 920037,'选品方案',60,'pool','fashion/pool/index','FashionSelectionWorkspace','fashion:quote:list','list'
 union all select 920038,'AI任务',70,'tasks','fashion/tasks/index','FashionTasks','fashion:ai:run:list','guide'
) v
where exists(select 1 from sys_menu where menu_id=920000 and path='fashion')
 and not exists(select 1 from sys_menu where menu_id=v.id)
 and not exists(select 1 from sys_menu where parent_id=920000 and path=v.path);
