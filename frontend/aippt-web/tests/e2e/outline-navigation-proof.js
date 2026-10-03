async (page) => {
  const pid = '11111111-1111-4111-8111-111111111111'
  let held = false; let release; let requestProof
  await page.evaluate(() => window.aipptFixtureControl({ project: { status: 'draft' }, outline: { status: 'draft', confirmed: false } }))
  await page.goto('http://127.0.0.1:39173/aippt-web/projects')
  await page.getByRole('link').filter({ hasText: '原界面技术夹具' }).click()
  await page.getByRole('heading', { name: '确认大纲', exact: true }).waitFor()
  const left = await page.getByRole('heading', { name: '确认大纲', exact: true }).boundingBox()
  const right = await page.getByRole('complementary').getByRole('button').first().boundingBox()
  if (!left || !right || right.x <= left.x + left.width) throw new Error('原左右大纲/主题两栏未呈现')
  await page.screenshot({ path: '../../output/playwright/aippt-react-entry/15-原提纲主题两栏.png' })
  await page.route(`**/dev-api/aippt/projects/${pid}/outline/confirm-and-generate`, async (route) => {
    held = true; requestProof = { body: route.request().postDataJSON(), key: route.request().headers()['idempotency-key'] }
    await new Promise((resolve) => { release = resolve })
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: { status: 'ready', task_ids: [], job_id: null } }) })
  })
  await page.getByRole('button', { name: '生成 PPT', exact: true }).click()
  for (let i = 0; i < 60 && !held; i++) await page.waitForTimeout(20)
  if (!held) throw new Error('未进入真实组件确认与生成请求')
  const origin = page.url(); const idx = await page.evaluate(() => history.state.idx)
  await page.evaluate(() => history.back())
  await page.getByText('确认与生成请求尚未结束，请稍后再离开', { exact: true }).waitFor()
  const afterBack = { url: page.url(), idx: await page.evaluate(() => history.state.idx), starting: await page.getByRole('button', { name: '启动中…', exact: true }).count() }
  if (afterBack.url !== origin || afterBack.idx !== idx || afterBack.starting !== 1) throw new Error('生成在途返回未留在原大纲')
  await page.screenshot({ path: '../../output/playwright/aippt-react-entry/16-提纲生成在途返回保持.png' })
  release()
  for (let i = 0; i < 60; i++) { if (await page.getByRole('button', { name: '生成 PPT', exact: true }).isEnabled()) break; await page.waitForTimeout(20) }
  if (!(await page.getByRole('button', { name: '生成 PPT', exact: true }).isEnabled())) throw new Error('确认回执后忙态未结束')
  await page.getByRole('link', { name: '返回我的 PPT', exact: true }).click()
  await page.waitForURL('**/aippt-web/projects')
  return { scope: '原BrowserRouter+实际OutlineWorkspace+延迟Mock请求；不代表真实生成业务', columns: { left, right }, requestProof, origin, idx, afterBack, afterSettledReturn: page.url() }
}
