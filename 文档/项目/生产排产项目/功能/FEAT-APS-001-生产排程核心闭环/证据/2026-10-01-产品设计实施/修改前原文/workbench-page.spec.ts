import { expect, test, type Page } from '@playwright/test'

const planId = '80000000-0000-4000-8000-000000000001'
const machineId = '80000000-0000-4000-8000-000000000002'
const personId = '80000000-0000-4000-8000-000000000003'

const baseDetail = {
  schemaVersion: '1.0', contractType: 'PLAN_VERSION_DETAIL',
  version: {
    planVersionId: planId, baseVersionId: null, versionNo: 1, versionName: '浏览器候选',
    status: 'FEASIBLE', definitionRevision: 12, executionRevision: 4,
    inputHash: 'a'.repeat(64), rowVersion: 3, updatedAt: '2026-09-15T00:05:00.000Z'
  },
  candidateHash: 'b'.repeat(64), solverStatus: 'OPTIMAL', resultKind: 'FEASIBLE',
  unplannedTaskIds: ['80000000-0000-4000-8000-000000000099'],
  inputCapturedAt: '2026-09-15T00:00:00.000Z', latestFactUpdatedAt: '2026-09-14T23:59:00.000Z', stale: false,
  jobs: [
    { jobId: '80000000-0000-4000-8000-000000000011', operationSpecId: '80000000-0000-4000-8000-000000000021', workCenterId: '80000000-0000-4000-8000-000000000031', jobCode: 'JOB-CUT-01', jobType: 'NORMAL', batchCode: null, plannedQty: '10', uomCode: 'PCS', capacityValue: null, capacityUomCode: null, compatibilityKey: null, startAt: '2026-09-15T01:00:00.000Z', endAt: '2026-09-15T02:00:00.000Z', members: [{ id: '80000000-0000-4000-8000-000000000041', taskId: '80000000-0000-4000-8000-000000000051', memberNo: 1, plannedQty: '10', uomCode: 'PCS' }] },
    { jobId: '80000000-0000-4000-8000-000000000012', operationSpecId: '80000000-0000-4000-8000-000000000022', workCenterId: '80000000-0000-4000-8000-000000000031', jobCode: 'JOB-HEAT-02', jobType: 'NORMAL', batchCode: null, plannedQty: '6', uomCode: 'PCS', capacityValue: null, capacityUomCode: null, compatibilityKey: null, startAt: '2026-09-15T02:00:00.000Z', endAt: '2026-09-15T03:00:00.000Z', members: [{ id: '80000000-0000-4000-8000-000000000042', taskId: '80000000-0000-4000-8000-000000000052', memberNo: 1, plannedQty: '6', uomCode: 'PCS' }] }
  ],
  segments: [
    { id: '80000000-0000-4000-8000-000000000061', jobId: '80000000-0000-4000-8000-000000000011', phaseId: '80000000-0000-4000-8000-000000000071', segmentNo: 1, phaseType: 'RUN', startAt: '2026-09-15T01:00:00.000Z', endAt: '2026-09-15T02:00:00.000Z', plannedQty: '10', releaseAt: null, releaseQty: null, uomCode: 'PCS' },
    { id: '80000000-0000-4000-8000-000000000062', jobId: '80000000-0000-4000-8000-000000000012', phaseId: '80000000-0000-4000-8000-000000000072', segmentNo: 1, phaseType: 'SETUP', startAt: '2026-09-15T02:00:00.000Z', endAt: '2026-09-15T03:00:00.000Z', plannedQty: null, releaseAt: null, releaseQty: null, uomCode: 'PCS' }
  ],
  allocations: [
    { id: '80000000-0000-4000-8000-000000000081', segmentId: '80000000-0000-4000-8000-000000000061', phaseId: '80000000-0000-4000-8000-000000000071', requirementId: '80000000-0000-4000-8000-000000000091', resourceId: machineId, allocationRole: 'MACHINE', seatNo: 1, capacityUsed: '1' },
    { id: '80000000-0000-4000-8000-000000000082', segmentId: '80000000-0000-4000-8000-000000000061', phaseId: '80000000-0000-4000-8000-000000000071', requirementId: '80000000-0000-4000-8000-000000000092', resourceId: personId, allocationRole: 'PERSON', seatNo: 1, capacityUsed: '1' },
    { id: '80000000-0000-4000-8000-000000000083', segmentId: '80000000-0000-4000-8000-000000000062', phaseId: '80000000-0000-4000-8000-000000000072', requirementId: '80000000-0000-4000-8000-000000000093', resourceId: machineId, allocationRole: 'MACHINE', seatNo: 1, capacityUsed: '1' }
  ],
  locks: [],
  problems: [{ schemaVersion: '1.0', contractType: 'APS_PROBLEM', problemId: '80000000-0000-4000-8000-000000000098', reasonCode: 'NO_COMMON_WINDOW', constraintCode: 'APS-VAL-08', severity: 'WARNING', title: '备用人员窗口不足', detail: '当前候选可执行，但没有第二名合格人员作为替代。', retryable: false, objectRefs: [{ objectType: 'TASK', objectId: '80000000-0000-4000-8000-000000000051', field: null }], timeRange: null, measurements: {} }]
}

