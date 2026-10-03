async (page) => {
  await page.getByRole('textbox', { name: '项目名称', exact: true }).fill('本地待保存 · 原名称完整保留')
  await page.getByRole('dialog').getByRole('button', { name: '关闭', exact: true }).focus()
  const focusPath = []
  for (let i = 0; i < 12; i++) {
    await page.keyboard.press('Shift+Tab')
    const text = await page.evaluate(() => document.activeElement?.textContent?.trim())
    focusPath.push(text)
    if (text === '新建 PPT') break
  }
  if (focusPath.at(-1) !== '新建 PPT') throw new Error('未实际找到原背景键盘入口')
  await page.keyboard.press('Enter')
  await page.getByRole('alert').filter({ hasText: '请先保存或明确放弃' }).waitFor()
  if (!page.url().endsWith('/aippt-web/projects')) throw new Error('材料脏稿被原程序导航绕过')
  await page.screenshot({ path: '../../output/playwright/aippt-react-entry/05-材料键盘新建阻离开.png' })
  await page.getByRole('button', { name: '查看章节', exact: true }).click()
  await page.getByText('材料章节原文字与稳定引用。', { exact: true }).waitFor()
  if (await page.getByRole('spinbutton', { name: '目标页数', exact: true }).inputValue() !== '40') throw new Error('已有40页被裁值')
  await page.screenshot({ path: '../../output/playwright/aippt-react-entry/06-材料章节与40正式值.png' })
  await page.evaluate(() => window.aipptFixtureControl({ fail: true, project: { page_count: 39, input_revision: 8, audience: '远端新受众', tone: 'casual' } }))
  await page.getByRole('button', { name: '保存参数', exact: true }).click()
  await page.getByText('正式数据已变化。请先读取最新版本，核对后明确重新应用或放弃草稿。', { exact: true }).waitFor()
  await page.getByRole('button', { name: '核对最新版本', exact: true }).click()
  await page.getByText('最新名称：原界面技术夹具 · 智能办公套件', { exact: true }).waitFor()
  await page.screenshot({ path: '../../output/playwright/aippt-react-entry/07-参数冲突双方核对.png' })
  await page.evaluate(() => window.aipptFixtureControl({ fail: false }))
  await page.getByRole('button', { name: '重新应用我的修改', exact: true }).click()
  await page.getByRole('button', { name: '保存参数', exact: true }).waitFor({ state: 'visible' })
  for (let i = 0; i < 30; i++) { if (await page.getByRole('spinbutton', { name: '目标页数', exact: true }).inputValue() === '39') break; await page.waitForTimeout(20) }
  const state = await page.evaluate(() => window.aipptFixtureControl({}))
  const writes = state.requests.filter((request) => request.method === 'PATCH' && request.path === `/aippt/projects/${state.project.id}`)
  if (writes.length !== 2) throw new Error('参数冲突重应用请求数不符')
  const first = JSON.parse(writes[0].body); const second = JSON.parse(writes[1].body)
  if (first.input_revision !== 7 || second.input_revision !== 8 || 'page_count' in first || 'page_count' in second || 'audience' in second || first.title !== second.title || writes[0].key === writes[1].key) throw new Error('重应用未保持真实修改字段/明确新CAS')
  if (state.project.audience !== '远端新受众' || state.project.page_count !== 39) throw new Error('未修改字段覆盖了远端正式值')
  await page.screenshot({ path: '../../output/playwright/aippt-react-entry/08-参数明确恢复后.png' })
  return { focusPath, writes, formal: { title: state.project.title, page_count: state.project.page_count, audience: state.project.audience } }
}
