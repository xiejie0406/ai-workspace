import { _electron as electron } from 'playwright'
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join, resolve, dirname } from 'node:path'

// 仅检查用户指定的本机安装版；不建数据库、初始化账号、模拟登录或写入商品。
const root = resolve(process.env.ADEN_EXISTING_WORKSPACE_ROOT || '../../..')
const evidence = process.env.ADEN_E2E_EVIDENCE_DIR
assert(evidence && existsSync(join(evidence, '手工验收清单.md')), '先保存本轮完整验收清单')
const executablePath = 'C:/Users/admin/AppData/Local/Programs/Aden Local Test/Aden Local Test.exe'
assert(existsSync(executablePath), '本机安装版必须存在')
const config = JSON.parse(readFileSync(join(dirname(executablePath), 'resources/app/local-test.json'), 'utf8').replace(/^\uFEFF/, ''))
assert.equal(config.channel, 'local-test'); assert.equal(config.apiBaseUrl, 'http://127.0.0.1:18093')
const start = JSON.parse(readFileSync(join(root, '.runtime/four-projects/aden/installed-start-result.json'), 'utf8').replace(/^\uFEFF/, ''))
assert(start.desktopProfile && existsSync(start.desktopProfile), '沿用已核实的现有资料目录')
mkdirSync(evidence, { recursive: true })
const checks = []; const rendererErrors = []; const viewports = []
let app; let page
const save = () => writeFileSync(join(evidence, '安装版定向检查.json'), JSON.stringify({
  executablePath, apiBaseUrl: config.apiBaseUrl, databaseWrites: false, nativeDialogsSubstituted: false,
  checks, rendererErrors, viewports, completedAt: new Date().toISOString()
}, null, 2))
const capture = async (name) => {
  const path = join(evidence, name)
  await page.screenshot({ path, timeout: 15000 })
  return name
}
try {
  const env = { ...process.env }
  for (const key of ['ELECTRON_RUN_AS_NODE', 'ELECTRON_RENDERER_URL', 'ADEN_DESKTOP_SMOKE', 'ADEN_API_BASE_URL']) delete env[key]
  app = await electron.launch({ executablePath, args: [`--user-data-dir=${start.desktopProfile}`], env })
  page = await app.firstWindow(); page.setDefaultTimeout(20000)
  page.on('pageerror', error => rendererErrors.push(error.message))
  await page.getByRole('heading', { name: '登录 Aden', exact: true }).waitFor()
  assert.equal(await page.getByRole('combobox').count(), 0, '登录页没有工作空间选择')
  assert.equal(await page.locator('input[name=username]').inputValue(), '', '原库安装版不预填合成账号')
  assert.equal(await page.locator('input[name=password]').inputValue(), '', '原库安装版不预填密码')
  for (const width of [1024, 1440]) {
    await app.evaluate(({ BrowserWindow }, value) => BrowserWindow.getAllWindows()[0].setContentSize(value, 900), width)
    viewports.push({ page: '登录', requestedWidth: width, ...await page.evaluate(() => ({ width: innerWidth, height: innerHeight, deviceScale: devicePixelRatio })) })
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), '登录页不得整页横向溢出')
    checks.push({ name: `登录界面${width}宽度`, result: 'Pass', screenshot: await capture(`登录界面-${width}.png`) })
  }
  await page.getByLabel('显示密码', { exact: true }).click()
  assert.equal(await page.locator('input[name=password]').getAttribute('type'), 'text')
  await page.getByLabel('隐藏密码', { exact: true }).click()
  assert.equal(await page.locator('input[name=password]').getAttribute('type'), 'password')
  checks.push({ name: '密码显隐控件', result: 'Pass', screenshot: await capture('登录界面-密码控件.png') })
  await page.getByRole('link', { name: '插件目录与设置', exact: true }).click()
  await page.getByRole('heading', { name: '设置', exact: true }).waitFor()
  const copy = page.getByRole('button', { name: '复制插件目录', exact: true })
  await copy.waitFor()
  await page.waitForFunction(() => !document.querySelector('.setup-copy-row button')?.disabled)
  const directory = await page.getByLabel('插件目录', { exact: true }).inputValue()
  assert.equal(resolve(directory), resolve(dirname(executablePath), 'resources/collector/extension'))
  for (const width of [1024, 1440]) {
    await app.evaluate(({ BrowserWindow }, value) => BrowserWindow.getAllWindows()[0].setContentSize(value, 900), width)
    viewports.push({ page: '设置', requestedWidth: width, ...await page.evaluate(() => ({ width: innerWidth, height: innerHeight, deviceScale: devicePixelRatio })) })
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), '设置不得整页横向溢出')
    checks.push({ name: `设置界面${width}宽度`, result: 'Pass', screenshot: await capture(`设置界面-${width}.png`) })
  }
  await copy.click(); await page.getByRole('status').filter({ hasText: '已复制' }).waitFor()
  const copied = await app.evaluate(({ clipboard }) => clipboard.readText())
  assert.equal(copied, directory, '剪贴板只包含完整目录，无换行或引号')
  checks.push({ name: '匿名离线复制本机目录', result: 'Pass', screenshot: await capture('设置界面-复制成功.png') })
  assert.deepEqual(rendererErrors, [])
  checks.push({ name: '原库登录及保存导出', result: 'Blocked', reason: '本入口没有真实账号授权与业务写入；原库表/成员及原配置前提另行核对。' })
  save(); process.stdout.write('ADEN_INSTALLED_READONLY_UI_OK\n')
} catch (error) {
  const screenshot = page ? await capture('安装版定向检查-失败.png').catch(() => null) : null
  checks.push({ name: '安装版定向检查异常', result: 'Fail', reason: error.message, screenshot })
  save(); throw error
} finally {
  if (app) await app.close()
  // 保留用户现有资料目录、配置、数据库和全部证据；不进行清理。
}