const resources = [
  { id: machineId, workshopId: '80000000-0000-4000-8000-000000000101', workCenterId: '80000000-0000-4000-8000-000000000031', code: 'M-01', name: '一号设备', type: 'MACHINE', capacityValue: 1, capacityUomCode: 'COUNT', status: 'ACTIVE', rowVersion: 0 },
  { id: personId, workshopId: '80000000-0000-4000-8000-000000000101', workCenterId: '80000000-0000-4000-8000-000000000031', code: 'P-01', name: '张工', type: 'PERSON', capacityValue: 1, capacityUomCode: 'COUNT', status: 'ACTIVE', rowVersion: 0 }
]

async function mockWorkbench(page: Page, writes: { action?: string; body?: unknown }) {
  await page.route('**/api/aps/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.endsWith('/api/aps/v1/resources')) return route.fulfill({ json: resources })
    if (path.endsWith(`/api/aps/v1/plan-versions/${planId}`)) return route.fulfill({ json: baseDetail })
    if (path.endsWith('/discard') && request.method() === 'POST') {
      writes.action = 'discard'; writes.body = request.postDataJSON()
      return route.fulfill({ json: { schemaVersion: '1.0', contractType: 'PLAN_CANDIDATE_DISCARDED', plan: { ...baseDetail, version: { ...baseDetail.version, status: 'CANCELLED', rowVersion: 4 } }, reason: '采用第二套方案', discardedAt: '2026-09-15T00:10:00.000Z' } })
    }
    if (path.endsWith('/publish') && request.method() === 'POST') {
      writes.action = 'publish'; writes.body = request.postDataJSON()
      return route.fulfill({ json: { schemaVersion: '1.0', contractType: 'PLAN_PUBLISHED', plan: { ...baseDetail, version: { ...baseDetail.version, status: 'PUBLISHED', rowVersion: 4 } }, reused: false, supersededVersionId: null, reason: '浏览器审核通过', outboundStatus: 'NOT_APPLICABLE' } })
    }
    if (path.endsWith('/api/aps/v1/resources/workshops') || path.endsWith('/api/aps/v1/orders') || path.endsWith('/api/aps/v1/plan-versions')) return route.fulfill({ json: [] })
    return route.fulfill({ status: 404, json: {} })
  })
}

test('工作台联动三甘特、筛选定位并解释冲突和未排任务', async ({ page }) => {
  const errors: string[] = []
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()) })
  page.on('pageerror', error => errors.push(error.message))
  await mockWorkbench(page, {})
  await page.goto('/tests/e2e/aps/fixtures/workbench.html')
  await page.getByRole('button', { name: '高级入口：按已有计划标识打开' }).click()
  await page.getByPlaceholder('输入候选或正式计划版本 UUID').fill(planId)
  await page.getByRole('button', { name: '加载计划' }).click()

  await expect(page.getByText('JOB-CUT-01').first()).toBeVisible()
  await page.getByPlaceholder('作业编码或稳定 ID').fill('HEAT')
  await expect(page.getByText('当前显示 1 / 2 个作业')).toBeVisible()
  await page.getByRole('button', { name: '重置筛选' }).click()
  await page.getByRole('tab', { name: '设备甘特' }).click()
  await expect(page.getByLabel('设备甘特').getByText('M-01 · 一号设备')).toBeVisible()
  await page.getByRole('tab', { name: '人员甘特' }).click()
  await expect(page.getByLabel('人员甘特').getByText('P-01 · 张工')).toBeVisible()
  await page.getByRole('tab', { name: /冲突与原因/ }).click()
  await expect(page.getByText('备用人员窗口不足')).toBeVisible()
  await expect(page.getByText('1 个任务未排入当前候选')).toBeVisible()
  expect(errors).toEqual([])
})

