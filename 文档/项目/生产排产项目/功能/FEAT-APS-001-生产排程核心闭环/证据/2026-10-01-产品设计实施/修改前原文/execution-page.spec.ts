import { expect, test, type Page } from '@playwright/test'

const runId = '90000000-0000-4000-8000-000000000001'
const planId = '90000000-0000-4000-8000-000000000002'
const jobId = '90000000-0000-4000-8000-000000000003'
const memberId = '90000000-0000-4000-8000-000000000004'
const taskId = '90000000-0000-4000-8000-000000000005'
const resourceId = '90000000-0000-4000-8000-000000000006'
const reportId = '90000000-0000-4000-8000-000000000007'
const lotId = '90000000-0000-4000-8000-000000000008'

const detail = (status = 'RUNNING', rowVersion = 1) => ({
  schemaVersion: '1.0', contractType: 'EXECUTION_RUN_DETAIL', executionRevision: rowVersion + 4,
  run: { id: runId, planVersionId: planId, planJobId: jobId, runNo: 1, status,
    assignedQty: 20, uomCode: 'PCS', actualStartAt: '2026-09-15T01:00:00.000Z', actualEndAt: null,
    pauseReason: null, requestId: '90000000-0000-4000-8000-000000000010', rowVersion },
  occupancies: [{ id: '90000000-0000-4000-8000-000000000011', planJobId: jobId,
    executionRunId: runId, planSegmentId: '90000000-0000-4000-8000-000000000012',
    sourcePlanAllocationId: '90000000-0000-4000-8000-000000000016', resourceId,
    activityType: 'RUN', startAt: '2026-09-15T01:00:00.000Z', endAt: null, status: 'ACTIVE',
    correctionOfId: null, reason: null, rowVersion: 0 }],
  reports: [], outputLots: []
})

async function openRun(page: Page) {
  await page.goto('/tests/e2e/aps/fixtures/execution.html')
  await page.getByPlaceholder('输入 UUID 或通过深链打开').fill(runId)
  await page.getByRole('button', { name: '读取执行' }).click()
  await expect(page.getByText('Run #1 · 执行中')).toBeVisible()
}

test('现场页从空态读取 Run，并按行版本提交暂停命令', async ({ page }) => {
  const writes: unknown[] = []
  await page.route('**/api/aps/v1/**', async route => {
    const request = route.request()
    if (request.method() === 'GET') return route.fulfill({ json: detail() })
    writes.push(request.postDataJSON())
    return route.fulfill({ json: detail('PAUSED', 2) })
  })
  await page.goto('/tests/e2e/aps/fixtures/execution.html')
  await expect(page.getByText('尚未选择执行 Run')).toBeVisible()
  await page.getByPlaceholder('输入 UUID 或通过深链打开').fill(runId)
  await page.getByRole('button', { name: '读取执行' }).click()
  await page.getByRole('button', { name: '暂停' }).click()
  await page.getByRole('textbox', { name: '请输入本次状态变化的现场原因' }).fill('设备点检')
  await page.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText('Run #1 · 已暂停')).toBeVisible()
  expect(writes).toEqual([{ action: 'PAUSE', expectedRowVersion: 1,
    occurredAt: expect.stringMatching(/Z$/), reason: '设备点检' }])
})

