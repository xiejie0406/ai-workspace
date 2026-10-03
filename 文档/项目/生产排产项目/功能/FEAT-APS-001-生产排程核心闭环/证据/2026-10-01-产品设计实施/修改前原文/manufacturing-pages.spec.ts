import { expect, test } from '@playwright/test'

test('路线页展示版本化 DAG 和显式依赖', async ({ page }) => {
  const errors: string[] = []
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()) })
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/aps/v1/routings/**', route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/items')) return route.fulfill({ json: [{ id: '10000000-0000-4000-8000-000000000001', code: 'PROD-A', name: '产品 A', type: 'PRODUCT', baseUomCode: 'PCS', status: 'ACTIVE', rowVersion: 0 }] })
    if (path.endsWith('/operations')) return route.fulfill({ json: [{ id: '20000000-0000-4000-8000-000000000001', code: 'OP-CUT', name: '切割', mode: 'MAN_MACHINE', interruptible: false, qualityGateRequired: false, status: 'ACTIVE', phases: [{ phaseNo: 1, phaseType: 'RUN', name: '加工', durationModel: 'PER_UNIT', fixedSeconds: 0, secondsPerUnit: 10, resourceHoldPolicy: 'PHASE_ONLY', requirements: [] }], rowVersion: 1 }] })
    if (path.endsWith('/routes')) return route.fulfill({ json: [{ id: '30000000-0000-4000-8000-000000000001', itemId: '10000000-0000-4000-8000-000000000001', routeCode: 'ROUTE-A', versionNo: 'V1', status: 'ACTIVE', rowVersion: 1 }] })
    if (path.endsWith('/routes/30000000-0000-4000-8000-000000000001')) return route.fulfill({ json: { route: { id: '30000000-0000-4000-8000-000000000001', itemId: '10000000-0000-4000-8000-000000000001', routeCode: 'ROUTE-A', versionNo: 'V1', status: 'ACTIVE', rowVersion: 1 }, nodes: [{ id: 'n1', routeVersionId: 'r1', operationSpecId: '20000000-0000-4000-8000-000000000001', nodeCode: 'CUT', nodeName: '切割', displayOrder: 10, quantityMultiplier: 1, terminal: false, rowVersion: 0 }, { id: 'n2', routeVersionId: 'r1', operationSpecId: '20000000-0000-4000-8000-000000000001', nodeCode: 'FINISH', nodeName: '完工', displayOrder: 20, quantityMultiplier: 1, terminal: true, rowVersion: 0 }], edges: [{ id: 'e1', routeVersionId: 'r1', predecessorNodeId: 'n1', successorNodeId: 'n2', dependencyType: 'FINISH', lagSeconds: 0, consumesOutput: false, rowVersion: 0 }] } })
    return route.fulfill({ status: 404, json: {} })
  })
  await page.goto('/tests/e2e/aps/fixtures/routing.html')
  await page.getByText('产品 A').click()
  await page.getByText('ROUTE-A').click()
  await expect(page.getByText('路线 DAG')).toBeVisible()
  await expect(page.getByText('CUT', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('FINISH', { exact: true }).first()).toBeVisible()
  expect(errors).toEqual([])
})

test('订单页展示多产品行和冻结任务网络', async ({ page }) => {
  const errors: string[] = []
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()) })
  page.on('pageerror', error => errors.push(error.message))
  const items = [{ id: 'i1', code: 'PROD-A', name: '产品 A', type: 'PRODUCT', baseUomCode: 'PCS', status: 'ACTIVE', rowVersion: 0 }, { id: 'i2', code: 'PROD-B', name: '产品 B', type: 'PRODUCT', baseUomCode: 'PCS', status: 'ACTIVE', rowVersion: 0 }]
  const lines = [{ id: 'l1', orderId: 'o1', lineNo: 1, itemId: 'i1', routeVersionId: 'r1', demandQty: 10, uomCode: 'PCS', status: 'DRAFT', components: [], dependencies: [], rowVersion: 0 }, { id: 'l2', orderId: 'o1', lineNo: 2, itemId: 'i2', routeVersionId: 'r2', demandQty: 5, uomCode: 'PCS', status: 'DRAFT', components: [], dependencies: [{ predecessorLineNo: 1, predecessorNodeCode: 'A-END', successorNodeCode: 'B-START', dependencyType: 'QUANTITY', thresholdQty: 5, lagSeconds: 0, consumesOutput: false }], rowVersion: 0 }]
  const order = { id: 'o1', orderNo: 'ERP-001', sourceSystem: 'ERP', externalId: 'EXT-001', priority: 50, status: 'DRAFT', lines, rowVersion: 0 }
  await page.route('**/api/aps/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/routings/items')) return route.fulfill({ json: items })
    if (path.endsWith('/routings/routes')) return route.fulfill({ json: [{ id: 'r1', itemId: 'i1', routeCode: 'RA', versionNo: 'V1', status: 'ACTIVE', rowVersion: 1 }, { id: 'r2', itemId: 'i2', routeCode: 'RB', versionNo: 'V1', status: 'ACTIVE', rowVersion: 1 }] })
    if (path.endsWith('/orders')) return route.fulfill({ json: [order] })
    if (path.endsWith('/orders/o1/expansion')) return route.fulfill({ json: { order, lots: [{ id: 'lot1' }, { id: 'lot2' }], tasks: [{ id: 't1', taskCode: 'ERP-001-001-A-END', taskName: '产品 A 完工', taskQty: 10, runSeconds: 100, status: 'NOT_READY' }, { id: 't2', taskCode: 'ERP-001-002-B-START', taskName: '产品 B 开工', taskQty: 5, runSeconds: 50, status: 'NOT_READY' }], dependencies: [{ id: 'd1', predecessorTaskId: 't1', successorTaskId: 't2', dependencyType: 'QUANTITY', thresholdQty: 5 }], materialDemands: [], reused: true } })
    return route.fulfill({ status: 404, json: {} })
  })
  await page.goto('/tests/e2e/aps/fixtures/orders.html')
  await page.getByText('ERP-001').click()
  await expect(page.getByText('产品 A', { exact: true })).toBeVisible()
  await expect(page.getByText('产品 B', { exact: true })).toBeVisible()
  await expect(page.getByText('ERP-001-001-A-END')).toBeVisible()
  await expect(page.getByText('QUANTITY')).toBeVisible()
  expect(errors).toEqual([])
})