test('工作台呈现空态和加载态，并可在接口失败后重试恢复', async ({ page }) => {
  let detailAttempts = 0
  await page.route('**/api/aps/v1/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/api/aps/v1/resources')) return route.fulfill({ json: resources })
    if (path.endsWith(`/api/aps/v1/plan-versions/${planId}`)) {
      detailAttempts++
      if (detailAttempts === 1) return route.fulfill({ status: 503, json: { code: 503, msg: '临时不可用' } })
      await new Promise(resolve => setTimeout(resolve, 500))
      return route.fulfill({ json: baseDetail })
    }
    if (path.endsWith('/api/aps/v1/resources/workshops') || path.endsWith('/api/aps/v1/orders') || path.endsWith('/api/aps/v1/plan-versions')) return route.fulfill({ json: [] })
    return route.fulfill({ status: 404, json: {} })
  })
  await page.goto('/tests/e2e/aps/fixtures/workbench.html')
  await expect(page.getByText('从上方新建排程或选择已有计划')).toBeVisible()
  await page.getByRole('button', { name: '高级入口：按已有计划标识打开' }).click()
  await page.getByPlaceholder('输入候选或正式计划版本 UUID').fill(planId)
  await page.getByRole('button', { name: '加载计划' }).click()
  await expect(page.getByText('计划加载失败', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '加载计划' }).click()
  await expect(page.locator('.aps-workbench > .el-loading-mask')).toBeVisible()
  await expect(page.getByText('JOB-CUT-01').first()).toBeVisible()
  await expect(page.getByText('计划加载失败', { exact: true })).toHaveCount(0)
  expect(detailAttempts).toBe(2)
})

test('窄屏下主控件可用，并可用键盘切换甘特视图', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockWorkbench(page, {})
  await page.goto('/tests/e2e/aps/fixtures/workbench.html')
  await page.getByRole('button', { name: '高级入口：按已有计划标识打开' }).click()
  await page.getByPlaceholder('输入候选或正式计划版本 UUID').fill(planId)
  const loadButton = page.getByRole('button', { name: '加载计划' })
  await loadButton.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByText('JOB-CUT-01').first()).toBeVisible()

  const equipmentTab = page.getByRole('tab', { name: '设备甘特' })
  await page.getByRole('tab', { name: '工序甘特' }).focus()
  await page.keyboard.press('ArrowRight')
  await expect(equipmentTab).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByLabel('设备甘特').getByText('M-01 · 一号设备')).toBeVisible()
  const inputBox = await page.getByPlaceholder('输入候选或正式计划版本 UUID').boundingBox()
  const buttonBox = await loadButton.boundingBox()
  expect(inputBox).not.toBeNull()
  expect(buttonBox).not.toBeNull()
  expect(inputBox!.x + inputBox!.width).toBeLessThanOrEqual(390)
  expect(buttonBox!.x + buttonBox!.width).toBeLessThanOrEqual(390)
})

