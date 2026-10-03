// 只渲染本项目静态设计图；不启动原项目、不安装依赖、不连接外部业务。
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');
const { chromium } = require('C:/Users/admin/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async () => {
  const root = path.resolve(__dirname, '..');
  const browser = await chromium.launch({ executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1180 }, deviceScaleFactor: 1 });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  await page.goto(pathToFileURL(path.join(root, '图稿', '界面设计图.html')).href);
  const scenes = [['workbench', '编码工作台'], ['approval', '审批与拒绝'], ['plan', '计划与审查'], ['tasks', '后台与恢复'], ['knowledge', '知识与连接'], ['channels', '终端与微信']];
  const results = [];
  for (const [id, name] of scenes) {
    await page.locator(`[data-show="${id}"]`).click();
    await page.screenshot({ path: path.join(root, '图稿', `${name}设计图.png`), fullPage: true });
    const metrics = await page.evaluate(() => ({ width: innerWidth, bodyWidth: document.documentElement.scrollWidth, active: document.querySelectorAll('.scene.active').length }));
    results.push({ scene: name, ...metrics });
  }
  await page.setViewportSize({ width: 1024, height: 1200 });
  await page.locator('[data-show="workbench"]').click();
  await page.screenshot({ path: path.join(root, '图稿', '编码工作台窄屏设计图.png'), fullPage: true });
  results.push({ scene: '1024工作台', ...await page.evaluate(() => ({ width: innerWidth, bodyWidth: document.documentElement.scrollWidth })) });
  await browser.close();
  fs.writeFileSync(path.join(root, '设计附录', '图稿渲染检查.json'), JSON.stringify({ kind: '静态设计图渲染，非业务验收', date: '2026-10-02', browser: '独立headless Edge进程', results, errors }, null, 2));
  console.log(JSON.stringify({ scenes: results, errors }));
})().catch(e => { console.error(e); process.exitCode = 1; });
