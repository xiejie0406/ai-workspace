// oxlint-disable-next-line no-unused-expressions -- Playwright CLI读取并执行此函数表达式。
async (page) => {
  const pid = '11111111-1111-4111-8111-111111111111'
  await page.unroute('**/dev-api/getInfo')
  await page.unroute(`**/dev-api/aippt/projects/${pid}/deck`)
  await page.reload()
  const present = page.getByRole('button', { name: '演示', exact: true })
  await present.waitFor(); await present.focus(); await present.click()
  const dialog = page.getByRole('dialog', { name: '演示放映', exact: true })
  await dialog.waitFor()
  await page.evaluate(() => { window.aipptOriginalFullscreen = HTMLElement.prototype.requestFullscreen; HTMLElement.prototype.requestFullscreen = () => Promise.reject(new DOMException('技术夹具拒绝全屏', 'NotAllowedError')) })
  await page.keyboard.press('f'); await dialog.waitFor()
  await page.keyboard.press('End'); await page.keyboard.press('Home')
  await page.screenshot({ path: 'D:/2025Ai/26-05-23/java-workspace/output/aippt-react-business-gap-20261003/screenshots/06-全屏拒绝仍可原放映.png' })
  await page.keyboard.press('Escape'); await dialog.waitFor({ state: 'detached' })
  const restored = await present.evaluate((node) => document.activeElement === node)
  if (!restored) throw new Error('放映退出未恢复原入口焦点')
  await page.evaluate(() => { HTMLElement.prototype.requestFullscreen = window.aipptOriginalFullscreen; delete window.aipptOriginalFullscreen })
  await page.screenshot({ path: 'D:/2025Ai/26-05-23/java-workspace/output/aippt-react-business-gap-20261003/screenshots/07-放映退出焦点恢复.png' })
  const verify = { passed: false, issues: [{ check: 'native_chart', slide_index: 5, shape: 'chart-b0502', message: '技术夹具普通导出原生图表不可编辑' }], slide_count: 11, expected_slide_count: 11, diagnostics_truncated: true, total_issues: 7 }
  const writes = []; let downloads = 0
  const respond = (route, data) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  await page.route(`**/dev-api/aippt/projects/${pid}/deck/exports`, (route) => { writes.push({ body: route.request().postDataJSON(), key: route.request().headers()['idempotency-key'] }); return respond(route, { id: 'ordinary-export', job_id: 'ordinary-job', status: 'queued' }) })
  await page.route('**/dev-api/aippt/tasks/ordinary-job', (route) => respond(route, { id: 'ordinary-job', status: 'failed', error: '回读失败', verification_failure: verify }))
  await page.route('**/dev-api/aippt/exports/ordinary-export', (route) => respond(route, { id: 'ordinary-export', status: 'failed', verify }))
  await page.route('**/dev-api/aippt/exports/ordinary-export/download', (route) => { downloads++; return route.fulfill({ body: 'must not download' }) })
  await page.getByRole('button', { name: '导出', exact: true }).click()
  const exportButton = page.getByRole('button', { name: '导出 PPTX', exact: true })
  await exportButton.waitFor(); await exportButton.click()
  await page.getByText('第 5 页 · 原生图表 · chart-b0502 · 技术夹具普通导出原生图表不可编辑', { exact: true }).waitFor()
  await page.getByText('诊断已截断，原始问题共 7 项。', { exact: true }).waitFor()
  if (writes.length !== 1 || !writes[0].key || downloads !== 0) throw new Error('普通失败诊断请求或下载阻断不符预期')
  await page.screenshot({ path: 'D:/2025Ai/26-05-23/java-workspace/output/aippt-react-business-gap-20261003/screenshots/08-普通导出回读检查形状与截断.png' })
  return { scope: '原React实际放映/导出面板+拒全屏/异步失败Mock；无真实PPTX/Office/中心导出', restoredFocus: restored, writes, verify, downloads, screenshots: ['06-全屏拒绝仍可原放映.png', '07-放映退出焦点恢复.png', '08-普通导出回读检查形状与截断.png'] }
}
