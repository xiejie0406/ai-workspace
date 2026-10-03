import type { Layout, Theme } from './types'
export const layouts = new Map<string, Layout>()
export const themes = new Map<string, Theme>()
export const themeList: Theme[] = []
export let catalogVersion = ''
export function registerCatalog(catalog: { layouts: Layout[]; themes: Theme[]; catalog_version?: string; catalogVersion?: string }) {
  layouts.clear(); themes.clear(); themeList.splice(0)
  for (const item of catalog.layouts) layouts.set(item.id, item)
  for (const item of catalog.themes) { themes.set(item.id, item); themeList.push(item) }
  catalogVersion = catalog.catalog_version || catalog.catalogVersion || ''
}
export function getLayout(id: string): Layout { const item = layouts.get(id); if (!item) throw new Error(`未知布局：${id}`); return item }
export function getTheme(id: string): Theme { const item = themes.get(id); if (!item) throw new Error(`未知主题：${id}`); return item }
