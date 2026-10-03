import { expect, test } from '@playwright/test'

const metadata = {
  siteCode: 'SITE-01', zoneId: 'Asia/Shanghai',
  fromAt: '2026-09-15T00:00:00Z', toAt: '2026-09-16T00:00:00Z',
  baselinePlanVersionId: '10000000-0000-4000-8000-000000000001',
  currentPlanVersionId: '20000000-0000-4000-8000-000000000002',
  dataCutoffAt: '2026-09-15T08:30:00Z', generatedAt: '2026-09-15T08:30:01Z'
}

const daily = {
  businessDate: '2026-09-15', metadata,
  rows: [{ workshopId: 'ws', workshopCode: 'WS-01', workshopName: '一车间',
    workCenterId: 'wc', workCenterCode: 'WC-01', workCenterName: '焊接中心',
    operationSpecId: 'op', operationCode: 'OP-WELD', operationName: '焊接',
    orderId: 'order', orderNo: 'SO-20260915-01', orderLineId: 'line', lineNo: 1,
    itemId: 'item', itemCode: 'FG-01', itemName: '成品 A', productionLotId: 'lot', lotNo: 'LOT-01',
    taskId: 'task', taskCode: 'TASK-01', taskName: '焊接任务', taskStatus: 'RELEASED', uomCode: 'PCS',
    promisedAt: '2026-09-15T10:00:00Z', baselineStartAt: '2026-09-15T00:00:00Z',
    baselineEndAt: '2026-09-15T08:00:00Z', baselinePlannedQty: 100,
    baselinePersonHours: 8, baselineMachineHours: 8, currentStartAt: '2026-09-15T01:00:00Z',
    currentEndAt: '2026-09-15T09:00:00Z', currentPlannedQty: 90, currentPersonHours: 7,
    currentMachineHours: 7, actualProcessedQty: 80, actualGoodQty: 75, rejectedQty: 3,
    scrapQty: 2, transferredQty: 70, actualPersonHours: 6.5, actualMachineHours: 6.5,
    achievementRatio: 0.75, carryoverQty: 25, delayed: true,
    plannedPersonResourceIds: ['P-01'], plannedMachineResourceIds: ['M-01'],
    actualPersonResourceIds: ['P-01'], actualMachineResourceIds: ['M-01'],
    reasonCodes: ['BELOW_BASELINE', 'DELAYED'] }]
}

const labor = {
  metadata,
  aggregationNotice: '人员总产能按人员去重；技能潜力不可跨技能相加。',
  people: [{ resourceId: 'p1', resourceCode: 'P-01', resourceName: '张工', workshopId: 'ws',
    workshopCode: 'WS-01', workshopName: '一车间', workCenterId: 'wc', workCenterCode: 'WC-01',
    workCenterName: '焊接中心', teamName: 'A 班', skillCodes: ['WELD', 'INSPECT'],
    availableHours: 8, scheduledHours: 2, remainingHours: 6, overloaded: false }],
  skills: [{ skillCode: 'WELD', availablePotentialHours: 8, scheduledHours: 4,
    unplannedRequiredHours: 1, peakRequiredPeople: 2, peakQualifiedPeople: 1,
    peakShortagePeople: 1, peakAt: '2026-09-15T05:00:00Z',
    reasonCodes: ['PEAK_SKILL_SHORTAGE', 'SKILL_POTENTIAL_NON_ADDITIVE'] }]
}

const orders = {
  metadata,
  orders: [{ orderId: 'order', orderNo: 'SO-20260915-01', orderStatus: 'RELEASED',
    promisedAt: '2026-09-16T08:00:00Z', etaState: 'UNKNOWN', expectedProductionAt: null,
    knownLowerBoundAt: '2026-09-15T09:00:00Z', delaySeconds: null,
    productionCompleted: false, orderClosed: false,
    reasons: [{ code: 'UNPLANNED_TASK', objectType: 'TASK', objectId: 'task', detail: '必需任务未进入当前正式计划' }],
    lines: [{ orderLineId: 'line', lineNo: 1, itemId: 'item', itemCode: 'FG-01', itemName: '成品 A',
      uomCode: 'PCS', demandQty: 100, releasedTerminalQty: 0, lineStatus: 'RELEASED',
      promisedAt: '2026-09-16T08:00:00Z', etaState: 'UNKNOWN', expectedProductionAt: null,
      knownLowerBoundAt: '2026-09-15T09:00:00Z',
      reasons: [{ code: 'UNPLANNED_TASK', objectType: 'TASK', objectId: 'task', detail: '必需任务未进入当前正式计划' }] }] }]
}

test.beforeEach(async ({ page }) => {
  await page.route('**/api/aps/v1/reports/**', async route => {
    const url = route.request().url()
    if (url.endsWith('.csv') || url.includes('.csv?')) {
      return route.fulfill({ contentType: 'text/csv; charset=utf-8', body: '\ufeff订单,ETA\r\nSO-20260915-01,UNKNOWN\r\n' })
    }
    if (url.includes('labor-capacity')) return route.fulfill({ json: labor })
    if (url.includes('order-delivery')) return route.fulfill({ json: orders })
    return route.fulfill({ json: daily })
  })
})

test('默认展示日冻结基线与当前正式计划的可追溯口径', async ({ page }) => {
  await page.goto('/tests/e2e/aps/fixtures/reports.html')
  await expect(page.getByText('SITE-01 · Asia/Shanghai')).toBeVisible()
  await expect(page.getByText(/日冻结 10000000… · 当前正式 20000000…/)).toBeVisible()
  await expect(page.getByText('100 / 90 PCS')).toBeVisible()
  await expect(page.getByText('75.0%')).toBeVisible()
  await expect(page.getByText('BELOW_BASELINE / DELAYED')).toBeVisible()
})

test('人力累计按员工去重，并独立显示多席位峰值缺口', async ({ page }) => {
  await page.goto('/tests/e2e/aps/fixtures/reports.html')
  await page.getByRole('tab', { name: '人力累计产能' }).click()
  await expect(page.getByText('人员总产能按人员去重；技能潜力不可跨技能相加。')).toBeVisible()
  await expect(page.getByText('WELD、INSPECT')).toBeVisible()
  await expect(page.getByRole('cell', { name: '8.00 h' }).first()).toBeVisible()
  await expect(page.getByRole('cell', { name: '2', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '1', exact: true }).first()).toBeVisible()
  await expect(page.getByText('PEAK_SKILL_SHORTAGE / SKILL_POTENTIAL_NON_ADDITIVE')).toBeVisible()
})

test('缺数据时 ETA 明确显示 UNKNOWN 和原因，不伪造完成时间', async ({ page }) => {
  await page.goto('/tests/e2e/aps/fixtures/reports.html')
  await page.getByRole('tab', { name: '订单交期与 ETA' }).click()
  await expect(page.getByText('UNKNOWN', { exact: true })).toBeVisible()
  await expect(page.getByText(/UNPLANNED_TASK: 必需任务未进入当前正式计划/)).toBeVisible()
  await expect(page.getByText('FG-01 · 成品 A')).toBeVisible()
})

test('CSV 导出沿用当前报表类型并生成可下载文件', async ({ page }) => {
  await page.goto('/tests/e2e/aps/fixtures/reports.html')
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 CSV' }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toMatch(/^aps-daily-production-\d{4}-\d{2}-\d{2}\.csv$/)
})
