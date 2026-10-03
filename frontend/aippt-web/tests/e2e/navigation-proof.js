async (page) => {
  const captures = []
  await page.getByRole('link', { name: 'AI PPT AI PPT', exact: true }).click()
  await page.getByRole('link').filter({ hasText: '原界面技术夹具' }).click()
  await page.getByRole('textbox', { name: '编辑文字', exact: true }).first().waitFor()
  await page.evaluate(() => window.aipptFixtureControl({ hold: true, fail: false }))
  await page.getByRole('textbox', { name: '编辑文字', exact: true }).first().fill('连续返回绿测 · 保存后才离开')
  await page.getByRole('heading', { name: '原界面技术夹具 · 智能办公套件', exact: true }).click()
  for (let i = 0; i < 30; i++) { if ((await page.evaluate(() => window.aipptFixtureControl({}))).held === 1) break; await page.waitForTimeout(20) }
  const origin = page.url(); const originIdx = await page.evaluate(() => history.state.idx)
  captures.push({ stage: 'held', held: (await page.evaluate(() => window.aipptFixtureControl({}))).held, url: origin, idx: originIdx })
  await page.evaluate(() => history.back()); await page.waitForTimeout(80)
  await page.evaluate(() => history.back()); await page.waitForTimeout(120)
  captures.push({ stage: 'beforeRelease', url: page.url(), idx: await page.evaluate(() => history.state.idx) })
  await page.screenshot({ path: '../../output/playwright/aippt-react-entry/03-连续返回保存中.png' })
  if (page.url() !== origin || captures[1].idx !== originIdx) throw new Error('两次Back在保存完成前离开了原编辑页')
  await page.evaluate(() => window.aipptFixtureControl({ release: true, hold: false }))
  await page.waitForURL('**/aippt-web/projects')
  captures.push({ stage: 'afterRelease', url: page.url(), idx: await page.evaluate(() => history.state.idx) })
  if (captures[2].idx !== originIdx - 1) throw new Error('未按首个Back目标完成历史导航')
  await page.screenshot({ path: '../../output/playwright/aippt-react-entry/04-连续返回保存后.png' })
  return captures
}
