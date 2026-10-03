import { expect, test } from '@playwright/test'

test('资源页按资源去重并展示多技能与绝对日历', async ({ page }) => {
  const consoleErrors: string[] = []
  const pageErrors: string[] = []
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()) })
  page.on('pageerror', error => pageErrors.push(error.message))

  await page.route('**/*', async route => {
    if (!route.request().url().includes('/api/aps/v1/resources')) return route.continue()
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/workshops')) return route.fulfill({ json: [{ id: 'w1', code: 'WS-1', name: '一号车间', status: 'ACTIVE', rowVersion: 0 }] })
    if (path.endsWith('/workshops/w1/centers')) return route.fulfill({ json: [{ id: 'c1', workshopId: 'w1', code: 'C-1', name: '焊接中心', centerType: 'LABOR', concurrentCapacity: 1, capacityUomCode: 'COUNT', status: 'ACTIVE', rowVersion: 0 }] })
    if (path.endsWith('/r1/skills')) return route.fulfill({ json: [
      { id: 's1', resourceId: 'r1', code: 'WELD', name: '焊接', level: 3, status: 'ACTIVE', rowVersion: 0 },
      { id: 's2', resourceId: 'r1', code: 'QC', name: '质检', level: 4, status: 'ACTIVE', rowVersion: 0 }
    ] })
    if (path.endsWith('/r1/availability')) return route.fulfill({ json: [
      { id: 'a1', resourceId: 'r1', type: 'AVAILABLE', startAt: '2026-09-14T00:00:00.000Z', endAt: '2026-09-14T08:00:00.000Z', capacityRatio: 1, sourceType: 'MANUAL', rowVersion: 0 }
    ] })
    if (path.endsWith('/resources')) return route.fulfill({ json: [
      { id: 'r1', workshopId: 'w1', workCenterId: 'c1', code: 'P-1', name: '张三', type: 'PERSON', capacityValue: 1, capacityUomCode: 'COUNT', status: 'ACTIVE', rowVersion: 0 },
      { id: 'r1', workshopId: 'w1', workCenterId: 'c1', code: 'P-1', name: '张三', type: 'PERSON', capacityValue: 1, capacityUomCode: 'COUNT', status: 'ACTIVE', rowVersion: 0 }
    ] })
    return route.fulfill({ status: 404, json: {} })
  })

  await page.goto('/tests/e2e/aps/fixtures/resource.html')
  await expect(page.getByText('WS-1 · 一号车间').first()).toBeVisible()
  await expect(page.getByText('张三')).toHaveCount(1)
  await page.getByText('张三').click()
  await expect(page.getByText('WELD')).toBeVisible()
  await expect(page.getByText('QC')).toBeVisible()
  await expect(page.getByText('AVAILABLE')).toBeVisible()
  expect(consoleErrors).toEqual([])
  expect(pageErrors).toEqual([])
})