test('运行中可幂等推进下一计划段', async ({ page }) => {
  let write: { url?: string; headers?: Record<string, string>; body?: Record<string, unknown> } = {}
  await page.route('**/api/aps/v1/**', async route => {
    const request = route.request()
    if (request.method() === 'GET') return route.fulfill({ json: detail() })
    write = { url: request.url(), headers: request.headers(), body: request.postDataJSON() }
    return route.fulfill({ json: detail('RUNNING', 2) })
  })
  await openRun(page)
  await page.getByRole('button', { name: '推进下一计划段' }).click()
  await page.getByRole('button', { name: '确认推进' }).click()
  await expect(page.getByText('当前计划段已关闭，下一计划段已开始')).toBeVisible()
  expect(write.url).toContain(`/execution-runs/${runId}/phase-advances`)
  expect(write.headers?.['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/)
  expect(write.body).toMatchObject({ expectedRowVersion: 1, occurredAt: expect.stringMatching(/Z$/),
    reason: '现场确认进入下一计划段' })
})

test('部分报工以幂等键提交，并显示原子生成的产出批', async ({ page }) => {
  let reportWrite: { headers?: Record<string, string>; body?: Record<string, unknown> } = {}
  await page.route('**/api/aps/v1/**', async route => {
    const request = route.request()
    if (request.method() === 'GET') return route.fulfill({ json: detail() })
    reportWrite = { headers: request.headers(), body: request.postDataJSON() }
    const next = { ...detail('RUNNING', 2), reports: [{ id: reportId, planJobId: jobId, executionRunId: runId, planJobMemberId: memberId,
      taskId, reportType: 'PROGRESS', reportedAt: '2026-09-15T01:20:00.000Z',
      quantities: { processedQty: 10, goodQty: 8, pendingQty: 2, rejectedQty: 0, scrapQty: 0, transferredQty: 0 },
      uomCode: 'PCS', defectReason: null, correctionOfId: null,
      requestId: '90000000-0000-4000-8000-000000000013', operatorUserId: 'operator', rowVersion: 0 }],
    outputLots: [{ id: lotId, productionLotId: '90000000-0000-4000-8000-000000000014', sourceTaskId: taskId,
      sourceReportId: reportId, itemId: '90000000-0000-4000-8000-000000000015', sourceOutputLotId: null,
      outputLotNo: 'OUT-001', qualityStatus: 'PENDING', totalQty: 2, availableQty: 0, reservedQty: 0,
      consumedQty: 0, scrappedQty: 0, uomCode: 'PCS', dispositionType: 'NONE', dispositionReason: null,
      approvedBy: null, approvedAt: null, rowVersion: 0 }] }
    return route.fulfill({ status: 201, json: next })
  })
  await openRun(page)
  await page.getByRole('button', { name: '提交报工' }).click()
  const dialog = page.getByRole('dialog', { name: '提交部分/完工报工' })
  await dialog.getByLabel('计划作业成员 ID').fill(memberId)
  await dialog.getByLabel('任务 ID').fill(taskId)
  await dialog.getByLabel('本次加工量').fill('10')
  await dialog.getByLabel('合格量').fill('8')
  await dialog.getByLabel('待检量').fill('2')
  await dialog.getByRole('button', { name: '提交且写入数量账' }).click()
  await page.getByRole('tab', { name: /产出与质量/ }).click()
  await expect(page.getByText('OUT-001')).toBeVisible()
  expect(reportWrite.headers?.['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/)
  expect(reportWrite.body).toMatchObject({ expectedRowVersion: 1, planJobMemberId: memberId, taskId,
    reportType: 'PROGRESS', quantities: { processedQty: 10, goodQty: 8, pendingQty: 2, rejectedQty: 0 } })
})

test('并发冲突后刷新事实且保留报工输入供核对重试', async ({ page }) => {
  let current = detail()
  let posts = 0
  await page.route('**/api/aps/v1/**', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: current })
    posts++
    if (posts === 1) {
      current = detail('RUNNING', 2)
      return route.fulfill({ status: 409, json: { code: 'STALE_VERSION', detail: '行版本已变化' } })
    }
    return route.fulfill({ status: 201, json: detail('RUNNING', 3) })
  })
  await openRun(page)
  await page.getByRole('button', { name: '提交报工' }).click()
  const dialog = page.getByRole('dialog', { name: '提交部分/完工报工' })
  await dialog.getByLabel('计划作业成员 ID').fill(memberId)
  await dialog.getByLabel('任务 ID').fill(taskId)
  await dialog.getByLabel('本次加工量').fill('4')
  await dialog.getByLabel('合格量').fill('4')
  await dialog.getByRole('button', { name: '提交且写入数量账' }).click()
  await expect(page.getByText(/执行事实已被其他操作更新/)).toBeVisible()
  await expect(dialog.getByLabel('本次加工量')).toHaveValue('4')
  await dialog.getByRole('button', { name: '提交且写入数量账' }).click()
  await expect(dialog).toBeHidden()
  expect(posts).toBe(2)
})
