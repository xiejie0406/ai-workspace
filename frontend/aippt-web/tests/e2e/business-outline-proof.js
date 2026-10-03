// oxlint-disable-next-line no-unused-expressions -- Playwright CLI读取并执行此函数表达式。
async (page) => {
  const pid = '11111111-1111-4111-8111-111111111111'
  const ref = '55555555-5555-4555-8555-555555555555'
  await page.getByRole('combobox', { name: '项目状态', exact: true }).selectOption({ label: '已有大纲' })
  await page.getByRole('link').filter({ hasText: '原界面技术夹具' }).waitFor()
  await page.screenshot({ path: 'D:/2025Ai/26-05-23/java-workspace/output/aippt-react-business-gap-20261003/screenshots/01-已有大纲筛选.png' })
  await page.getByRole('link').filter({ hasText: '原界面技术夹具' }).click()
  await page.getByRole('heading', { name: '确认大纲', exact: true }).waitFor()
  await page.getByText('来源模型输入按预算裁剪：15000→12000字', { exact: true }).waitFor()
  await page.getByText('来源、页型与配图', { exact: true }).first().click()
  const role = page.getByRole('combobox', { name: '第 1 页角色', exact: true })
  const layout = page.getByRole('combobox', { name: '第 1 页布局', exact: true })
  const visual = page.getByRole('textbox', { name: '第 1 页配图意图', exact: true })
  const source = page.getByRole('checkbox', { name: '第 1 页引用 技术材料.txt · 解析章节', exact: true })
  const left = await page.getByRole('heading', { name: '确认大纲', exact: true }).boundingBox()
  const right = await page.getByRole('complementary').boundingBox()
  if (!left || !right || right.x <= left.x + left.width) throw new Error('原左右提纲/主题两栏被改变')
  const choices = await layout.locator('option').allTextContents()
  if (choices.length !== 11) throw new Error('未提供原11合法版式')
  await role.selectOption('summary'); await layout.selectOption('image-right'); await visual.fill('城市办公协作场景'); await source.check()
  await page.screenshot({ path: 'D:/2025Ai/26-05-23/java-workspace/output/aippt-react-business-gap-20261003/screenshots/02-完整提纲字段与裁剪提示.png' })
  let state
  for (let i = 0; i < 100; i++) {
    state = await page.evaluate(() => window.aipptFixtureControl({}))
    if (state.requests.some((request) => request.path.endsWith('/outline') && request.method === 'PATCH')) break
    await page.waitForTimeout(25)
  }
  const patch = state.requests.filter((request) => request.path.endsWith('/outline') && request.method === 'PATCH').at(-1)
  if (!patch) throw new Error('真实控件未发送提纲PATCH')
  const body = JSON.parse(patch.body); const first = body.pages[0]
  if (body.revision !== 6 || !patch.key || first.layout_id !== 'image-right' || first.page_role !== 'summary' || first.visual !== '城市办公协作场景' || first.source_refs[0] !== ref || body.warnings !== undefined || body.source_budget !== undefined) throw new Error('实际提纲正文/版本/稳定引用/只读metadata不符预期')
  await page.reload(); await page.getByRole('heading', { name: '确认大纲', exact: true }).waitFor()
  await page.getByText('来源、页型与配图', { exact: true }).first().click()
  if (await role.inputValue() !== 'summary' || await layout.inputValue() !== 'image-right' || await visual.inputValue() !== '城市办公协作场景' || !await source.isChecked()) throw new Error('刷新后正式字段未保留')
  await page.screenshot({ path: 'D:/2025Ai/26-05-23/java-workspace/output/aippt-react-business-gap-20261003/screenshots/03-提纲刷新正式字段保持.png' })
  return { scope: '隔离headless原React真实控件+Mock中心API；不计41业务通过', projectId: pid, left, right, choices, patch, formal: state.outline, screenshots: ['01-已有大纲筛选.png', '02-完整提纲字段与裁剪提示.png', '03-提纲刷新正式字段保持.png'] }
}
