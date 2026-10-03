// oxlint-disable-next-line no-unused-expressions -- Playwright CLI读取并执行此函数表达式。
async (page) => {
  const pid = '11111111-1111-4111-8111-111111111111'
  const state = await page.evaluate(() => window.aipptFixtureControl({ project: { status: 'ready' }, outline: { status: 'confirmed', confirmed: true } }))
  const slides = state.slides.map((slide, index) => index === 1 ? { ...slide, status: 'failed' } : slide)
  const calls = []
  let permissions = ['aippt:project:list', 'aippt:project:query', 'aippt:outline:query', 'aippt:deck:query', 'aippt:deck:generate']
  const respond = (route, data) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  await page.route('**/dev-api/getInfo', (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, user: { userId: 1, userName: '权限技术夹具' }, roles: [], permissions }) }))
  await page.route(`**/dev-api/aippt/projects/${pid}/deck`, (route) => respond(route, { project_id: pid, status: 'partial', ready: 10, failed: 1, total: 11, deck_revision: 4, theme_revision: 3, slides }))
  await page.route(`**/dev-api/aippt/projects/${pid}/deck/slides/*/retry`, (route) => { calls.push({ path: new URL(route.request().url()).pathname, body: route.request().postDataJSON(), key: route.request().headers()['idempotency-key'] }); return respond(route, { job_id: 'retry-technical', status: 'generating' }) })
  await page.reload()
  const nav = page.getByRole('navigation', { name: '页面列表', exact: true })
  const retry = nav.getByRole('button', { name: '重试这一页', exact: true })
  await retry.waitFor()
  if (!await retry.isEnabled() || await nav.getByRole('button', { name: '页面操作', exact: true }).count() !== 0) throw new Error('仅生成权限角色不能重试或误开放编辑菜单')
  await page.screenshot({ path: 'D:/2025Ai/26-05-23/java-workspace/output/aippt-react-business-gap-20261003/screenshots/04-仅生成权限可重试缩略图.png' })
  await retry.click()
  for (let i = 0; i < 80 && !calls.length; i++) await page.waitForTimeout(20)
  if (calls.length !== 1 || !calls[0].key || calls[0].body.revision !== slides[1].revision) throw new Error('原缩略图重试未使用实际页revision/key')
  permissions = ['aippt:project:list', 'aippt:project:query', 'aippt:outline:query', 'aippt:deck:query', 'aippt:slide:edit']
  await page.reload(); await retry.waitFor()
  if (await retry.isEnabled() || await nav.getByRole('button', { name: '页面操作', exact: true }).count() === 0) throw new Error('仅编辑权限未分别禁生成重试/保留编辑菜单')
  await page.screenshot({ path: 'D:/2025Ai/26-05-23/java-workspace/output/aippt-react-business-gap-20261003/screenshots/05-仅编辑权限禁止生成重试.png' })
  return { scope: '隔离React真实缩略图/平台getInfo权限Mock；无真实生成业务', calls, generationOnly: { retryEnabled: true, editMenuVisible: false }, editOnly: { retryEnabled: false, editMenuVisible: true } }
}
