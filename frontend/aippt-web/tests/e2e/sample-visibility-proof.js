async (page) => {
  await page.evaluate(() => window.aipptFixtureControl({ project: { status: 'ready' }, outline: { status: 'confirmed', confirmed: true } }))
  await page.goto('http://127.0.0.1:39173/aippt-web/projects/11111111-1111-4111-8111-111111111111')
  await page.getByRole('button', { name: '主题', exact: true }).click()
  await page.getByRole('button', { name: '查看设计样例', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '主题与版式样例', exact: true })
  await dialog.getByRole('button', { name: '检查样例质量', exact: true }).click()
  await dialog.getByText('检查完成，1 项警告', { exact: true }).waitFor()
  await dialog.getByRole('checkbox').check()
  await dialog.getByRole('button', { name: '导出样例 PPTX', exact: true }).click()
  const failure = dialog.getByText('第 5 页 · chart · 技术夹具原生图表回读失败', { exact: true })
  await failure.waitFor(); await failure.scrollIntoViewIfNeeded()
  await page.screenshot({ path: '../../output/playwright/aippt-react-entry/17-样例回读失败完整可见.png' })
  const state = await page.evaluate(() => window.aipptFixtureControl({}))
  if (state.requests.some((item) => item.path.endsWith('/download'))) throw new Error('失败回读后仍下载')
  return { scope: '仅补拍可见范围；真实组件+Mock异步quality/failed task，无真实PPTX', failure: await failure.textContent(), exports: state.requests.filter((item) => item.path === '/aippt/design/sample-deck/exports'), downloads: [] }
}
