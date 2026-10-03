/** 业务导航只作用于智能选品，不写入若依全局布局偏好。 */
export const fashionMenus = [
  { title: '商品资料', path: '/fashion/product', permission: 'fashion:product:list' },
  { title: '资料导入', path: '/fashion/import', permission: 'fashion:product:import' },
  { title: '仓库管理', path: '/fashion/warehouse', permission: 'fashion:stock:list' },
  { title: '库存中心', path: '/fashion/stock', permission: 'fashion:stock:list' },
  { title: '客户需求', path: '/fashion/customer', permission: 'fashion:customer:list' },
  { title: '选品方案', path: '/fashion/pool', permission: 'fashion:quote:list' },
  { title: 'AI任务', path: '/fashion/tasks', permission: 'fashion:ai:run:list', alternatePermission: 'fashion:quote:query' },
  { title: '提案交付', path: '/fashion/delivery', permission: 'fashion:quote:export' }
] as const;

export const fashionSettingsMenus = [
  { title: '业务设置', path: '/fashion/settings', permission: 'fashion:settings:list' },
  { title: 'Agent版本', path: '/fashion/agent', permission: 'fashion:ai:agent:list' },
  { title: '运行日志', path: '/fashion/operations', permission: 'fashion:operations:query' }
] as const;

export function isFashionPath(path: string): boolean {
  return path === '/fashion' || path.startsWith('/fashion/');
}

export function fashionActivePath(path: string): string {
  if (path.startsWith('/fashion/material') || path.startsWith('/fashion/price-import')) return '/fashion/product';
  if (path.startsWith('/fashion/workbench') || path.startsWith('/fashion/image')) return '/fashion/tasks';
  if (path.startsWith('/fashion/quote')) return '/fashion/pool';
  return [...fashionMenus, ...fashionSettingsMenus].find(menu => path === menu.path || path.startsWith(menu.path + '/'))?.path || path;
}

export function visibleFashionMenus<T extends { permission: string; alternatePermission?: string }>(menus: readonly T[], permissions: readonly string[]): T[] {
  return menus.filter(menu => permissions.includes('*:*:*') || permissions.includes(menu.permission) || Boolean(menu.alternatePermission && permissions.includes(menu.alternatePermission)));
}