test('工作台发布和废弃都只提交带行版本和原因的命令', async ({ page }) => {
  const writes: { action?: string; body?: unknown } = {}
  await mockWorkbench(page, writes)
  await page.goto('/tests/e2e/aps/fixtures/workbench.html')
  await page.getByRole('button', { name: '高级入口：按已有计划标识打开' }).click()
  await page.getByPlaceholder('输入候选或正式计划版本 UUID').fill(planId)
  await page.getByRole('button', { name: '加载计划' }).click()
  await page.getByPlaceholder('例如：计划员审核通过').fill('浏览器审核通过')
  await page.getByRole('button', { name: '发布为当前正式计划' }).click()
  await expect(page.getByText('系统内正式计划已生效')).toBeVisible()
  expect(writes).toEqual({ action: 'publish', body: { expectedPlanRowVersion: 3, reason: '浏览器审核通过' } })

  await page.reload()
  await page.getByRole('button', { name: '高级入口：按已有计划标识打开' }).click()
  await page.getByPlaceholder('输入候选或正式计划版本 UUID').fill(planId)
  await page.getByRole('button', { name: '加载计划' }).click()
  await page.getByPlaceholder('例如：改用另一候选方案').fill('采用第二套方案')
  await page.getByRole('button', { name: '废弃当前候选' }).click()
  await page.getByRole('button', { name: '确认废弃' }).click()
  await expect(page.getByText('候选已废弃，历史数据仍保留')).toBeVisible()
  expect(writes).toEqual({ action: 'discard', body: { expectedPlanRowVersion: 3, reason: '采用第二套方案' } })
})

