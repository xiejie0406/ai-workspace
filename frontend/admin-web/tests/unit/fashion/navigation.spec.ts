import { describe, it, expect } from 'vitest';
import { fashionMenus, visibleFashionMenus, fashionActivePath, isFashionPath } from '../../../src/layout/components/FashionNavigation/menu';

describe('智能选品导航边界', () => {
  it('八个业务菜单包含仓库，并把图片与价格收纳到商品', () => {
    expect(fashionMenus.map(x => x.title)).toEqual(['商品资料','资料导入','仓库管理','库存中心','客户需求','选品方案','AI任务','提案交付']);
    expect(fashionActivePath('/fashion/material')).toBe('/fashion/product');
    expect(fashionActivePath('/fashion/price-import')).toBe('/fashion/product');
  });
  it('按原权限呈现菜单，库存读权限同时支持仓库查看', () => {
    expect(visibleFashionMenus(fashionMenus, ['fashion:stock:list']).map(x => x.title)).toEqual(['仓库管理','库存中心']);
    expect(visibleFashionMenus(fashionMenus, [])).toEqual([]);
    expect(visibleFashionMenus(fashionMenus, ['*:*:*'])).toHaveLength(8);
    expect(visibleFashionMenus(fashionMenus, ['fashion:quote:query']).map(x => x.title)).toEqual(['AI任务']);
  });
  it('只匹配本项目且保留旧深链的业务定位', () => {
    expect(isFashionPath('/aps/workbench')).toBe(false);
    expect(isFashionPath('/fashionable')).toBe(false);
    expect(isFashionPath('/fashion/pool')).toBe(true);
    expect(fashionActivePath('/fashion/quote/old')).toBe('/fashion/pool');
  });
});