test('当前正式计划用版本化命令提交插单、拆批和固定合批', async ({ page }) => {
  const currentOrderId = '80000000-0000-4000-8000-000000000110'
  const insertedOrderId = '80000000-0000-4000-8000-000000000120'
  const lotA = '80000000-0000-4000-8000-000000000111'
  const lotB = '80000000-0000-4000-8000-000000000112'
  const insertedLot = '80000000-0000-4000-8000-000000000121'
  const publishedDetail = { ...baseDetail, unplannedTaskIds: [], version: { ...baseDetail.version, status: 'PUBLISHED' } }
  const order = (id: string, orderNo: string) => ({
    id, orderNo, sourceSystem: 'LOCAL', priority: 50, status: 'RELEASED', rowVersion: 1, lines: []
  })
  const task = (id: string, productionLotId: string, taskCode: string, quantity: number) => ({
    id, productionLotId, routeVersionId: '80000000-0000-4000-8000-000000000130',
    routeNodeId: '80000000-0000-4000-8000-000000000131', operationSpecId: '80000000-0000-4000-8000-000000000132',
    workCenterId: '80000000-0000-4000-8000-000000000031', taskCode, taskName: taskCode, taskQty: quantity,
    uomCode: 'PCS', setupSeconds: 0, runSeconds: 600, unloadSeconds: 0, waitSeconds: 0,
    transportSeconds: 0, status: 'NOT_READY'
  })
  const currentExpansion = {
    order: order(currentOrderId, 'O-CURRENT'),
    lots: [
      { id: lotA, orderLineId: '80000000-0000-4000-8000-000000000141', lotNo: 'LOT-A', lotType: 'NORMAL', plannedQty: 10, uomCode: 'PCS', status: 'RELEASED' },
      { id: lotB, orderLineId: '80000000-0000-4000-8000-000000000142', lotNo: 'LOT-B', lotType: 'NORMAL', plannedQty: 6, uomCode: 'PCS', status: 'RELEASED' }
    ],
    tasks: [
      task('80000000-0000-4000-8000-000000000051', lotA, 'TASK-A', 10),
      task('80000000-0000-4000-8000-000000000052', lotB, 'TASK-B', 6)
    ], dependencies: [], materialDemands: [], reused: true
  }
  const insertedExpansion = {
    order: order(insertedOrderId, 'O-INSERT'),
    lots: [{ id: insertedLot, orderLineId: '80000000-0000-4000-8000-000000000143', lotNo: 'LOT-INSERT', lotType: 'NORMAL', plannedQty: 2, uomCode: 'PCS', status: 'RELEASED' }],
    tasks: [task('80000000-0000-4000-8000-000000000053', insertedLot, 'TASK-INSERT', 2)],
    dependencies: [], materialDemands: [], reused: true
  }
  const writes: unknown[] = []
  await page.route('**/api/aps/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.endsWith('/api/aps/v1/resources')) return route.fulfill({ json: resources })
    if (path.endsWith(`/api/aps/v1/plan-versions/${planId}`)) return route.fulfill({ json: publishedDetail })
    if (path.endsWith('/api/aps/v1/orders')) return route.fulfill({ json: [currentExpansion.order, insertedExpansion.order] })
    if (path.endsWith(`/api/aps/v1/orders/${currentOrderId}/expansion`)) return route.fulfill({ json: currentExpansion })
    if (path.endsWith(`/api/aps/v1/orders/${insertedOrderId}/expansion`)) return route.fulfill({ json: insertedExpansion })
    if (path.endsWith('/structural-adjustments') && request.method() === 'POST') {
      const body = request.postDataJSON(); writes.push(body)
      return route.fulfill({ json: {
        schemaVersion: '1.0', contractType: 'PLAN_STRUCTURAL_ADJUSTMENT_ACCEPTED',
        requestId: request.headers()['idempotency-key'], planVersionId: '80000000-0000-4000-8000-000000000150',
        basePlanVersionId: planId, planStatus: 'DRAFT', reused: false, action: body.action,
        derivedLotId: body.action === 'SPLIT_LOT' ? '80000000-0000-4000-8000-000000000151' : null,
        baseCandidateHash: 'b'.repeat(64), impact: { affectedTaskIds: body.members?.map((item: { taskId: string }) => item.taskId) ?? [],
          affectedResourceIds: [machineId], reasons: {}, globalRevalidationRequired: true }
      } })
    }
    if (path.endsWith('/api/aps/v1/resources/workshops') || path.endsWith('/api/aps/v1/orders') || path.endsWith('/api/aps/v1/plan-versions')) return route.fulfill({ json: [] })
    return route.fulfill({ status: 404, json: {} })
  })

  await page.goto('/tests/e2e/aps/fixtures/workbench.html')
  await page.getByRole('button', { name: '高级入口：按已有计划标识打开' }).click()
  await page.getByPlaceholder('输入候选或正式计划版本 UUID').fill(planId)
  await page.getByRole('button', { name: '加载计划' }).click()
  await page.getByRole('button', { name: '载入可选对象' }).click()

  const panel = page.locator('.structural-panel')
  await panel.locator('.el-select').nth(1).click()
  await page.getByText(/O-INSERT/).click()
  await page.getByPlaceholder('说明插单、拆批或合批原因').fill('浏览器插单')
  await page.getByRole('button', { name: '创建结构调整草稿' }).click()
  await expect(page.getByText(/结构调整草稿 .* 已进入 DRAFT/)).toBeVisible()

  await panel.locator('.el-select').nth(0).click()
  await page.getByText('拆分生产批', { exact: true }).click()
  await panel.locator('.el-select').nth(1).click()
  await page.getByText(/LOT-A/).click()
  await panel.locator('.el-input-number input').fill('2')
  await page.getByPlaceholder('说明插单、拆批或合批原因').fill('浏览器拆批')
  await page.getByRole('button', { name: '创建结构调整草稿' }).click()

  await panel.locator('.el-select').nth(0).click()
  await page.getByText('固定合批', { exact: true }).click()
  await panel.locator('.el-select').nth(1).click()
  await page.getByText(/TASK-A/).click()
  await panel.locator('.el-select').nth(1).click()
  await page.getByText(/TASK-B/).click()
  await page.keyboard.press('Escape')
  await page.getByPlaceholder('例如：TEMP-180-RED').fill('TEMP-180-COMPATIBLE')
  await page.getByPlaceholder('说明插单、拆批或合批原因').fill('浏览器合批')
  await page.getByRole('button', { name: '创建结构调整草稿' }).click()

  expect(writes).toHaveLength(3)
  expect(writes[0]).toMatchObject({ expectedBaseRowVersion: 3, action: 'INSERT_ORDER', orderId: insertedOrderId, reason: '浏览器插单' })
  expect(writes[1]).toMatchObject({ expectedBaseRowVersion: 3, action: 'SPLIT_LOT', parentLotId: lotA, splitQuantity: 2, reason: '浏览器拆批' })
  expect(writes[2]).toMatchObject({ expectedBaseRowVersion: 3, action: 'MERGE_BATCH', compatibilityKey: 'TEMP-180-COMPATIBLE', reason: '浏览器合批', members: [
    { taskId: '80000000-0000-4000-8000-000000000051', quantity: 10 },
    { taskId: '80000000-0000-4000-8000-000000000052', quantity: 6 }
  ] })
})
