import test from 'node:test'
import assert from 'node:assert/strict'
import { Window } from 'happy-dom'

// 技术边界测试使用本地 DOM 和 stub host；不计为真实模型或业务联机验收。
const browser = new Window({ url: 'http://127.0.0.1/' })
// React 浏览器 bundle 的调度器在 Node 内不应创建永久引用的 MessagePort。
globalThis.MessageChannel = undefined
const nativeTimeout = globalThis.setTimeout
const pendingTimers = new Map()
globalThis.setTimeout = (run, delay, ...args) => {
  let id
  id = nativeTimeout(() => { pendingTimers.delete(id); run(...args) }, delay)
  pendingTimers.set(id, { delay, stack: new Error().stack })
  return id
}
const nativeClearTimeout = globalThis.clearTimeout
globalThis.clearTimeout = (id) => { pendingTimers.delete(id); nativeClearTimeout(id) }
for (const key of ['window', 'document', 'HTMLElement', 'HTMLInputElement', 'HTMLTextAreaElement', 'HTMLCanvasElement', 'Image', 'ResizeObserver', 'sessionStorage']) {
  Object.defineProperty(globalThis, key, { configurable: true, value: key === 'window' ? browser : browser[key] })
}
globalThis.requestAnimationFrame = browser.requestAnimationFrame.bind(browser)
globalThis.cancelAnimationFrame = browser.cancelAnimationFrame.bind(browser)
globalThis.getComputedStyle = browser.getComputedStyle.bind(browser)
let recordCanvasDraw = false
const canvasDrawCalls = []
browser.HTMLCanvasElement.prototype.getContext = function () {
  const canvas = this
  return new Proxy({
    canvas: this,
    measureText: () => ({ width: 20 }),
    getImageData: (x, y, width = 1, height = 1) => ({ width, height, data: new Uint8ClampedArray(width * height * 4) }),
    getLineDash: () => [],
    createLinearGradient: () => ({ addColorStop() {} }),
    createPattern: () => ({}),
  }, { get(target, key) {
    return key in target ? target[key] : (...args) => {
      if (recordCanvasDraw) canvasDrawCalls.push({ canvas, method: key, args })
    }
  } })
}
const api = await import('../.test-dist/entry.js')
const pause = () => new Promise((resolve) => setTimeout(resolve, 35))
async function waitFor(condition, message) {
  const deadline = Date.now() + 5000
  while (Date.now() < deadline) {
    if (condition()) return
    await pause()
  }
  assert.ok(condition(), message)
}
function host(options = {}) {
  return {
    identity: { userId: 'u1' }, page: 'create', params: {}, active: true,
    hasPermission: () => true,
    request: async () => ({ code: 200, data: [] }),
    navigate() {}, notify() {}, download() {}, ...options,
  }
}
const doc = { width: 1080, height: 1080, layers: [] }
function session(id, version = 7) {
  return { id, title: '技术测试会话', original_asset_id: 'a1', current_asset_id: 'a1', revision: 2, row_version: version, history_seq: 1, document: doc, previous_document: null, can_undo: true, can_redo: false, assets: [], created_at: 1790985600000, updated_at: 1790985600000 }
}

test('AjaxResult、TableDataInfo、空响应及错误明确解包', () => {
  assert.deepEqual(api.unwrap({ code: 200, data: { id: '1' } }), { id: '1' })
  assert.deepEqual(api.unwrap({ code: 200, rows: [1], total: 1 }), [1])
  assert.equal(api.unwrap({ code: 200, msg: '操作成功' }), undefined)
  assert.throws(() => api.unwrap({ code: 403, msg: '无权限' }), /无权限/)
})

test('Java 当前 snake_case DTO 状态、图片格式和毫秒时间显式转换', () => {
  const normalized = api.normalizeBusinessDto({ status: 'executing', goal: '去背景', created_at: 1790985600000, steps: [{ status: 'cancelled' }], asset: { id: 'a1', kind: 'image', source: 'upload', format: 'png', width: 32, height: 32, has_alpha: 1 }, item: { source_asset_id: 'a1', status: 'queued' } })
  assert.equal(normalized.status, 'running')
  assert.equal(normalized.steps[0].status, 'canceled')
  assert.equal(normalized.asset.kind, 'original')
  assert.equal(normalized.asset.image_format, 'PNG')
  assert.equal(normalized.asset.has_alpha, true)
  assert.equal(normalized.item.status, 'pending')
  assert.match(normalized.created_at, /^2026-/)
})

test('真实非空 Session 文档的图片与文字层不当成 Asset，revision=0选区仍读取', async () => {
  const calls = []
  const layer = { id: 'base', kind: 'image', name: '底图', width: 64, height: 80, asset_id: 'a1', opacity: 1,
    transform: { x: 0, y: 0, scale_x: 1, scale_y: 1, rotation: 0 } }
  const text = { id: 'text1', kind: 'text', width: 64, height: 20, text: '测试文字', font_size: 20,
    transform: { x: 0, y: 10, scale_x: 1, scale_y: 1, rotation: 0 } }
  const realSession = { ...session('s-real'), revision: 0, document: { width: 64, height: 80, layers: [layer, text] },
    assets: [{ id: 'a1', kind: 'original', width: 64, height: 80, image_format: 'PNG', source: 'upload' }] }
  const mask = { id: 'm1', kind: 'mask', width: 64, height: 80, image_format: 'PNG', source: 'selection' }
  const container = document.createElement('div'); document.body.append(container)
  const h = host({ page: 'editor', params: { sessionId: 's-real' }, request: async (config) => {
    calls.push(config)
    if (config.responseType === 'blob') return new Blob(['technical-png'], { type: 'image/png' })
    if (config.url === '/aipicture/sessions/s-real') return { code: 200, data: realSession }
    if (config.url.endsWith('/selection')) return { code: 200, data: { revision: 0, mask, markers: [] } }
    return { code: 200, data: [] }
  } })
  const normalized = api.normalizeBusinessDto(realSession)
  assert.deepEqual(normalized.document.layers, [layer, text])
  const runtime = new api.HostRuntime(h)
  try {
    const received = await api.createClient(runtime).get('/sessions/s-real')
    assert.deepEqual(received.document.layers, [layer, text])
    assert.match(received.assets[0].url, /^blob:/)
    assert.equal(calls.filter((item) => item.responseType === 'blob').length, 1)
    const mounted = api.mount(container, h)
    try {
      await waitFor(() => container.shadowRoot.querySelector('input[aria-label="会话标题"]'), '非空真实文档应打开编辑器')
      await waitFor(() => calls.some((item) => item.url.endsWith('/selection')), 'revision=0也应读取正式选区')
      await waitFor(() => calls.some((item) => item.url === '/aipicture/assets/m1/download'), 'revision=0选区应继续授权加载遮罩')
      const oldSignal = calls.findLast((item) => item.url === '/aipicture/sessions/s-real').signal
      mounted.update({ ...h, params: { sessionId: 's-other' }, request: async (config) => {
        calls.push(config)
        if (config.responseType === 'blob') return new Blob(['technical-png'], { type: 'image/png' })
        if (config.url === '/aipicture/sessions/s-other') return { code: 200, data: { ...realSession, id: 's-other', title: '会话二' } }
        if (config.url.endsWith('/selection')) return { code: 200 }
        return { code: 200, data: [] }
      } })
      await waitFor(() => container.shadowRoot.querySelector('input[aria-label="会话标题"]')?.value === '会话二', '相同revision切会话应重建编辑器')
      assert.equal(oldSignal.aborted, true, '切会话中止原请求上下文，避免旧选区/历史与资源沿用')
      assert.ok(calls.some((item) => item.url === '/aipicture/sessions/s-other/selection'))
    } finally { mounted.unmount() }
  } finally { runtime.dispose(); container.remove() }
})

test('正式 row_version 优先于 revision，旧列表不能回退版本，写入携带工具契约和幂等标识', async () => {
  const calls = []
  const runtime = new api.HostRuntime(host({ request: async (config) => { calls.push(config); return { code: 200, data: {} } } }))
  await runtime.hydrate(session('s1', 8))
  await runtime.hydrate(session('s1', 3))
  await api.createClient(runtime).post('/sessions/s1/tools', { tool: 'flip_layer', params: { direction: 'horizontal' } }, 'aipicture:tool:execute')
  assert.equal(calls[0].data.expectedVersion, 8)
  assert.equal(calls[0].data.schemaVersion, '1')
  assert.equal(calls[0].data.toolRegistryVersion, '2026.10-r2')
  assert.equal(calls[0].headers['Idempotency-Key'], calls[0].data.idempotencyKey)
  runtime.dispose()
})

test('权限不足在调用宿主前拒绝；原素材 URL 必须授权 blob，不采信源 DTO URL', async () => {
  let calls = 0
  const denied = new api.HostRuntime(host({ hasPermission: () => false, request: async () => { calls++; return {} } }))
  await assert.rejects(api.createClient(denied).post('/generations', {}, 'aipicture:image:generate'), /权限/)
  assert.equal(calls, 0)
  const paths = []
  const runtime = new api.HostRuntime(host({ request: async (config) => { paths.push(config); return new Blob(['png'], { type: 'image/png' }) } }))
  const asset = { id: 'a1', kind: 'original', image_format: 'PNG', width: 32, height: 32, url: 'https://untrusted.invalid/private.png' }
  const normalized = await runtime.hydrate(asset)
  assert.match(normalized.url, /^blob:/)
  assert.equal(paths[0].url, '/aipicture/assets/a1/download')
  assert.equal(paths[0].responseType, 'blob')
  const again = await runtime.hydrate(asset)
  assert.equal(again.url, normalized.url)
  assert.equal(paths.length, 1)
  runtime.dispose(); denied.dispose()
})

test('失活、reset 和卸载关闭订阅并中止旧请求，迟到响应不能进入缓存', async () => {
  let closeCount = 0
  let release
  let signal
  const runtime = new api.HostRuntime(host({
    subscribeRun: () => () => { closeCount++ },
    request: (config) => { signal = config.signal; return new Promise((resolve) => { release = resolve }) },
  }))
  runtime.subscribe('r1', () => {}, () => {})
  const pending = runtime.request({ url: '/aipicture/runs/r1', method: 'GET' })
  runtime.reset()
  assert.equal(signal.aborted, true)
  assert.equal(closeCount, 1)
  release({ code: 200, data: {} })
  await assert.rejects(pending, { name: 'AbortError' })
  runtime.dispose()
  await assert.rejects(runtime.request({ url: '/aipicture/runs/r1', method: 'GET' }), { name: 'AbortError' })
})

test('两个挂载各自拥有缩放、选区与图层选择，不共享 Zustand', () => {
  const first = api.createCanvasViewStore(); const second = api.createCanvasViewStore()
  first.getState().pan({ x: 18, y: 22 })
  assert.equal(second.getState().x, 0)
  const a = api.createEditorUiStore(); const b = api.createEditorUiStore()
  a.getState().selectLayer('layer-a')
  assert.equal(b.getState().selectedLayerId, null)
  first.dispose(); second.dispose()
})

test('选区撤销重做与新操作截断重做分支保留原算法', () => {
  const history = api.createSelectionHistory()
  history.commit({ kind: 'point', point: { x: .1, y: .2 } })
  history.commit({ kind: 'stroke', points: [{ x: .2, y: .3 }] })
  assert.equal(history.undo().length, 1)
  assert.equal(history.redo().length, 2)
  history.undo(); history.commit({ kind: 'point', point: { x: .8, y: .9 } })
  assert.equal(history.canRedo(), false)
})

test('宿主深链只转换页标识与参数，不创建认证路径或外部跳转', () => {
  assert.deepEqual(api.routeFromPath('/editor/s1'), { page: 'editor', params: { sessionId: 's1' } })
  assert.deepEqual(api.routeFromPath('/batch/r1'), { page: 'batch', params: { runId: 'r1' } })
  assert.throws(() => api.routeFromPath('/auth'), /未知/)
  assert.throws(() => api.routeFromPath('https://outside.invalid'), /未知/)
})

test('真实 mount/update/unmount 隔离 CSS，身份切换清空缓存，宿主导航回调可用', async () => {
  const container = document.createElement('div'); document.body.append(container)
  const routes = []
  const runtimeHost = host({ navigate: (route) => routes.push(route) })
  const mounted = api.mount(container, runtimeHost)
  try {
  await waitFor(() => container.shadowRoot.querySelector('.aipicture-root')?.textContent.includes('历史素材'), '等待创建页面完成渲染')
  assert.equal(document.head.querySelectorAll('style').length, 0)
  assert.equal(container.shadowRoot.querySelectorAll('style').length, 1)
  assert.match(container.shadowRoot.querySelector('.aipicture-root').textContent, /历史素材/)
  const guide = [...container.shadowRoot.querySelectorAll('a')].find((link) => link.textContent === '创作指南')
  guide.click()
  assert.equal(routes[0].page, 'guide')
  mounted.update(host({ identity: { userId: 'u2' }, page: 'batch' }))
  await waitFor(() => container.shadowRoot.querySelector('.aipicture-root')?.textContent.includes('最多 20 张'), '等待身份切换后的批量页面')
  assert.match(container.shadowRoot.querySelector('.aipicture-root').textContent, /最多 20 张/)
  mounted.update(host({ active: false }))
  await waitFor(() => container.shadowRoot.querySelector('.aipicture-root')?.textContent === '', '等待失活页面退出')
  assert.equal(container.shadowRoot.querySelector('.aipicture-root').textContent, '')
  mounted.unmount(); mounted.unmount()
  assert.equal(container.shadowRoot.childNodes.length, 0)
  } finally { mounted.unmount(); container.remove() }
})

test('两个编辑器的 Ctrl+Z 只操作所属 mount，影子输入和其他页面按键不会触发编辑', async () => {
  const first = document.createElement('div'); const second = document.createElement('div')
  document.body.append(first, second)
  const calls = []
  function editorHost(id) {
    return host({ page: 'editor', params: { sessionId: id }, request: async (config) => {
      calls.push({ id, config })
      if (config.url === `/aipicture/sessions/${id}` || config.url.endsWith('/undo')) return { code: 200, data: session(id) }
      if (config.url.endsWith('/selection')) return { code: 200 }
      return { code: 200, data: [] }
    } })
  }
  const a = api.mount(first, editorHost('s1')); const b = api.mount(second, editorHost('s2'))
  try {
  await waitFor(() => first.shadowRoot.querySelector('input[aria-label="会话标题"]') && second.shadowRoot.querySelector('input[aria-label="会话标题"]'), '等待两个编辑器完成渲染')
  const undoCalls = () => calls.filter((entry) => entry.config.url.endsWith('/undo'))
  const input = first.shadowRoot.querySelector('input[aria-label="会话标题"]')
  assert.ok(input)
  input.dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, composed: true }))
  document.body.dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, composed: true }))
  await pause(); assert.equal(undoCalls().length, 0)
  first.shadowRoot.querySelector('.aipicture-root').dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, composed: true }))
  await waitFor(() => undoCalls().length === 1, '等待所属编辑器撤销请求')
  assert.equal(undoCalls().length, 1)
  assert.equal(undoCalls()[0].id, 's1')
  a.update({ ...editorHost('s1'), active: false }); await waitFor(() => first.shadowRoot.querySelector('.aipicture-root')?.textContent === '', '等待编辑器失活')
  first.shadowRoot.querySelector('.aipicture-root').dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, composed: true }))
  await pause(); assert.equal(undoCalls().length, 1)
  } finally { a.unmount(); b.unmount(); first.remove(); second.remove() }
})

test('selection 异步回执后再读取正式遮罩，计划确认发送 plan_hash 与当前版本', async () => {
  const calls = []
  const runtime = new api.HostRuntime(host({ request: async (config) => {
    calls.push(config)
    if (config.method === 'POST' && config.url.endsWith('/selection')) return { code: 200, data: { id: 'r1', status: 'succeeded', result: {} } }
    if (config.method === 'GET') return { code: 200, data: { revision: 2, markers: [], mask: { id: 'm1', kind: 'mask', width: 32, height: 32, image_format: 'PNG' } } }
    return { code: 200, data: { id: 'turn1', status: 'executing', steps: [] } }
  } }))
  // 只在此例省略真实授权图片传输；授权 blob 另例已检查。
  runtime.hydrate = async (value) => value
  runtime.metadata = (path, body) => ({ ...body, expectedVersion: 7, schemaVersion: '1', toolRegistryVersion: '2026.10-r2', idempotencyKey: 'technical-test' })
  const sessions = api.createSessionsApi(api.createClient(runtime), runtime)
  const selection = await sessions.select('s1', { revision: 2, points: [{ x: .2, y: .3 }] })
  assert.equal(selection.mask.id, 'm1')
  assert.equal(calls[1].method, 'GET')
  await api.createAgentApi(api.createClient(runtime)).confirm('s1', 'turn1', 'hash1')
  assert.equal(calls[2].data.plan_hash, 'hash1')
  assert.equal(calls[2].data.expectedVersion, 7)
  runtime.dispose()
})

test('批量create对齐Java Batch DTO的母run，六类流水线输出原参数', async () => {
  const runtime = new api.HostRuntime(host({ request: async () => ({ code: 200, data: { id: 'b1', run: { id: 'b1', status: 'running' }, items: [] } }) }))
  const created = await api.createBatchesApi(api.createClient(runtime), runtime).create({ asset_ids: ['a1'], operations: [], formats: ['png'] })
  assert.equal(created.id, 'b1'); assert.equal(created.status, 'running')
  const operations = api.operationsOf({ ...api.EMPTY_DRAFT, selected: ['remove_background', 'replace_background', 'adjust_image', 'upscale_image', 'expand_canvas', 'prepare_delivery_sizes'], prompt: '木桌', scale: 3 })
  assert.equal(operations.length, 6)
  assert.equal(operations[3].params.scale, 3)
  runtime.dispose()
})

test('批量真实unknown DTO显示待核对数量与部分成功，未知项没有失败重试按钮', async () => {
  const asset = (id) => ({ id, kind: 'original', width: 64, height: 80, image_format: 'PNG', source: 'upload' })
  const run = { id: 'b-unknown', tool: 'batch_process', status: 'partial_success', progress: 100,
    stage: '', error: null, prompt: null, result: {}, candidates: [] }
  const batch = { run, created_at: 1790985600000, items: [
    { id: 'i-ok', source: asset('a-ok'), status: 'succeeded', error: null, outputs: [asset('o-ok')] },
    { id: 'i-unknown', source: asset('a-unknown'), status: 'external_result_unknown', error: '供应商响应丢失', outputs: [] },
  ] }
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, host({ page: 'batch', params: { runId: run.id }, request: async (config) => {
    if (config.responseType === 'blob') return new Blob(['technical-png'], { type: 'image/png' })
    return { code: 200, data: config.url.includes('/batches/') ? batch : run }
  } }))
  try {
    await waitFor(() => container.shadowRoot.querySelector('.aipicture-root')?.textContent.includes('待核对 1 张'), '等待实际未知项DTO进入批量详情')
    const content = container.shadowRoot.querySelector('.aipicture-root').textContent
    assert.match(content, /部分成功/)
    assert.match(content, /成功 1 张 · 失败 0 张 · 已取消 0 张 · 待核对 1 张 · 共 2 张/)
    assert.match(content, /外部结果待核对/)
    assert.match(content, /可能已经完成或产生费用/)
    assert.doesNotMatch(content, /等待中|仅重试此项/)
  } finally { mounted.unmount(); container.remove() }
})

test('无选区整层区域工具可操作，实际计划显示目标、固定选区、参数、输出与费用', async () => {
  const layer = { id: 'base', kind: 'image', name: '底图', width: 64, height: 80, asset_id: 'a1', visible: true, opacity: 1, locked: true,
    transform: { x: 0, y: 0, scale_x: 1, scale_y: 1, rotation: 0 } }
  const realSession = { ...session('s-plan'), revision: 0, document: { width: 64, height: 80, layers: [layer] },
    assets: [{ id: 'a1', kind: 'original', width: 64, height: 80, image_format: 'PNG', source: 'upload' }] }
  const step = { id: 's1', tool: 'replace_region', label: '局部替换', params: { layer_id: 'base', prompt: '把杯子变红' }, depends_on: [], run_id: null, status: 'pending' }
  const turn = { id: 't-plan', revision: 0, goal: '变红再移动', reply: '请确认', status: 'waiting_confirmation', error: null,
    created_at: 1790985600000, mask_asset_id: null, plan_hash: 'frozen-hash', steps: [step,
      { id: 's2', tool: 'move_layer', label: '移动图层', params: { layer_id: 'base', dx: 5 }, depends_on: ['s1'], run_id: null, status: 'pending' }] }
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, host({ page: 'editor', params: { sessionId: realSession.id }, request: async (config) => {
    if (config.responseType === 'blob') return new Blob(['technical-png'], { type: 'image/png' })
    if (config.url.endsWith('/messages')) return { code: 200, data: [turn] }
    if (config.url === '/aipicture/sessions/s-plan') return { code: 200, data: realSession }
    if (config.url.endsWith('/selection')) return { code: 200 }
    return { code: 200, data: [] }
  } }))
  try {
    await waitFor(() => container.shadowRoot.querySelector('.aipicture-root')?.textContent.includes('执行计划'), '等待实际多步计划回执')
    const root = container.shadowRoot.querySelector('.aipicture-root')
    assert.match(root.textContent, /目标图层：底图；无选区，作用整个目标图层/)
    assert.match(root.textContent, /结果写入正式画布/)
    assert.match(root.textContent, /描述：把杯子变红/)
    assert.match(root.textContent, /图像模型：调用已配置供应商，可能产生费用/)
    const buttons = [...root.querySelectorAll('button')]
    const erase = buttons.find((button) => button.textContent === '整层消除')
    const replace = buttons.find((button) => button.textContent === '整层替换')
    assert.ok(erase && !erase.disabled); assert.ok(replace && !replace.disabled)
    replace.click()
    await waitFor(() => root.querySelector('textarea[placeholder="例如：把杯子换成陶瓷马克杯"]'), '整层替换应打开可填写表单')
    assert.equal(root.querySelector('textarea[placeholder="例如：把杯子换成陶瓷马克杯"]').disabled, false)
    assert.match(root.textContent, /无选区，作用整个目标图层：底图/)
    const frozen = api.planSummary(step, { ...turn, mask_asset_id: 'fixed-mask', mask_width: 64, mask_height: 80 }, realSession.document)
    assert.equal(frozen.scope, '目标图层：底图；已固定选区 64×80，与图层取交集')
    assert.match(api.planSummary(turn.steps[1], turn, realSession.document).cost, /不调用付费图像模型/)
  } finally { mounted.unmount(); container.remove() }
})

function candidateFixture(options = {}) {
  const candidates = ['a-first', 'a-second'].map((id) => ({ id, kind: 'generated', width: 64, height: 80,
    image_format: 'PNG', source: 'generation', size_bytes: 8, created_at: 1790985600000 }))
  const run = { id: 'r-candidates', tool: 'generate_image', status: 'succeeded', progress: 100,
    stage: '', error: null, prompt: '技术候选原提示词', result: { asset_ids: candidates.map(({ id }) => id) }, candidates }
  const calls = [], downloads = [], navigations = [], notifications = []
  const h = host({ page: 'candidates', params: { runId: run.id },
    request: async (config) => {
      calls.push(config)
      if (config.responseType === 'blob') return new Blob(['original-candidate-bytes'], { type: 'image/png' })
      if (config.url === '/aipicture/runs/r-candidates') return { code: 200, data: run }
      if (config.url === '/aipicture/sessions') return { code: 200, data: session('s-adopt') }
      return { code: 200, data: [] }
    },
    download: (blob, filename) => downloads.push({ blob, filename }),
    navigate: (route) => navigations.push(route), notify: (notice) => notifications.push(notice), ...options })
  return { h, calls, downloads, navigations, notifications }
}

test('候选入口：放大关闭、授权原图下载、全部候选采用及返回创作', async () => {
  const fixture = candidateFixture()
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  try {
    const root = container.shadowRoot
    await waitFor(() => root.textContent.includes('选出一张'), '技术宿主等待候选快照')
    const originalButtons = [...root.querySelectorAll('button')].map((button) => button.textContent.trim())
    const zoom = root.querySelector('button[aria-label="放大候选图 2"]')
    assert.ok(zoom, `原候选实际按钮：${JSON.stringify(originalButtons)}；缺少放大入口`)
    assert.ok(root.querySelector('button[aria-label="下载候选图 2"]'), '应有授权下载入口')
    assert.ok(root.querySelector('a[data-candidate-create]'), '应有返回创作入口')
    zoom.click()
    await waitFor(() => root.querySelector('[role="dialog"]'), '打开实例内大图')
    const dialog = root.querySelector('[role="dialog"]')
    assert.match(dialog.querySelector('img').src, /^blob:/)
    assert.match(dialog.textContent, /64 × 80/)
    dialog.dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true }))
    await waitFor(() => !root.querySelector('[role="dialog"]'), 'Esc 关闭当前预览')
    root.querySelector('button[aria-label="下载候选图 2"]').click()
    await waitFor(() => fixture.downloads.length === 1, '文件由宿主下载')
    const downloaded = fixture.downloads[0]
    assert.equal(await downloaded.blob.text(), 'original-candidate-bytes')
    assert.equal(downloaded.filename, '候选图-2-a-second.png')
    const request = fixture.calls.findLast(({ responseType }) => responseType === 'blob')
    assert.equal(request.url, '/aipicture/assets/a-second/download')
    assert.equal(request.method, 'GET'); assert.equal(request.responseType, 'blob')
    assert.ok(request.signal)
    root.querySelector('button[aria-label="选择候选图 2"]').click()
    await pause()
    const adopt = [...root.querySelectorAll('button')].find((button) => button.textContent === '进入编辑')
    adopt.click()
    await waitFor(() => fixture.calls.some(({ url }) => url === '/aipicture/sessions'), '采用请求')
    const created = fixture.calls.find(({ url }) => url === '/aipicture/sessions').data
    assert.equal(created.current_asset_id, 'a-second')
    assert.deepEqual(created.asset_ids, ['a-first', 'a-second'])
    root.querySelector('a[data-candidate-create]').click()
    await waitFor(() => fixture.navigations.some(({ page }) => page === 'create'), '若依导航返回创作')
    assert.equal(sessionStorage.getItem('aipicture:prompt-draft:u1'), '技术候选原提示词')
  } finally { mounted.unmount(); container.remove(); sessionStorage.removeItem('aipicture:prompt-draft:u1') }
})

test('候选入口：下载权限拒绝与两个实例预览键盘互不影响', async () => {
  const first = document.createElement('div'), second = document.createElement('div'); document.body.append(first, second)
  const one = candidateFixture({ hasPermission: (permission) => permission !== 'aipicture:export:download' })
  const two = candidateFixture()
  const mountedOne = api.mount(first, one.h), mountedTwo = api.mount(second, two.h)
  try {
    await waitFor(() => first.shadowRoot.querySelector('button[aria-label="放大候选图 1"]') && second.shadowRoot.querySelector('button[aria-label="放大候选图 1"]'), '等待两个候选实例')
    const denied = first.shadowRoot.querySelector('button[aria-label="下载候选图 1"]')
    assert.equal(denied.disabled, true)
    const before = one.calls.length; denied.click(); await pause(); assert.equal(one.calls.length, before)
    first.shadowRoot.querySelector('button[aria-label="放大候选图 1"]').click()
    second.shadowRoot.querySelector('button[aria-label="放大候选图 1"]').click()
    await waitFor(() => first.shadowRoot.querySelector('[role="dialog"]') && second.shadowRoot.querySelector('[role="dialog"]'), '两个预览独立')
    first.shadowRoot.querySelector('[role="dialog"]').dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true }))
    await waitFor(() => !first.shadowRoot.querySelector('[role="dialog"]'), '关闭第一实例')
    assert.ok(second.shadowRoot.querySelector('[role="dialog"]'), '其他实例保留预览')
  } finally { mountedOne.unmount(); mountedTwo.unmount(); first.remove(); second.remove() }
})

test('候选入口：失活清理预览和对象URL，迟到下载不能跨实例', async () => {
  let release, oldSignal
  const fixture = candidateFixture()
  const initial = fixture.h.request
  let blobCalls = 0
  fixture.h.request = (config) => {
    if (config.responseType === 'blob' && ++blobCalls > 2) {
      oldSignal = config.signal
      return new Promise((resolve) => { release = resolve })
    }
    return initial(config)
  }
  const container = document.createElement('div'); document.body.append(container)
  const revoke = URL.revokeObjectURL, revoked = []
  URL.revokeObjectURL = (url) => { revoked.push(url); revoke(url) }
  const mounted = api.mount(container, fixture.h)
  try {
    await waitFor(() => container.shadowRoot.querySelector('button[aria-label="下载候选图 1"]'), '等待候选')
    container.shadowRoot.querySelector('button[aria-label="放大候选图 1"]').click()
    await waitFor(() => container.shadowRoot.querySelector('[role="dialog"]'), '打开预览')
    const oldUrl = container.shadowRoot.querySelector('[role="dialog"] img').src
    container.shadowRoot.querySelector('button[aria-label="下载候选图 1"]').click()
    await waitFor(() => release, '下载保持在途')
    mounted.update({ ...fixture.h, active: false })
    assert.equal(oldSignal.aborted, true)
    assert.equal(container.shadowRoot.querySelector('[role="dialog"]'), null)
    assert.ok(revoked.includes(oldUrl), '实例资源随失活释放')
    mounted.update(candidateFixture({ identity: { userId: 'u2' } }).h)
    release(new Blob(['late-old-user-file'], { type: 'image/png' }))
    await pause()
    assert.equal(fixture.downloads.length, 0)
    assert.equal(fixture.notifications.length, 0, '卸载后的旧请求不弹提示')
  } finally { mounted.unmount(); URL.revokeObjectURL = revoke; container.remove() }
})

function editorFixture(options = {}) {
  const calls = []
  const assets = ['a1', 'a2'].map((id) => ({ id, kind: 'original', width: 64, height: 80, image_format: 'PNG', source: 'upload' }))
  const document = { width: 64, height: 80, layers: [{ id: 'base', kind: 'image', name: '底图', width: 64, height: 80,
    asset_id: 'a1', visible: true, opacity: 1, locked: true, transform: { x: 0, y: 0, scale_x: 1, scale_y: 1, rotation: 0 } }] }
  const state = { ...session('s-final'), document, assets, can_redo: true }
  const h = host({ page: 'editor', params: { sessionId: state.id }, request: async (config) => {
    calls.push(config)
    if (config.responseType === 'blob') return new Blob(['technical-png'], { type: 'image/png' })
    if (config.url.endsWith('/messages')) return { code: 200, data: [] }
    if (config.url.endsWith('/selection')) return { code: 200 }
    if (config.url.endsWith('/history')) return { code: 200, data: [] }
    if (config.url.startsWith('/aipicture/sessions/s-final')) return { code: 200, data: state }
    return { code: 200, data: [] }
  }, ...options })
  return { h, calls, state }
}
function plannedTurn(status, steps = []) {
  return { id: 't-planning', revision: 2, goal: '当前正式规划', reply: status === 'waiting_confirmation' ? '请确认后执行' : '', status,
    error: null, plan_hash: 'fixed-plan-hash', steps, created_at: 1790985600000 }
}
const finalSteps = [
  { id: 'p1', tool: 'flip_layer', label: '水平翻转', params: { direction: 'horizontal' }, depends_on: [], run_id: null, status: 'pending' },
  { id: 'p2', tool: 'move_layer', label: '移动', params: { dx: 1 }, depends_on: ['p1'], run_id: null, status: 'pending' },
]
function fillInstruction(root, text) {
  const input = root.querySelector('textarea[aria-label="修图指令"]')
  Object.getOwnPropertyDescriptor(browser.HTMLTextAreaElement.prototype, 'value').set.call(input, text)
  input.dispatchEvent(new browser.Event('input', { bubbles: true, composed: true }))
}

test('规划恢复：重开时 planning 显示思考并轮询迟到待确认，禁止重复发送', async () => {
  let reads = 0
  const fixture = editorFixture()
  const ordinary = fixture.h.request
  fixture.h.request = (config) => config.url.endsWith('/messages')
    ? Promise.resolve({ code: 200, data: [++reads === 1 ? plannedTurn('planning') : plannedTurn('waiting_confirmation', finalSteps)] })
    : ordinary(config)
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  try {
    const root = container.shadowRoot
    await waitFor(() => root.textContent.includes('当前正式规划'), '读取已存规划中状态')
    assert.match(root.textContent, /思考中/, '规划中必须明确显示，不能是空卡片')
    fillInstruction(root, '不能在正式规划中重复发送')
    await pause()
    const send = [...root.querySelectorAll('button')].find((button) => button.textContent === '思考中…')
    assert.ok(send?.disabled, '正式 planning 阻止新发送')
    await waitFor(() => root.textContent.includes('执行计划'), '不重发规划，只轮询正式消息至待确认')
    assert.ok(reads >= 2)
    assert.equal(fixture.calls.some(({ method, url }) => method === 'POST' && url.endsWith('/messages')), false)
  } finally { mounted.unmount(); container.remove() }
})

test('规划恢复：发送传输失败仍回读正式 planning，随后显示待确认或失败', async () => {
  for (const terminal of ['waiting_confirmation', 'failed']) {
    let sent = false, reads = 0
    const fixture = editorFixture()
    const ordinary = fixture.h.request
    fixture.h.request = async (config) => {
      if (config.url.endsWith('/messages') && config.method === 'POST') {
        sent = true
        throw new Error('技术模拟共享请求超时')
      }
      if (config.url.endsWith('/messages')) {
        if (!sent) return { code: 200, data: [] }
        reads++
        const turn = reads === 1 ? plannedTurn('planning') : plannedTurn(terminal, terminal === 'waiting_confirmation' ? finalSteps : [])
        if (turn.status === 'failed') turn.error = '真实规划服务暂不可用'
        return { code: 200, data: [turn] }
      }
      return ordinary(config)
    }
    const container = document.createElement('div'); document.body.append(container)
    const mounted = api.mount(container, fixture.h)
    try {
      const root = container.shadowRoot
      await waitFor(() => root.querySelector('textarea[aria-label="修图指令"]'), '等待指令框')
      fillInstruction(root, '先翻转再移动')
      await waitFor(() => [...root.querySelectorAll('button')].some((button) => button.textContent === '发送' && !button.disabled), '可发送原指令')
      ;[...root.querySelectorAll('button')].find((button) => button.textContent === '发送').click()
      await waitFor(() => root.textContent.includes(terminal === 'failed' ? '真实规划服务暂不可用' : '执行计划'), '传输失败之后仍读正式规划结果')
      assert.equal(sent, true); assert.ok(reads >= 2)
      assert.match(root.textContent, /技术模拟共享请求超时/, '传输错误本身仍明确保留')
      if (terminal === 'failed') {
        assert.match(root.textContent, /规划未产生步骤，请重新发送指令/)
        assert.equal([...root.querySelectorAll('button')].some((button) => button.textContent === '重试失败步骤'), false, '没有步骤不能重试步骤')
        fillInstruction(root, '重新规划一条新指令')
        await waitFor(() => [...root.querySelectorAll('button')].some((button) => button.textContent === '发送' && !button.disabled), '规划失败正式回读后不锁新指令')
      }
    } finally { mounted.unmount(); container.remove() }
  }
})

test('权限独立：会话编辑与工具执行的三种组合分别限制撤销、切图和工具', async () => {
  for (const [edit, execute] of [[true, false], [false, true], [false, false]]) {
    const fixture = editorFixture({ hasPermission: (permission) => permission === 'aipicture:session:edit' ? edit : permission === 'aipicture:tool:execute' ? execute : true })
    const container = document.createElement('div'); document.body.append(container)
    const mounted = api.mount(container, fixture.h)
    try {
      const root = container.shadowRoot
      await waitFor(() => root.querySelector('input[aria-label="会话标题"]'), '等待正式会话')
      const find = (label) => [...root.querySelectorAll('button')].find((button) => button.textContent === label)
      assert.equal(find('撤销').disabled, !edit, `session:edit=${edit} tool:execute=${execute} 撤销权限`)
      assert.equal(find('重做').disabled, !edit, '重做独立使用会话权限')
      assert.equal(root.querySelector('button[title^="采用这张"]').disabled, !edit, '切图独立使用会话权限')
      for (const label of ['去背景', '裁剪', '调色', '拆层', '整层消除', '整层替换', '超分']) {
        assert.equal(find(label).disabled, !execute, `${label}独立使用工具权限`)
      }
      if (edit) {
        find('撤销').click()
        await waitFor(() => fixture.calls.some(({ url }) => url.endsWith('/undo')), '无工具权限仍可撤销')
        root.querySelector('button[title^="采用这张"]').click()
        await waitFor(() => fixture.calls.some(({ method }) => method === 'PATCH'), '无工具权限仍可切图')
      } else {
        root.querySelector('.aipicture-root').dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, composed: true }))
        await pause()
        assert.equal(fixture.calls.some(({ url }) => url.endsWith('/undo')), false, '快捷键也不可绕过会话编辑权限')
      }
    } finally { mounted.unmount(); container.remove() }
  }
})

test('会话在途恢复：正式 active_run_id 保留等待锁，终态回读后解锁', async () => {
  const fixture = editorFixture()
  fixture.state.active_run_id = 'r-resume'
  let runReads = 0, finished = false
  const ordinary = fixture.h.request
  fixture.h.request = async (config) => {
    if (config.url === '/aipicture/runs/r-resume') {
      runReads++
      if (finished) fixture.state.active_run_id = null
      return { code: 200, data: { id: 'r-resume', tool: 'remove_background', status: finished ? 'succeeded' : 'running', progress: 50, result: {} } }
    }
    return ordinary(config)
  }
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  try {
    const root = container.shadowRoot
    await waitFor(() => root.querySelector('input[aria-label="会话标题"]'), '读取已存当前在途任务')
    const button = (label) => [...root.querySelectorAll('button')].find((item) => item.textContent.startsWith(label))
    assert.ok(button('撤销').disabled)
    assert.ok(button('去背景').disabled)
    assert.ok(root.querySelector('button[title^="采用这张"]').disabled)
    finished = true
    await waitFor(() => button('撤销') && !button('撤销').disabled, '终态回读解除会话等待锁')
    assert.ok(runReads >= 2)
    assert.equal(button('去背景').disabled, false)
  } finally { mounted.unmount(); container.remove() }
})

test('首次读取：403、503和网络错误保留原信息并可重试，四个正式页面不误报不存在', async () => {
  const pages = [
    ['editor', { sessionId: 's-final' }, '/aipicture/sessions/s-final', '会话标题'],
    ['marketing', { sessionId: 's-final' }, '/aipicture/sessions/s-final', '投放尺寸'],
    ['batch', { runId: 'b-final' }, '/aipicture/batches/b-final', '批量进度'],
    ['candidates', { runId: 'r-final' }, '/aipicture/runs/r-final', '选出一张'],
  ]
  for (const [page, params, path, success] of pages) {
    for (const mode of ['403', '503', 'network']) {
      let recovered = false, reads = 0
      const message = `${page}-${mode}-正式读取原因`
      const fixture = editorFixture({ page, params })
      const ordinary = fixture.h.request
      fixture.h.request = async (config) => {
        if (config.url === path) {
          reads++
          if (!recovered) {
            if (mode === '403') return { code: 403, msg: message }
            throw Object.assign(new Error(message), mode === '503' ? { status: 503, response: { status: 200, data: { code: 503 } } } : {})
          }
          return { code: 200, data: page === 'batch'
            ? { run: { id: 'b-final', status: 'succeeded', result: {} }, items: [], created_at: 1790985600000 }
            : page === 'candidates' ? { id: 'r-final', status: 'succeeded', candidates: [], result: {} }
            : fixture.state }
        }
        if (config.url === '/aipicture/runs/b-final') return { code: 200, data: { id: 'b-final', status: 'succeeded', result: {} } }
        return ordinary(config)
      }
      const container = document.createElement('div'); document.body.append(container)
      const mounted = api.mount(container, fixture.h)
      try {
        const root = container.shadowRoot
        await waitFor(() => root.querySelector('.aipicture-root').textContent.includes(message), `${page}应明示${mode}正式错误`)
        assert.doesNotMatch(root.querySelector('.aipicture-root').textContent, /会话不存在|任务不存在/)
        const retry = [...root.querySelectorAll('button')].find((button) => button.textContent === '重新读取')
        assert.ok(retry, '初次读取失败有原请求重试入口')
        const before = reads
        recovered = true; retry.click()
        await waitFor(() => success === '会话标题' ? root.querySelector('input[aria-label="会话标题"]') : root.textContent.includes(success), `${page}重试成功后显示业务页`)
        assert.ok(reads > before)
        assert.equal(root.querySelector('[role="alert"]'), null, '成功读取后错误反馈消失')
      } finally { mounted.unmount(); container.remove() }
    }
  }
})

test('首次读取：只有真实404和明确null才显示不存在，四页均保留返回入口', async () => {
  for (const page of ['editor', 'marketing', 'batch', 'candidates']) {
    for (const mode of ['404', 'business404', 'null']) {
      const fixture = editorFixture({ page, params: { sessionId: 's-final', runId: 'r-final' } })
      const ordinary = fixture.h.request
      const path = page === 'batch' ? '/aipicture/batches/r-final' : page === 'candidates' ? '/aipicture/runs/r-final' : '/aipicture/sessions/s-final'
      fixture.h.request = async (config) => {
        if (config.url === path) {
          if (mode === 'null') return { code: 200, data: null }
          if (mode === 'business404') throw Object.assign(new Error('正式业务404'), { status: 404, response: { status: 200, data: { code: 404 } } })
          throw Object.assign(new Error('HTTP404正式对象不存在'), { response: { status: 404 } })
        }
        if (config.url === '/aipicture/runs/r-final') return { code: 200, data: { id: 'r-final', status: 'succeeded', result: {} } }
        return ordinary(config)
      }
      const container = document.createElement('div'); document.body.append(container)
      const mounted = api.mount(container, fixture.h)
      try {
        const root = container.shadowRoot
        await waitFor(() => /会话不存在|任务不存在/.test(root.querySelector('.aipicture-root').textContent), `${page} ${mode}应显示不存在`)
        assert.ok(root.querySelector('a'), '对象不存在仍提供原页面返回入口')
        assert.equal(root.querySelector('[role="alert"]'), null)
      } finally { mounted.unmount(); container.remove() }
    }
  }
})

test('文字字体：画布与内联编辑共用中文默认字体，宿主覆盖及更新仅影响当前实例', async () => {
  const first = editorFixture()
  const second = editorFixture({ textFontFamily: '  "Microsoft YaHei"  ' })
  for (const [fixture, id] of [[first, 'text-font-1'], [second, 'text-font-2']]) {
    fixture.state.document.layers.push({ id, kind: 'text', name: '中文字形', text: id, width: 64, height: 20,
      font_size: 20, fill: '#141414', visible: true, opacity: 1, locked: false,
      transform: { x: 32, y: 25, scale_x: 1, scale_y: 1, rotation: 0 } })
  }
  const containers = [document.createElement('div'), document.createElement('div')]
  containers.forEach((container) => document.body.append(container))
  const mounted = [api.mount(containers[0], first.h), api.mount(containers[1], second.h)]
  const textOf = (index) => api.Konva.stages
    .filter((stage) => stage.container()?.getRootNode() === containers[index].shadowRoot)
    .flatMap((stage) => stage.find('Text'))
    .find((text) => text.text() === `text-font-${index + 1}`)
  const editorOf = (index) => [...containers[index].shadowRoot.querySelectorAll('textarea')]
    .find((textarea) => textarea.value === `text-font-${index + 1}`)
  try {
    await waitFor(() => textOf(0) && textOf(1), '两个实例应使用真实业务文档渲染Konva文字层')
    assert.equal(textOf(0).fontFamily(), api.DEFAULT_TEXT_FONT_FAMILY)
    assert.equal(textOf(1).fontFamily(), '"Microsoft YaHei"')
    textOf(0).fire('dblclick', { evt: new browser.MouseEvent('dblclick') }, true)
    await waitFor(() => editorOf(0), '双击文字层应打开原内联编辑框')
    assert.equal(editorOf(0).style.fontFamily, api.DEFAULT_TEXT_FONT_FAMILY)
    mounted[0].update({ ...first.h, textFontFamily: ' "Microsoft YaHei", sans-serif ' })
    await waitFor(() => textOf(0)?.fontFamily() === '"Microsoft YaHei", sans-serif' && editorOf(0)?.style.fontFamily === '"Microsoft YaHei", sans-serif', '宿主更新应同步画布与仍打开的内联编辑框')
    assert.equal(textOf(1).fontFamily(), '"Microsoft YaHei"', '另一个实例不得继承字体更新')
    mounted[0].update({ ...first.h, textFontFamily: '   ' })
    await waitFor(() => textOf(0)?.fontFamily() === api.DEFAULT_TEXT_FONT_FAMILY && editorOf(0)?.style.fontFamily === api.DEFAULT_TEXT_FONT_FAMILY, '空白配置退回中文默认栈')
    editorOf(0).dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true }))
    await waitFor(() => !editorOf(0), '关闭第一个实例的编辑框')
    textOf(1).fire('dblclick', { evt: new browser.MouseEvent('dblclick') }, true)
    await waitFor(() => editorOf(1), '第二个实例保持自身受控字体')
    assert.equal(editorOf(1).style.fontFamily, '"Microsoft YaHei"')
    assert.equal(textOf(0).fontFamily(), api.DEFAULT_TEXT_FONT_FAMILY)
    for (const fixture of [first, second]) {
      assert.equal(fixture.calls.filter((call) => call.method === 'POST').length, 0, '字体配置与打开编辑框不改图层协议或发工具任务')
    }
  } finally {
    mounted.forEach((instance) => instance.unmount())
    containers.forEach((container) => container.remove())
  }
})

test('文字字框裁切：实际绘制先裁切局部框，翻转旋转及拖动选择都作用同一Group', async () => {
  const fixture = editorFixture()
  const layer = { id: 'clip-text', kind: 'text', name: '中文字框', text: '中文价格', width: 192, height: 24,
    font_size: 64, fill: '#141414', visible: true, opacity: 0.7, locked: false,
    transform: { x: 40, y: 80, scale_x: -1.5, scale_y: 1.5, rotation: 30 } }
  fixture.state.document = { width: 320, height: 320, layers: [layer] }
  const ordinary = fixture.h.request
  fixture.h.request = async (config) => {
    if (config.url === '/aipicture/sessions/s-final/tools') {
      fixture.calls.push(config)
      const { tool, params } = config.data
      assert.ok(['move_layer', 'scale_layer'].includes(tool))
      const current = fixture.state.document.layers[0]
      const transform = { ...current.transform, x: params.x, y: params.y }
      if (tool === 'scale_layer') {
        transform.scale_x = Math.sign(transform.scale_x) * params.scale_x
        transform.scale_y = Math.sign(transform.scale_y) * params.scale_y
      }
      fixture.state.document = { ...fixture.state.document, layers: [{ ...current, transform }] }
      fixture.state.row_version++
      return { code: 200, data: { run: { id: 'r-clip-move', status: 'succeeded', result: {} }, session: fixture.state } }
    }
    return ordinary(config)
  }
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  const textOf = () => api.Konva.stages.filter((stage) => stage.container()?.getRootNode() === container.shadowRoot)
    .flatMap((stage) => stage.find('Text')).find((text) => text.text() === layer.text)
  try {
    await waitFor(textOf, '真实文档应渲染超过字框高度的64px文字')
    const text = textOf()
    const group = text.getParent()
    canvasDrawCalls.length = 0
    recordCanvasDraw = true
    try { group.drawScene() } finally { recordCanvasDraw = false }
    const rectangle = canvasDrawCalls.findIndex((call) => call.method === 'rect' && call.args.join(',') === '0,0,192,24')
    const clipping = canvasDrawCalls.findIndex((call, index) => index > rectangle && call.method === 'clip')
    const lettering = canvasDrawCalls.findIndex((call) => call.method === 'fillText')
    assert.ok(rectangle >= 0 && clipping > rectangle && lettering > clipping, '公共Konva绘制路径必须在文字填充前执行局部192×24裁切')
    assert.equal(group.getClassName(), 'Group', '注册及操作文字框Group，子文字不再独立变换')
    assert.equal(group.opacity(), 0.7)
    const assertCorners = (origin, magnitude = 1.5) => {
      const radians = Math.PI / 6
      for (const corner of [{ x: 0, y: 0 }, { x: 192, y: 0 }, { x: 192, y: 24 }, { x: 0, y: 24 }]) {
        const dx = (corner.x - 96) * -magnitude, dy = (corner.y - 12) * magnitude
        const expected = { x: origin.x + 96 + dx * Math.cos(radians) - dy * Math.sin(radians),
          y: origin.y + 12 + dx * Math.sin(radians) + dy * Math.cos(radians) }
        const actual = group.getTransform().point(corner)
        assert.ok(Math.abs(actual.x - expected.x) < 1e-8 && Math.abs(actual.y - expected.y) < 1e-8, 'clip框与文字经同一中心偏移、水平翻转及旋转矩阵')
        assert.deepEqual(text.getTransform().point(corner), corner, '子文字局部坐标不得再次偏移或变换')
      }
    }
    assertCorners(layer.transform)
    text.fire('click', { evt: new browser.MouseEvent('click') }, true)
    await waitFor(() => group.getLayer().find('Transformer').some((transformer) => transformer.nodes()[0] === group), '文字事件上冒后原选择器必须注册可操作的Group')
    const transformedBox = group.getClientRect({ skipTransform: true, skipStroke: true })
    assert.deepEqual(transformedBox, { x: 0, y: 0, width: 192, height: 24 })
    group.fire('dragstart', { evt: new browser.MouseEvent('mousedown') }, true)
    group.position({ x: layer.transform.x + 96 + 17, y: layer.transform.y + 12 - 9 })
    group.fire('dragend', { evt: new browser.MouseEvent('mouseup') }, true)
    await waitFor(() => fixture.calls.some((call) => call.url.endsWith('/tools')), '拖动Group仍应沿原move_layer工具写回')
    const move = fixture.calls.find((call) => call.url.endsWith('/tools'))
    assert.deepEqual(move.data.params, { layer_id: 'clip-text', x: 57, y: 71 })
    assert.equal(move.data.expectedVersion, 7)
    await waitFor(() => group.x() === 153 && group.y() === 83 && group.draggable(), '正式回读后Group位置及可交互状态一致')
    assertCorners({ x: 57, y: 71 })
    await pause()
    const scaler = group.getLayer().find('Transformer').find((transformer) => transformer.nodes()[0] === group)
    scaler.fire('transformstart')
    group.scale({ x: -2, y: 2 }); group.position({ x: 156, y: 87 })
    scaler.fire('transformend')
    await waitFor(() => fixture.calls.some((call) => call.url.endsWith('/tools') && call.data.tool === 'scale_layer'), '注册Group后的原拖角控制应写回统一倍率和中心位置')
    const scale = fixture.calls.find((call) => call.url.endsWith('/tools') && call.data.tool === 'scale_layer')
    assert.deepEqual(scale.data.params, { layer_id: 'clip-text', scale_x: 2, scale_y: 2, x: 60, y: 75 })
    assert.equal(scale.data.expectedVersion, 8)
    await waitFor(() => group.scaleX() === -2 && group.scaleY() === 2 && group.draggable(), '正式缩放回读保持已有水平翻转')
    assertCorners({ x: 60, y: 75 }, 2)
    text.fire('dblclick', { evt: new browser.MouseEvent('dblclick') }, true)
    await waitFor(() => [...container.shadowRoot.querySelectorAll('textarea')].some((textarea) => textarea.value === layer.text), '裁切后子文字双击仍打开原内联编辑')
    assert.equal(group.opacity(), 0, '编辑时整组文字隐藏，避免重复显示')
  } finally { recordCanvasDraw = false; mounted.unmount(); container.remove() }
})

test('独立两轴：实际图片与两文字层按持久幅值变换，等比预览及拖角待回写保持等比', async () => {
  const previousImage = globalThis.Image
  const previousImageDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'Image')
  // 此检查只核对实际Konva节点几何；受控解码不证明图片像素或授权下载通过。
  Object.defineProperty(globalThis, 'Image', { configurable: true, value: function () {
    const image = new previousImage()
    image.width = 64; image.height = 80
    let url = ''
    Object.defineProperty(image, 'src', { configurable: true, get: () => url, set(value) {
      url = value; queueMicrotask(() => image.onload?.(new browser.Event('load')))
    } })
    return image
  } })
  const fixture = editorFixture()
  const layers = [
    { ...fixture.state.document.layers[0], locked: false, transform: { x: 60, y: 250, scale_x: 1.5, scale_y: 0.75, rotation: -15 } },
    { id: 'axes-text-x', kind: 'text', name: '横向文字', text: '横向文字', width: 128, height: 64, font_size: 32,
      visible: true, opacity: 1, locked: false, transform: { x: 30, y: 120, scale_x: -2, scale_y: 0.5, rotation: 30 } },
    { id: 'axes-text-y', kind: 'text', name: '纵向文字', text: '纵向文字', width: 96, height: 40, font_size: 24,
      visible: true, opacity: 1, locked: false, transform: { x: 240, y: 120, scale_x: 0.5, scale_y: -2, rotation: -20 } },
  ]
  fixture.state.document = { width: 512, height: 512, layers }
  const ordinary = fixture.h.request
  let release
  const pendingReceipt = new Promise((resolve) => { release = resolve })
  fixture.h.request = async (config) => {
    if (config.url.endsWith('/tools')) {
      fixture.calls.push(config)
      await pendingReceipt
      const { layer_id, scale_x, scale_y, x, y } = config.data.params
      fixture.state.document = { ...fixture.state.document, layers: layers.map((layer) => layer.id === layer_id
        ? { ...layer, transform: { ...layer.transform, x, y, scale_x: Math.sign(layer.transform.scale_x) * scale_x, scale_y: Math.sign(layer.transform.scale_y) * scale_y } }
        : layer) }
      fixture.state.row_version++
      return { code: 200, data: { run: { id: 'r-axes-scale', status: 'succeeded', result: {} }, session: fixture.state } }
    }
    return ordinary(config)
  }
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  const stages = () => api.Konva.stages.filter((stage) => stage.container()?.getRootNode() === container.shadowRoot)
  const nodeOf = (layer) => layer.kind === 'text'
    ? stages().flatMap((stage) => stage.find('Text')).find((text) => text.text() === layer.text)?.getParent()
    : stages().flatMap((stage) => stage.find('Image'))[0]
  try {
    await waitFor(() => layers.every(nodeOf), '应渲染实际图片节点和两个文字框Group')
    for (const layer of layers) {
      const node = nodeOf(layer)
      const angle = layer.transform.rotation * Math.PI / 180
      const corners = [{ x: 0, y: 0 }, { x: layer.width, y: 0 }, { x: layer.width, y: layer.height }, { x: 0, y: layer.height }]
      const expectedCorners = corners.map((corner) => {
        const dx = (corner.x - layer.width / 2) * layer.transform.scale_x
        const dy = (corner.y - layer.height / 2) * layer.transform.scale_y
        return { x: layer.transform.x + layer.width / 2 + dx * Math.cos(angle) - dy * Math.sin(angle),
          y: layer.transform.y + layer.height / 2 + dx * Math.sin(angle) + dy * Math.cos(angle) }
      })
      corners.forEach((corner, index) => {
        const actual = node.getTransform().point(corner), expected = expectedCorners[index]
        assert.ok(Math.abs(actual.x - expected.x) < 1e-8 && Math.abs(actual.y - expected.y) < 1e-8, `${layer.id}实际节点四角必须分别使用持久scale_x和scale_y`)
      })
      const rect = node.getClientRect({ relativeTo: node.getParent(), skipStroke: true })
      const xs = expectedCorners.map((corner) => corner.x), ys = expectedCorners.map((corner) => corner.y)
      const expectedRect = { x: Math.min(...xs), y: Math.min(...ys), width: Math.max(...xs) - Math.min(...xs), height: Math.max(...ys) - Math.min(...ys) }
      for (const key of ['x', 'y', 'width', 'height']) assert.ok(Math.abs(rect[key] - expectedRect[key]) < 1e-8, `${layer.id}实际多层矩形${key}与独立计算一致`)
    }
    const text = nodeOf(layers[1]).findOne('Text')
    text.fire('click', { evt: new browser.MouseEvent('click') }, true)
    const root = container.shadowRoot
    ;[...root.querySelectorAll('button')].find((button) => button.textContent === '图层').click()
    await waitFor(() => [...root.querySelectorAll('label')].some((label) => label.textContent.startsWith('图层缩放')), '使用原图层缩放滑杆验证等比预览')
    const range = [...root.querySelectorAll('label')].find((label) => label.textContent.startsWith('图层缩放')).querySelector('input')
    Object.getOwnPropertyDescriptor(browser.HTMLInputElement.prototype, 'value').set.call(range, '1.25')
    range.dispatchEvent(new browser.Event('input', { bubbles: true, composed: true }))
    await waitFor(() => nodeOf(layers[1]).scaleX() === -1.25 && nodeOf(layers[1]).scaleY() === 1.25, 'scalar preview仍明确表示等比并保留翻转符号')
    assert.equal(nodeOf(layers[2]).scaleY(), -2, '另一文字层保持自己的独立轴')
    ;[...root.querySelectorAll('button')].find((button) => button.textContent === '图层').click()
    await waitFor(() => nodeOf(layers[1]).scaleX() === -2 && nodeOf(layers[1]).scaleY() === 0.5, '撤去预览后恢复正式非等比文档')
    const group = nodeOf(layers[1])
    const scaler = group.getLayer().find('Transformer').find((transformer) => transformer.nodes()[0] === group)
    scaler.fire('transformstart'); group.scale({ x: -1.75, y: 1.75 }); scaler.fire('transformend')
    await waitFor(() => fixture.calls.some((call) => call.url.endsWith('/tools')), '拖角调用原scale_layer')
    await pause()
    assert.equal(group.scaleX(), -1.75)
    assert.equal(group.scaleY(), 1.75, 'pinned scalar在正式回执等待期间继续等比，不能回落到旧持久纵轴')
    release()
    await waitFor(() => group.draggable() && fixture.state.row_version === 8, '正式等比回执解锁原操作')
    assert.equal(group.scaleY(), 1.75)
  } finally { release(); mounted.unmount(); container.remove(); Object.defineProperty(globalThis, 'Image', previousImageDescriptor) }
})

test('独立两轴文字编辑：局部字号与CSS双轴围绕正式中心，最小编辑尺寸仍可读', async () => {
  for (const mode of ['normal', 'small']) {
    const fixture = editorFixture()
    const layer = mode === 'normal'
      ? { id: 'axes-editor', kind: 'text', name: '独立轴文字', text: '非等比编辑', width: 128, height: 64, font_size: 32,
        visible: true, opacity: 1, locked: false, transform: { x: 40, y: 80, scale_x: -2, scale_y: 0.5, rotation: 30 } }
      : { id: 'axes-small', kind: 'text', name: '小字框', text: '小字框', width: 20, height: 10, font_size: 8,
        visible: true, opacity: 1, locked: false, transform: { x: 20, y: 30, scale_x: 0.1, scale_y: -0.2, rotation: 0 } }
    fixture.state.document = { width: 320, height: 320, layers: [layer] }
    const container = document.createElement('div'); document.body.append(container)
    const mounted = api.mount(container, fixture.h)
    try {
      const textOf = () => api.Konva.stages.filter((stage) => stage.container()?.getRootNode() === container.shadowRoot)
        .flatMap((stage) => stage.find('Text')).find((text) => text.text() === layer.text)
      await waitFor(textOf, '读取合法非等比文字文档')
      textOf().fire('dblclick', { evt: new browser.MouseEvent('dblclick') }, true)
      const editorOf = () => [...container.shadowRoot.querySelectorAll('textarea')].find((textarea) => textarea.value === layer.text)
      await waitFor(editorOf, '使用原双击文字编辑入口')
      const style = editorOf().style
      const match = /^rotate\(([-.\d]+)deg\) scale\(([-.\d]+),\s*([-.\d]+)\)$/.exec(style.transform)
      assert.ok(match, '编辑框用CSS独立轴变换，而非把纵轴和字号都取横轴幅值')
      const sx = Number(match[2]), sy = Number(match[3])
      assert.equal(sx, Math.abs(layer.transform.scale_x), '编辑文字仍沿原正向可读方式')
      assert.equal(sy, Math.abs(layer.transform.scale_y))
      const width = parseFloat(style.width), height = parseFloat(style.height), font = parseFloat(style.fontSize)
      assert.equal(parseFloat(style.left) + width / 2, layer.transform.x + layer.width / 2)
      assert.equal(parseFloat(style.top) + height / 2, layer.transform.y + layer.height / 2, 'CSS缩放旋转围绕正式图层中心')
      if (mode === 'normal') {
        assert.equal(width * sx, 256)
        assert.equal(height * sy, 32)
        assert.equal(font, 32, '保留局部32px字体，CSS水平×2/垂直×.5产生对应字形')
        assert.equal(font * sy, 16)
        assert.equal(Number(match[1]), 30)
      } else {
        assert.ok(width * sx >= 48 && height * sy >= 28, '极小文档框保留原最小可编辑尺寸，不承诺该扩展框逐像素匹配画布')
        assert.ok(font * Math.min(sx, sy) >= 12)
      }
      assert.equal(fixture.calls.filter((call) => call.method === 'POST').length, 0, '打开非等比编辑框不修改图层或协议')
    } finally { mounted.unmount(); container.remove() }
  }
})

const auxiliaryLabels = { editor: '编辑器', marketing: '营销', batch: '批量' }
function auxiliaryFixture(page, mode) {
  const runId = page === 'batch' ? 'b-auxiliary' : 'r-auxiliary'
  const fixture = editorFixture({ page, params: page === 'batch' ? { runId } : { sessionId: 's-final' } })
  fixture.state.active_run_id = runId
  const ordinary = fixture.h.request
  const cause = mode === 'network' ? '技术任务读取网络中断' : '技术任务读取服务暂不可用'
  let recovered = false, runReads = 0, detailReads = 0
  const output = { id: 'a-auxiliary-result', kind: 'original', width: 64, height: 80, image_format: 'PNG', source: 'upload' }
  const snapshot = () => ({ id: runId, tool: page === 'batch' ? 'batch_process' : 'generate_marketing', status: recovered ? 'succeeded' : 'running',
    progress: recovered ? 100 : 20, stage: recovered ? '正式处理完成' : '正式处理中', error: null, result: {}, candidates: [] })
  fixture.h.request = async (config) => {
    if (config.url === `/aipicture/runs/${runId}`) {
      fixture.calls.push(config); runReads++
      if (!recovered) {
        if (mode === 'network') throw new Error(cause)
        return { code: 503, msg: cause }
      }
      return { code: 200, data: snapshot() }
    }
    if (config.url === `/aipicture/batches/${runId}`) {
      fixture.calls.push(config); detailReads++
      return { code: 200, data: { run: snapshot(), created_at: 1790985600000,
        items: [{ id: 'item-auxiliary', source: fixture.state.assets[0], status: recovered ? 'succeeded' : 'running', error: null, outputs: recovered ? [output] : [] }] } }
    }
    return ordinary(config)
  }
  return { ...fixture, cause, runId, get runReads() { return runReads }, get detailReads() { return detailReads },
    recover() { recovered = true; fixture.state.active_run_id = null; fixture.state.assets.push(output) } }
}
function findButton(root, label) {
  return [...root.querySelectorAll('button')].find((button) => button.textContent === label)
}

for (const page of ['editor', 'marketing', 'batch']) {
  for (const mode of ['503', 'network']) {
    test(`辅助任务读取：${auxiliaryLabels[page]}主数据成功后${mode}耗尽一次重试，原错误可重读且正式成功解除等待`, async () => {
      const fixture = auxiliaryFixture(page, mode)
      const container = document.createElement('div'); document.body.append(container)
      const mounted = api.mount(container, fixture.h)
      try {
        const root = container.shadowRoot
        await waitFor(() => page === 'editor' ? root.querySelector('input[aria-label="会话标题"]') : root.textContent.includes(page === 'batch' ? '批量进度' : '投放尺寸'), '主会话或批量详情已正式读取成功')
        await waitFor(() => fixture.runReads >= 2, '共享 Query retry:1 已耗尽，不能将单次失败误作永久停止')
        await pause(); await pause()
        assert.match(root.querySelector('.aipicture-root').textContent, new RegExp(fixture.cause), '辅助任务原读取错误必须可见')
        const retry = findButton(root, '重新读取任务')
        assert.ok(retry, '保留可重读入口，不能把读取异常伪造业务终态')
        const locked = () => findButton(root, page === 'editor' ? '撤销' : page === 'batch' ? '打包下载' : '生成 1:1 / 4:5 / 9:16')
        assert.equal(locked().disabled, true, '尚未得到正式终态不能解锁')
        fixture.recover(); retry.click()
        await waitFor(() => locked() && !locked().disabled, '正式 GET200 成功终态才解除等待锁')
        await waitFor(() => fixture.calls.some((call) => call.responseType === 'blob' && call.url.includes('a-auxiliary-result')), '正式会话或批量详情回读刷新新结果的授权图片')
        assert.ok(fixture.runReads >= 3)
        assert.doesNotMatch(root.querySelector('.aipicture-root').textContent, new RegExp(fixture.cause), '读取恢复后清除读取错误')
        assert.equal(fixture.calls.filter((call) => call.method === 'POST').length, 0, '重读只做 GET，不重发工具或批量操作')
      } finally { mounted.unmount(); container.remove() }
    })
  }
}

test('辅助任务读取：耗尽 Query 重试后原1500ms轮询仍能恢复，无需重新发起业务', async () => {
  const fixture = auxiliaryFixture('marketing', '503')
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  try {
    const root = container.shadowRoot
    await waitFor(() => fixture.runReads >= 2, '实际两次读取均失败')
    await pause(); await pause()
    assert.match(root.querySelector('.aipicture-root').textContent, new RegExp(fixture.cause), '轮询恢复前保留实际异常')
    fixture.recover()
    await waitFor(() => findButton(root, '生成 1:1 / 4:5 / 9:16')?.disabled === false, '既有轮询按正式响应自动解除等待')
    assert.ok(fixture.runReads >= 3)
    assert.equal(fixture.calls.some((call) => call.method === 'POST'), false)
  } finally { mounted.unmount(); container.remove() }
})

for (const [action, label, initial] of [['confirm', '执行计划', 'waiting_confirmation'], ['retry', '重试失败步骤', 'failed'], ['cancel', '取消', 'waiting_confirmation']]) {
  test(`计划动作回读：${action}已受理但响应丢失，等待正式刷新并且不重发副作用`, async () => {
    const fixture = editorFixture()
    const ordinary = fixture.h.request
    let accepted = false, reads = 0, posts = 0, release
    const gate = new Promise((resolve) => { release = resolve })
    let turn = plannedTurn(initial, finalSteps.map((step) => ({ ...step, status: initial === 'failed' ? 'failed' : 'pending' })))
    fixture.h.request = async (config) => {
      if (config.method === 'POST' && config.url.endsWith(`/t-planning/${action}`)) {
        fixture.calls.push(config); posts++; accepted = true
        turn = { ...plannedTurn(action === 'cancel' ? 'canceled' : 'running', finalSteps.map((step) => ({ ...step, status: action === 'cancel' ? 'canceled' : 'running' }))), reply: action === 'cancel' ? '正式已取消' : '正式正在执行' }
        return { code: 503, msg: '技术模拟操作已受理但响应丢失' }
      }
      if (config.url.endsWith('/messages')) {
        fixture.calls.push(config); reads++
        if (accepted) await gate
        return { code: 200, data: [turn] }
      }
      return ordinary(config)
    }
    const container = document.createElement('div'); document.body.append(container)
    const mounted = api.mount(container, fixture.h)
    try {
      const root = container.shadowRoot
      await waitFor(() => findButton(root, label) && !findButton(root, label).disabled, '等待真实正式计划动作入口')
      // useSessionHistory 只在现有图层面板中活跃；未打开时 invalidation 不发 GET。
      findButton(root, '图层').click()
      await waitFor(() => fixture.calls.some((call) => call.url.endsWith('/history')), '打开正式历史查询作为刷新前提')
      const initialHistoryReads = fixture.calls.filter((call) => call.url.endsWith('/history')).length
      const initialSessionReads = fixture.calls.filter((call) => call.url === '/aipicture/sessions/s-final').length
      const initialReads = reads
      findButton(root, label).click()
      await waitFor(() => posts === 1, '技术宿主仅受理一个动作')
      await pause(); await pause()
      assert.ok(reads > initialReads, 'POST失败也必须读取后台已受理后的正式计划')
      assert.equal(findButton(root, label)?.disabled, true, '正式 GET 尚未完成时必须等待，不能重新确认旧计划')
      release()
      await waitFor(() => root.textContent.includes(action === 'cancel' ? '正式已取消' : '取消后续'), '仅正式 GET 决定实际状态')
      if (action !== 'cancel') {
        turn = { ...plannedTurn('succeeded', finalSteps.map((step) => ({ ...step, status: 'succeeded' }))), reply: '正式已完成' }
        await waitFor(() => root.textContent.includes('正式已完成'), '正式 running 继续既有计划轮询至成功')
        assert.equal(findButton(root, '取消后续'), undefined)
      }
      assert.equal(posts, 1, '回读恢复期间没有重发确认、重试或取消 POST')
      assert.ok(fixture.calls.filter((call) => call.url.endsWith('/history')).length > initialHistoryReads, '活跃的正式历史同时回读')
      assert.ok(fixture.calls.filter((call) => call.url === '/aipicture/sessions/s-final').length > initialSessionReads, '正式会话同时回读')
    } finally { release(); mounted.unmount(); container.remove() }
  })
}

test('辅助任务读取：计划步骤保留正式进度和503原错误，重读恢复后显示完成', async () => {
  const fixture = editorFixture()
  const ordinary = fixture.h.request
  let reads = 0, recovered = false
  fixture.h.request = async (config) => {
    if (config.url.endsWith('/messages')) return { code: 200, data: [plannedTurn(recovered ? 'succeeded' : 'running', [{ ...finalSteps[0], status: recovered ? 'succeeded' : 'running', run_id: 'r-step' }])] }
    if (config.url === '/aipicture/runs/r-step') {
      fixture.calls.push(config); reads++
      return recovered ? { code: 200, data: { id: 'r-step', tool: 'flip_layer', status: 'succeeded', progress: 100, stage: '', result: {}, candidates: [] } }
        : { code: 503, msg: '技术步骤快照服务暂不可用' }
    }
    return ordinary(config)
  }
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  try {
    const root = container.shadowRoot
    await waitFor(() => reads >= 2, '计划步骤 Query retry:1 耗尽')
    await pause(); await pause()
    assert.match(root.querySelector('.aipicture-root').textContent, /技术步骤快照服务暂不可用/, '步骤的辅助 GET 异常不能静默')
    const retry = findButton(root, '重新读取任务')
    assert.ok(retry)
    recovered = true; retry.click()
    await waitFor(() => root.textContent.includes('已完成'), '步骤真实终态恢复')
    assert.doesNotMatch(root.querySelector('.aipicture-root').textContent, /技术步骤快照服务暂不可用/)
    assert.equal(fixture.calls.some((call) => call.method === 'POST'), false)
  } finally { mounted.unmount(); container.remove() }
})

for (const action of ['retry', 'cancel']) {
  test(`批量动作回读：${action}已受理但响应丢失，正式回读期间禁用旧动作且只POST一次`, async () => {
    const runId = 'b-action-recovery'
    const fixture = editorFixture({ page: 'batch', params: { runId } })
    const ordinary = fixture.h.request
    let accepted = false, reads = 0, posts = 0, release
    const gate = new Promise((resolve) => { release = resolve })
    let status = action === 'retry' ? 'failed' : 'running'
    const run = () => ({ id: runId, tool: 'batch_process', status, progress: 30, result: {}, candidates: [] })
    fixture.h.request = async (config) => {
      if (config.method === 'POST' && config.url.endsWith(action === 'retry' ? '/items/i-recovery/retry' : '/b-action-recovery/cancel')) {
        fixture.calls.push(config); accepted = true; posts++
        status = action === 'retry' ? 'running' : 'canceled'
        return { code: 503, msg: '技术模拟批量操作已受理但响应丢失' }
      }
      if (config.url === `/aipicture/batches/${runId}` || config.url === `/aipicture/runs/${runId}`) {
        fixture.calls.push(config); reads++
        if (accepted) await gate
        return { code: 200, data: config.url.includes('/runs/') ? run() : { run: run(), created_at: 1790985600000,
          items: [{ id: 'i-recovery', source: fixture.state.assets[0], status, error: status === 'failed' ? '原单图失败' : null, outputs: [] }] } }
      }
      return ordinary(config)
    }
    const container = document.createElement('div'); document.body.append(container)
    const mounted = api.mount(container, fixture.h)
    try {
      const root = container.shadowRoot
      const label = action === 'retry' ? '仅重试此项' : '取消未开始项'
      await waitFor(() => findButton(root, label) && !findButton(root, label).disabled, '正式批量动作可操作')
      const initialReads = reads
      findButton(root, label).click()
      await waitFor(() => posts === 1, '受理一次批量动作')
      await pause(); await pause()
      assert.ok(reads > initialReads, '丢失响应后主动回读正式批量和母任务，不依赖旧终态轮询')
      const pending = findButton(root, action === 'retry' ? '重试中…' : label)
      assert.equal(pending?.disabled, true, '正式回读未完成不能重复单项重试或取消')
      release()
      await waitFor(() => root.textContent.includes(action === 'retry' ? '成功 0 张 · 失败 0 张 · 已取消 0 张' : '已取消 1 张'), '后台真实受理状态进入批量详情')
      assert.equal(findButton(root, '仅重试此项'), undefined, '正式运行或取消项不呈现旧失败重试')
      assert.equal(posts, 1, '没有重发批量副作用 POST')
      if (action === 'retry') {
        status = 'succeeded'
        await waitFor(() => root.textContent.includes('成功 1 张'), '回读为 running 后继续正常轮询至终态')
      }
    } finally { release(); mounted.unmount(); container.remove() }
  })
}

test('辅助任务读取：正式状态优先级区分网络或5xx与鉴权、不存在、业务拒绝及取消', () => {
  const abort = new Error('已取消'); abort.name = 'AbortError'
  const cases = [
    [new Error('network'), true, false],
    [{ status: 503 }, true, false],
    [{ response: { status: 200, data: { code: 503 } } }, true, false],
    [{ response: { status: 503 } }, true, false],
    [{ status: 401, response: { status: 503 } }, false, false],
    [{ response: { status: 404, data: { code: 403 } } }, false, false],
    [{ response: { status: 200, data: { code: 404 } } }, false, true],
    [{ response: { status: 404, data: { code: 200 } } }, false, true],
    [{ status: 409 }, false, false],
    [{ status: 4001 }, false, false],
    [abort, false, false],
    [null, false, false],
  ]
  for (const [error, retryable, missing] of cases) {
    assert.equal(api.isRetryableReadError(error), retryable)
    assert.equal(api.isNotFoundError(error), missing)
  }
})

test('辅助任务读取：403、404及明确null保留不可用提示，不伪造处理完成或解除工具锁', async () => {
  for (const mode of [403, 404, 'null']) {
    const fixture = editorFixture()
    fixture.state.active_run_id = 'r-unavailable'
    const ordinary = fixture.h.request
    let reads = 0
    fixture.h.request = async (config) => {
      if (config.url === '/aipicture/runs/r-unavailable') {
        fixture.calls.push(config); reads++
        return mode === 'null' ? { code: 200, data: null } : { code: mode, msg: mode === 403 ? '当前角色无权读取任务' : '正式任务不存在' }
      }
      return ordinary(config)
    }
    const container = document.createElement('div'); document.body.append(container)
    const mounted = api.mount(container, fixture.h)
    try {
      const root = container.shadowRoot
      await waitFor(() => reads >= (mode === 'null' ? 1 : 2), '正式无权限或不存在读取已完成')
      await pause(); await pause()
      assert.match(root.querySelector('.aipicture-root').textContent, mode === 403 ? /当前角色无权读取任务/ : /任务不存在，当前处理状态无法读取/)
      assert.equal(findButton(root, '撤销').disabled, true)
      assert.equal(findButton(root, '去背景').disabled, true)
      assert.ok(findButton(root, '重新读取任务'))
      assert.equal(fixture.calls.some((call) => call.method === 'POST'), false)
    } finally { mounted.unmount(); container.remove() }
  }
})

test('任务错误保持：首次503后自动及手动重读pending保留原错误与同一入口，正式成功才清除', async () => {
  for (const page of ['editor', 'batch']) {
    const fixture = auxiliaryFixture(page, '503')
    const request = fixture.h.request
    let reads = 0, release
    const gate = new Promise((resolve) => { release = resolve })
    fixture.h.request = async (config) => {
      if (config.url === `/aipicture/runs/${fixture.runId}` && ++reads >= 3) await gate
      return request(config)
    }
    const container = document.createElement('div'); document.body.append(container)
    const mounted = api.mount(container, fixture.h)
    try {
      const root = container.shadowRoot
      await waitFor(() => fixture.runReads >= 2 && findButton(root, '重新读取任务'), `${page}首次错误在Query retry:1耗尽后明确显示`)
      const retry = findButton(root, '重新读取任务')
      await waitFor(() => reads >= 3, '既有1500ms自动重读已进入真实pending传输')
      await pause(); await pause()
      assert.match(root.querySelector('.aipicture-root').textContent, new RegExp(fixture.cause), '自动重读pending不能清除尚未恢复的读取错误')
      assert.equal(findButton(root, '重新读取任务'), retry, '不能在自动重读时拆卸入口导致原生点击失败')
      assert.equal(retry.isConnected, true)
      retry.click()
      await pause(); await pause()
      assert.match(root.querySelector('.aipicture-root').textContent, new RegExp(fixture.cause), '手动重读pending仍保留原读取错误')
      assert.equal(findButton(root, '重新读取任务'), retry)
      const locked = () => findButton(root, page === 'editor' ? '撤销' : '打包下载')
      assert.equal(locked().disabled, true, 'pending不是正式业务终态')
      fixture.recover(); release()
      await waitFor(() => locked() && !locked().disabled, '成功正式回读恢复正常状态')
      assert.doesNotMatch(root.querySelector('.aipicture-root').textContent, new RegExp(fixture.cause))
      assert.equal(findButton(root, '重新读取任务'), undefined, '正式成功后清除旧读取错误及入口')
      assert.equal(fixture.calls.some((call) => call.method === 'POST'), false)
    } finally { release(); mounted.unmount(); container.remove() }
  }
})

test('任务错误保持：同一useRun切换资源时旧错误及迟到503不串入，新资源正式null保持不存在语义', async () => {
  let oldReads = 0, newReads = 0, releaseOld, releaseNew, oldReturned = false, snapshot, resource
  const oldGate = new Promise((resolve) => { releaseOld = resolve })
  const newGate = new Promise((resolve) => { releaseNew = resolve })
  const h = host({ request: async (config) => {
    if (config.url === '/aipicture/runs/r-old-read') {
      oldReads++
      if (oldReads >= 3) { await oldGate; oldReturned = true }
      return { code: 503, msg: '仅属于旧任务的读取异常' }
    }
    newReads++
    await newGate
    return { code: 200, data: null }
  } })
  const runtime = new api.HostRuntime(h)
  const state = api.createBusinessState(runtime)
  const queryClient = new api.QueryClient({ defaultOptions: { queries: { retry: 1, staleTime: 5000, gcTime: 0, refetchOnWindowFocus: false } } })
  const container = document.createElement('div'); document.body.append(container)
  const renderer = api.createReactRoot(container)
  function Probe({ runId }) {
    snapshot = api.useRun(runId); resource = runId
    return api.createElement('span', { 'data-run': runId }, snapshot.loadError?.message ?? '')
  }
  const render = (runId) => renderer.render(api.createElement(api.HostContext.Provider, { value: state },
    api.createElement(api.QueryClientProvider, { client: queryClient }, api.createElement(Probe, { runId }))))
  try {
    render('r-old-read')
    await waitFor(() => oldReads >= 2 && snapshot?.loadError, '真实useRun读取旧任务失败')
    assert.match(snapshot.loadError.message, /仅属于旧任务/)
    void snapshot.refetch()
    await waitFor(() => oldReads >= 3, '旧任务已有pending回读')
    render('r-new-read')
    await waitFor(() => resource === 'r-new-read' && newReads >= 1, '同一hook切换至新任务pending')
    assert.equal(snapshot.loadError, null, '新资源不能拿旧资源最后错误')
    assert.equal(snapshot.notFound, false, '新资源尚未读取时不伪造404')
    assert.equal(snapshot.status, undefined, '读错不能虚构job.failed')
    releaseOld()
    await waitFor(() => oldReturned, '旧请求确实迟到返回503')
    await pause(); await pause()
    assert.equal(snapshot.loadError, null, '迟到旧错误不能回到新hook快照')
    assert.equal(container.textContent, '')
    releaseNew()
    await waitFor(() => snapshot.notFound, '新资源正式null才确认不存在')
    assert.equal(snapshot.loadError, null, '明确null清除读取错误，保留正式不存在语义')
  } finally {
    releaseOld(); releaseNew(); renderer.unmount(); runtime.dispose(); state.canvas.dispose(); queryClient.clear(); container.remove()
  }
})

function batchUploadFixture(history = []) {
  const calls = [], uploads = [], completed = [], gates = new Map(), navigations = []
  const assetOf = (name) => ({ id: name.replace(/\.[^.]+$/, ''), kind: 'original', source: 'upload',
    width: 64, height: 80, image_format: 'PNG', size_bytes: 8, has_alpha: true, created_at: 1790985600000 })
  const prepare = (name, failure = null) => {
    let release
    const promise = new Promise((resolve) => { release = resolve })
    gates.set(name, { promise, release, failure })
    return new File(['technical-image'], name, { type: 'image/png' })
  }
  const h = host({ page: 'batch', navigate: (route) => navigations.push(route), request: async (config) => {
    calls.push(config)
    if (config.responseType === 'blob') return new Blob(['technical-png'], { type: 'image/png' })
    if (config.url.startsWith('/aipicture/assets/library')) return { code: 200, data: history.length
      ? [{ session_id: null, title: '技术历史素材', cover: history[0], assets: history, updated_at: 1790985600000 }] : [] }
    if (config.url === '/aipicture/assets' && config.method === 'POST') {
      const name = config.data.get('file').name
      uploads.push(name)
      const gate = gates.get(name)
      await gate.promise
      completed.push(name)
      if (gate.failure) throw new Error(gate.failure)
      return { code: 200, data: assetOf(name) }
    }
    if (config.url === '/aipicture/batches' && config.method === 'POST') return { code: 200, data: { run: { id: 'r-upload', status: 'running' }, items: [] } }
    return { code: 200, data: [] }
  } })
  return { h, calls, uploads, completed, gates, navigations, assetOf, prepare }
}
function pickBatchFiles(root, files) {
  const input = root.querySelector('input[type="file"]')
  Object.defineProperty(input, 'files', { configurable: true, value: files })
  input.dispatchEvent(new browser.Event('change', { bubbles: true, composed: true }))
}
const selectedBatchCount = (root) => root.querySelectorAll('button[aria-label="移除"]').length
const startBatchButton = (root) => [...root.querySelectorAll('button')].find((button) => button.textContent === '开始处理')

test('批量多图上传：多个成功全部按选择顺序入选并提交原数组', async () => {
  const fixture = batchUploadFixture()
  const files = ['a.png', 'b.png', 'c.png'].map((name) => fixture.prepare(name))
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  try {
    const root = container.shadowRoot
    await waitFor(() => root.querySelector('input[type="file"]'), '真实批量页应提供多文件输入')
    pickBatchFiles(root, files)
    await waitFor(() => fixture.uploads.length > 0, '真实upload mutation开始')
    // 先释放后两个，避免只靠响应恰好按选择顺序到达掩盖问题。
    fixture.gates.get('b.png').release(); fixture.gates.get('c.png').release(); fixture.gates.get('a.png').release()
    await waitFor(() => fixture.completed.length === 3, '三个实际upload请求都已成功返回')
    await pause(); await pause()
    assert.equal(selectedBatchCount(root), 3, '旧per-call回调只加入末项，此处必须保留全部三个成功项')
    startBatchButton(root).click()
    await waitFor(() => fixture.calls.some(({ url, method }) => url === '/aipicture/batches' && method === 'POST'), '提交真实batch adapter')
    const created = fixture.calls.filter(({ url, method }) => url === '/aipicture/batches' && method === 'POST')
    assert.equal(created.length, 1)
    assert.deepEqual(created[0].data.asset_ids, ['a', 'b', 'c'])
    assert.deepEqual(fixture.uploads, ['a.png', 'b.png', 'c.png'])
    assert.equal(fixture.navigations[0].params.runId, 'r-upload')
  } finally { for (const gate of fixture.gates.values()) gate.release(); mounted.unmount(); container.remove() }
})

test('批量多图上传：末项失败仍保留前面成功并明确失败文件', async () => {
  const fixture = batchUploadFixture()
  const files = ['a.png', 'b.png', 'c.png'].map((name) => fixture.prepare(name, name === 'c.png' ? '技术上传服务拒绝C' : null))
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  try {
    const root = container.shadowRoot
    await waitFor(() => root.querySelector('input[type="file"]'), '真实批量页已挂载')
    pickBatchFiles(root, files)
    for (const name of ['a.png', 'b.png', 'c.png']) fixture.gates.get(name).release()
    await waitFor(() => fixture.completed.length === 3, '两成功和末项失败均实际完成')
    await pause(); await pause()
    assert.equal(selectedBatchCount(root), 2, '末项失败不能丢弃前两个成功上传')
    assert.match(root.textContent, /c\.png.*技术上传服务拒绝C/)
    assert.equal(startBatchButton(root).disabled, false)
    startBatchButton(root).click()
    await waitFor(() => fixture.calls.some(({ url, method }) => url === '/aipicture/batches' && method === 'POST'), '成功素材仍可进入批量')
    assert.deepEqual(fixture.calls.find(({ url, method }) => url === '/aipicture/batches' && method === 'POST').data.asset_ids, ['a', 'b'])
  } finally { for (const gate of fixture.gates.values()) gate.release(); mounted.unmount(); container.remove() }
})

test('批量多图上传：整轮pending保留既选素材且禁止提前开始或插入历史素材', async () => {
  const fixture = batchUploadFixture()
  const prior = fixture.assetOf('prior.png')
  const ordinary = fixture.h.request
  fixture.h.request = (config) => config.url.startsWith('/aipicture/assets/library')
    ? Promise.resolve({ code: 200, data: [{ session_id: null, title: '技术历史', cover: prior, assets: [prior], updated_at: 1790985600000 }] }) : ordinary(config)
  const files = ['a.png', 'b.png'].map((name) => fixture.prepare(name))
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  try {
    const root = container.shadowRoot
    await waitFor(() => [...root.querySelectorAll('button')].some((button) => button.querySelector('img')), '先选择历史素材')
    const historical = () => root.querySelector('div.mt-4 ul button')
    historical().click()
    await waitFor(() => selectedBatchCount(root) === 1, '已有一张正式素材可开始')
    pickBatchFiles(root, files)
    await waitFor(() => fixture.uploads.length === 1 && root.textContent.includes('正在上传 2 张'), '队列尚有两张，仅首张已请求')
    const start = () => root.querySelector('button[aria-label="开始批量处理"]')
    assert.equal(start().disabled, true)
    assert.equal(historical().disabled, true)
    start().click(); historical().click()
    assert.equal(fixture.calls.some(({ url, method }) => url === '/aipicture/batches' && method === 'POST'), false)
    fixture.gates.get('a.png').release()
    await waitFor(() => fixture.uploads.length === 2 && selectedBatchCount(root) === 2, '首张成功自动入选，第二张才开始')
    assert.equal(start().disabled, true, '单张mutation结束不能解除整轮pending')
    assert.match(root.textContent, /正在上传 1 张/)
    fixture.gates.get('b.png').release()
    await waitFor(() => selectedBatchCount(root) === 3 && !start().disabled, '最后上传结束才可处理')
    start().click()
    await waitFor(() => fixture.calls.some(({ url, method }) => url === '/aipicture/batches' && method === 'POST'), '处理全部已选素材')
    assert.deepEqual(fixture.calls.find(({ url, method }) => url === '/aipicture/batches' && method === 'POST').data.asset_ids, ['prior', 'a', 'b'])
  } finally { for (const gate of fixture.gates.values()) gate.release(); mounted.unmount(); container.remove() }
})

test('批量多图上传：十九张后整轮仅预留一个位置，已满二十张不再上传', async () => {
  const fixture = batchUploadFixture()
  const files = Array.from({ length: 19 }, (_, index) => fixture.prepare(`first-${index}.png`))
  for (const gate of fixture.gates.values()) gate.release()
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  try {
    const root = container.shadowRoot
    await waitFor(() => root.querySelector('input[type="file"]'), '真实批量页已挂载')
    pickBatchFiles(root, files)
    await waitFor(() => selectedBatchCount(root) === 19 && !root.textContent.includes('正在上传'), '前十九张全部成功入选')
    pickBatchFiles(root, ['twentieth.png', 'overflow.png'].map((name) => fixture.prepare(name)))
    await waitFor(() => fixture.uploads.length === 20, '仅剩的一个位置开始上传')
    assert.match(root.textContent, /overflow\.png：最多选择 20 张，未上传此文件/)
    assert.equal(fixture.uploads.includes('overflow.png'), false)
    fixture.gates.get('twentieth.png').release()
    await waitFor(() => selectedBatchCount(root) === 20 && !root.textContent.includes('正在上传'), '恰好二十张')
    pickBatchFiles(root, [fixture.prepare('still-full.png')])
    await pause(); await pause()
    assert.equal(fixture.uploads.length, 20, '禁用的文件输入和队列预算均不可发第二十一张请求')
    startBatchButton(root).click()
    await waitFor(() => fixture.calls.some(({ url, method }) => url === '/aipicture/batches' && method === 'POST'), '允许正式二十张批量')
    assert.deepEqual(fixture.calls.find(({ url, method }) => url === '/aipicture/batches' && method === 'POST').data.asset_ids,
      [...Array.from({ length: 19 }, (_, index) => `first-${index}`), 'twentieth'])
  } finally { for (const gate of fixture.gates.values()) gate.release(); mounted.unmount(); container.remove() }
})

test('批量多图上传：中间失败不阻断后项且后项成功不抹去具体错误', async () => {
  const fixture = batchUploadFixture()
  const files = ['a.png', 'b.png', 'c.png'].map((name) => fixture.prepare(name, name === 'b.png' ? '技术B上传失败' : null))
  for (const gate of fixture.gates.values()) gate.release()
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  try {
    const root = container.shadowRoot
    await waitFor(() => root.querySelector('input[type="file"]'), '真实批量页已挂载')
    pickBatchFiles(root, files)
    await waitFor(() => fixture.completed.length === 3 && selectedBatchCount(root) === 2, '中间失败之后仍上传后项')
    assert.match(root.textContent, /b\.png：技术B上传失败/)
    assert.equal(startBatchButton(root).disabled, false)
    startBatchButton(root).click()
    await waitFor(() => fixture.calls.some(({ url, method }) => url === '/aipicture/batches' && method === 'POST'), '只使用成功素材')
    assert.deepEqual(fixture.calls.find(({ url, method }) => url === '/aipicture/batches' && method === 'POST').data.asset_ids, ['a', 'c'])
  } finally { for (const gate of fixture.gates.values()) gate.release(); mounted.unmount(); container.remove() }
})

test('批量多图上传：同轮非法文件反馈保留，上传中再次输入被禁用', async () => {
  const fixture = batchUploadFixture()
  const good = fixture.prepare('a.png'), later = fixture.prepare('later.png')
  const bad = new File(['invalid'], 'bad.gif', { type: 'image/gif' })
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  try {
    const root = container.shadowRoot
    await waitFor(() => root.querySelector('input[type="file"]'), '真实批量页已挂载')
    pickBatchFiles(root, [bad, good])
    await waitFor(() => fixture.uploads.length === 1 && root.textContent.includes('正在上传'), '合法文件开始上传')
    assert.match(root.textContent, /bad\.gif：仅支持 JPG、PNG 与 WebP/)
    pickBatchFiles(root, [later])
    await pause()
    assert.deepEqual(fixture.uploads, ['a.png'])
    fixture.gates.get('a.png').release()
    await waitFor(() => selectedBatchCount(root) === 1 && !root.textContent.includes('正在上传'), '有效项完成后仍展示同轮拒绝原因')
    assert.match(root.textContent, /bad\.gif：仅支持 JPG、PNG 与 WebP/)
    assert.equal(fixture.uploads.includes('bad.gif'), false)
  } finally { for (const gate of fixture.gates.values()) gate.release(); mounted.unmount(); container.remove() }
})

test('批量多图上传：失活后停止队列，迟到成功不加入另一个实例', async () => {
  const one = batchUploadFixture(), two = batchUploadFixture()
  const files = ['old-a.png', 'old-b.png'].map((name) => one.prepare(name))
  const fresh = two.prepare('fresh.png'); two.gates.get('fresh.png').release()
  const container = document.createElement('div'); document.body.append(container)
  let mounted = api.mount(container, one.h)
  try {
    await waitFor(() => container.shadowRoot.querySelector('input[type="file"]'), '旧批量页已挂载')
    pickBatchFiles(container.shadowRoot, files)
    await waitFor(() => one.uploads.length === 1, '旧实例首项在途')
    mounted.update({ ...one.h, active: false })
    mounted.unmount(); mounted = api.mount(container, two.h)
    await waitFor(() => container.shadowRoot.querySelector('input[type="file"]'), '新批量实例已挂载')
    pickBatchFiles(container.shadowRoot, [fresh])
    await waitFor(() => selectedBatchCount(container.shadowRoot) === 1, '新实例仅接收自己的素材')
    one.gates.get('old-a.png').release()
    await waitFor(() => one.completed.length === 1, '旧响应确实迟到')
    await pause(); await pause()
    assert.deepEqual(one.uploads, ['old-a.png'], '失活后未开始的旧队列文件不能继续上传')
    assert.equal(selectedBatchCount(container.shadowRoot), 1)
    startBatchButton(container.shadowRoot).click()
    await waitFor(() => two.calls.some(({ url, method }) => url === '/aipicture/batches' && method === 'POST'), '新实例独立提交')
    assert.deepEqual(two.calls.find(({ url, method }) => url === '/aipicture/batches' && method === 'POST').data.asset_ids, ['fresh'])
  } finally { for (const fixture of [one, two]) for (const gate of fixture.gates.values()) gate.release(); mounted.unmount(); container.remove() }
})

test('批量多图上传：共用Dropzone的创作单文件入口仍走原创建会话与导航', async () => {
  const fixture = batchUploadFixture()
  fixture.h.page = 'create'
  const ordinary = fixture.h.request
  fixture.h.request = (config) => config.url === '/aipicture/sessions' && config.method === 'POST'
    ? (fixture.calls.push(config), Promise.resolve({ code: 200, data: session('single-upload') })) : ordinary(config)
  const file = fixture.prepare('single.png'); fixture.gates.get('single.png').release()
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  try {
    const root = container.shadowRoot
    await waitFor(() => root.querySelector('input[type="file"]'), '原单文件入口已挂载')
    pickBatchFiles(root, [file])
    await waitFor(() => fixture.navigations.length === 1, '成功上传继续创建会话和导航')
    assert.deepEqual(fixture.uploads, ['single.png'])
    assert.equal(fixture.calls.find(({ url, method }) => url === '/aipicture/sessions' && method === 'POST').data.current_asset_id, 'single')
    assert.equal(fixture.navigations[0].page, 'editor')
    assert.equal(fixture.navigations[0].params.sessionId, 'single-upload')
  } finally { for (const gate of fixture.gates.values()) gate.release(); mounted.unmount(); container.remove() }
})

test('批量多图上传：StrictMode真实Effect重放后仍能逐文件入选', { skip: process.env.AIPICTURE_TEST_REACT_MODE !== 'development' }, async () => {
  const fixture = batchUploadFixture()
  const files = ['strict-a.png', 'strict-b.png'].map((name) => fixture.prepare(name))
  for (const gate of fixture.gates.values()) gate.release()
  const runtime = new api.HostRuntime(fixture.h)
  const state = api.createBusinessState(runtime)
  const queryClient = new api.QueryClient({ defaultOptions: { queries: { retry: 1, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } })
  const container = document.createElement('div'); document.body.append(container)
  const renderer = api.createReactRoot(container)
  let setups = 0, cleanups = 0
  function EffectProbe() {
    api.useEffect(() => { setups++; return () => { cleanups++ } }, [])
    return api.createElement(api.BatchPage)
  }
  renderer.render(api.createElement(api.StrictMode, null, api.createElement(api.HostContext.Provider, { value: state },
    api.createElement(api.QueryClientProvider, { client: queryClient }, api.createElement(EffectProbe)))))
  try {
    await waitFor(() => setups === 2 && cleanups === 1 && container.querySelector('input[type="file"]'), '必须实测开发StrictMode的setup→cleanup→setup，而非生产版无重放')
    pickBatchFiles(container, files)
    await pause(); await pause(); await pause()
    assert.equal(fixture.uploads.length, 2, '旧cleanup后alive永久false，两文件均不会进入真实upload mutation')
    await waitFor(() => selectedBatchCount(container) === 2, 'Effect重新setup后队列仍逐文件消费')
    startBatchButton(container).click()
    await waitFor(() => fixture.calls.some(({ url, method }) => url === '/aipicture/batches' && method === 'POST'), '原批量提交仍可用')
    assert.deepEqual(fixture.calls.find(({ url, method }) => url === '/aipicture/batches' && method === 'POST').data.asset_ids, ['strict-a', 'strict-b'])
  } finally {
    for (const gate of fixture.gates.values()) gate.release()
    renderer.unmount(); runtime.dispose(); state.canvas.dispose(); queryClient.clear(); container.remove()
  }
})


// 已批准完整迁移的定向恢复检查：真实 React/Query/API + 本地受控宿主，不计正式业务验收。
function fillField(input, value) {
  const prototype = input instanceof browser.HTMLTextAreaElement ? browser.HTMLTextAreaElement.prototype : browser.HTMLInputElement.prototype
  Object.getOwnPropertyDescriptor(prototype, 'value').set.call(input, value)
  input.dispatchEvent(new browser.Event('input', { bubbles: true, composed: true }))
}
function remainingToolFixture(mode = 'post503') {
  const fixture = editorFixture()
  const ordinary = fixture.h.request
  const notices = []
  let runReleased = false
  fixture.h.notify = (notice) => notices.push(notice)
  fixture.h.request = async (config) => {
    if (config.method === 'POST' && config.url.endsWith('/tools')) {
      fixture.calls.push(config)
      if (mode === 'post503' || mode === 'conflict') return { code: mode === 'post503' ? 503 : 409, msg: mode === 'post503' ? '工具提交503原错误' : '画布版本冲突原错误' }
      const run = { id: 'r-remaining-tool', tool: config.data.tool, params: config.data.params, status: 'queued', progress: 0, error: null, candidates: [], result: {} }
      return { code: 200, data: { run, session: fixture.state } }
    }
    if (config.url === '/aipicture/runs/r-remaining-tool') {
      fixture.calls.push(config)
      const terminal = mode === 'success' ? 'succeeded' : mode
      return { code: 200, data: { id: 'r-remaining-tool', tool: 'adjust_image', status: runReleased ? terminal : 'running', progress: runReleased ? 100 : 20,
        error: runReleased && !['succeeded', 'partial_success'].includes(terminal) ? `正式工具${terminal}原错误` : null, candidates: [], result: {} } }
    }
    return ordinary(config)
  }
  return { ...fixture, notices, release: () => { runReleased = true } }
}
async function prepareRemainingForm(root, kind) {
  const title = { background: '换背景', replace: '整层替换', adjust: '调色' }[kind]
  const existing = kind === 'adjust' ? root.querySelector('input[type="range"]') : root.querySelector(`textarea[placeholder="${kind === 'background' ? '例如：浅木色桌面，晨光从左侧照入' : '例如：把杯子换成陶瓷马克杯'}"]`)
  if (!existing) findButton(root, title).click()
  if (kind !== 'adjust') {
    const selector = kind === 'background' ? 'textarea[placeholder="例如：浅木色桌面，晨光从左侧照入"]' : 'textarea[placeholder="例如：把杯子换成陶瓷马克杯"]'
    await waitFor(() => root.querySelector(selector), '打开原参数表单')
    const field = root.querySelector(selector)
    await waitFor(() => !field.disabled, '正式任务完成后参数输入可编辑')
    fillField(field, '本次必须保留的完整用户参数')
    await pause()
    return { submit: () => findButton(root, kind === 'background' ? '生成' : '替换').click(), value: () => field.value, expected: '本次必须保留的完整用户参数' }
  }
  await waitFor(() => root.querySelector('input[type="range"]'), '打开原11项调色')
  const field = root.querySelector('input[type="range"]')
  await waitFor(() => !field.disabled, '调色正式任务完成后输入可编辑')
  fillField(field, '0.35'); await pause()
  field.dispatchEvent(new browser.Event('pointerup', { bubbles: true, composed: true })); await pause()
  return { submit: () => findButton(root, '应用').click(), value: () => field.value, expected: '0.35' }
}

test('剩余完整性：未提交创作草稿在同用户标签失活与重新挂载后恢复', async () => {
  sessionStorage.removeItem('aipicture:prompt-draft:u1')
  const container = document.createElement('div'); document.body.append(container)
  const h = host(), mounted = api.mount(container, h)
  try {
    await waitFor(() => container.shadowRoot.querySelector('textarea[aria-label="画面描述"]'), '读取创作输入')
    fillField(container.shadowRoot.querySelector('textarea[aria-label="画面描述"]'), '未提交也必须保留的创作草稿'); await pause()
    mounted.update({ ...h, active: false }); mounted.update(h)
    await waitFor(() => container.shadowRoot.querySelector('textarea[aria-label="画面描述"]'), '同用户重新激活')
    assert.equal(container.shadowRoot.querySelector('textarea[aria-label="画面描述"]').value, '未提交也必须保留的创作草稿')
    mounted.unmount()
    const another = api.mount(container, h)
    try { await waitFor(() => container.shadowRoot.querySelector('textarea'), '标签关闭后新实例'); assert.equal(container.shadowRoot.querySelector('textarea').value, '未提交也必须保留的创作草稿') }
    finally { another.unmount() }
  } finally { mounted.unmount(); container.remove(); sessionStorage.removeItem('aipicture:prompt-draft:u1') }
})

test('剩余完整性：草稿两用户隔离、成功提交清除且身份退出不留下旧草稿', async () => {
  const container = document.createElement('div'); document.body.append(container)
  const calls = [], routes = []
  const h = host({ request: async (config) => { calls.push(config); return { code: 200, data: config.method === 'POST' ? { id: 'r-draft', status: 'queued', candidates: [] } : [] } }, navigate: (route) => routes.push(route) })
  const mounted = api.mount(container, h)
  try {
    await waitFor(() => container.shadowRoot.querySelector('textarea'), '用户一创作')
    fillField(container.shadowRoot.querySelector('textarea'), '成功提交草稿'); await pause()
    assert.equal(sessionStorage.getItem('aipicture:prompt-draft:u1'), '成功提交草稿')
    container.shadowRoot.querySelector('form').dispatchEvent(new browser.Event('submit', { bubbles: true, cancelable: true }))
    await waitFor(() => routes.length === 1, '成功走正式候选导航')
    assert.equal(sessionStorage.getItem('aipicture:prompt-draft:u1'), null)
    fillField(container.shadowRoot.querySelector('textarea'), '退出应清除的旧用户草稿'); await pause()
    mounted.update({ ...h, identity: { userId: 'u2' } })
    await waitFor(() => container.shadowRoot.querySelector('textarea')?.value === '', '另一用户没有旧草稿')
    assert.equal(sessionStorage.getItem('aipicture:prompt-draft:u1'), null)
    fillField(container.shadowRoot.querySelector('textarea'), '用户二独立草稿'); await pause()
    assert.equal(sessionStorage.getItem('aipicture:prompt-draft:u2'), '用户二独立草稿')
    mounted.update({ ...h, identity: { userId: 0 }, active: false })
    assert.equal(sessionStorage.getItem('aipicture:prompt-draft:u2'), null)
    assert.equal(calls.filter(({ method }) => method === 'POST').length, 1)
  } finally { mounted.unmount(); container.remove(); for (const id of ['u1', 'u2']) sessionStorage.removeItem(`aipicture:prompt-draft:${id}`) }
})

for (const [kind, mode] of [['background', 'post503'], ['replace', 'conflict'], ['adjust', 'failed']]) {
  test(`剩余完整性：${kind}在${mode}后保留原用户参数，不以提交成功清空`, async () => {
    const fixture = remainingToolFixture(mode), container = document.createElement('div'); document.body.append(container)
    const mounted = api.mount(container, fixture.h)
    try {
      const root = container.shadowRoot
      await waitFor(() => root.querySelector('input[aria-label="会话标题"]'), '读取正式编辑器')
      const form = await prepareRemainingForm(root, kind)
      form.submit()
      await waitFor(() => fixture.calls.some(({ method, url }) => method === 'POST' && url.endsWith('/tools')), '单次正式命令提交')
      fixture.release()
      await waitFor(() => fixture.notices.some(({ message }) => message.includes('原错误')), '原错误如实报告')
      assert.equal(form.value(), form.expected, '失败后仍可以按原参数明确重试')
      assert.equal(fixture.calls.filter(({ method, url }) => method === 'POST' && url.endsWith('/tools')).length, 1)
    } finally { fixture.release(); mounted.unmount(); container.remove() }
  })
}

test('剩余完整性：三原参数表单只有正式成功后才清空，调色显式重置仍可用', async () => {
  for (const kind of ['background', 'replace', 'adjust']) {
    const fixture = remainingToolFixture('success'), container = document.createElement('div'); document.body.append(container)
    const mounted = api.mount(container, fixture.h)
    try {
      const root = container.shadowRoot
      await waitFor(() => root.querySelector('input[aria-label="会话标题"]'), '读取编辑器')
      const form = await prepareRemainingForm(root, kind)
      form.submit()
      await waitFor(() => fixture.calls.some(({ method, url }) => method === 'POST' && url.endsWith('/tools')), '正式命令被接受')
      assert.equal(form.value(), form.expected, 'queued不等于成功，不能先清输入')
      fixture.release()
      await waitFor(() => form.value() === (kind === 'adjust' ? '0' : ''), '正式succeeded才清除已应用参数')
      assert.equal(fixture.calls.filter(({ method, url }) => method === 'POST' && url.endsWith('/tools')).length, 1)
      if (kind === 'adjust') {
        const again = await prepareRemainingForm(root, kind)
        findButton(root, '重置').click(); await pause(); assert.equal(again.value(), '0')
      }
    } finally { fixture.release(); mounted.unmount(); container.remove() }
  }
})

test('剩余完整性：工具取消和外部未知结果保留参数，不清空或自动再次付费', async () => {
  for (const mode of ['canceled', 'external_result_unknown']) {
    const fixture = remainingToolFixture(mode), container = document.createElement('div'); document.body.append(container)
    const mounted = api.mount(container, fixture.h)
    try {
      const root = container.shadowRoot
      await waitFor(() => root.querySelector('input[aria-label="会话标题"]'), '读取编辑器')
      const form = await prepareRemainingForm(root, 'background'); form.submit(); fixture.release()
      await waitFor(() => fixture.notices.some(({ message }) => message.includes(`正式工具${mode}`)), '原终态错误如实显示')
      assert.equal(form.value(), form.expected)
      assert.equal(fixture.calls.filter(({ method, url }) => method === 'POST' && url.endsWith('/tools')).length, 1)
    } finally { mounted.unmount(); container.remove() }
  }
})

function remainingAsyncFixture({ kind = 'selection', failures = 1, status = 'succeeded', finalReadFailure = false } = {}) {
  const calls = []; let reads = 0, selected = false, finalReads = 0
  const mask = { id: 'm-remaining', kind: 'mask', source: 'tool', image_format: 'PNG', width: 64, height: 80 }
  const h = host({ request: async (config) => {
    calls.push(config)
    if (config.responseType === 'blob') return new Blob([config.url.includes('/exports/') ? 'saved-original-export-bytes' : 'png'], { type: config.url.includes('/exports/') ? 'application/zip' : 'image/png' })
    if (config.method === 'POST') return { code: 200, data: { id: 'r-remaining-async', status: 'queued', tool: kind, candidates: [], result: {} } }
    if (config.url === '/aipicture/runs/r-remaining-async') {
      reads++
      if (reads <= failures) return { code: 503, msg: '当前任务读取503原错误' }
      selected = true
      return { code: 200, data: { id: 'r-remaining-async', status, tool: kind, candidates: [], error: status === 'succeeded' ? null : `正式异步${status}原错误`, result: kind === 'export' ? { export_id: 'e-remaining', filename: '原物料名称.zip' } : {} } }
    }
    if (config.url.endsWith('/selection')) {
      if (!selected) return { code: 200, data: null }
      if (finalReadFailure && ++finalReads === 1) return { code: 503, msg: '已保存选区回读503原错误' }
      return { code: 200, data: { revision: 2, mask, markers: [] } }
    }
    return { code: 200, data: [] }
  } })
  return { h, calls, get reads() { return reads } }
}

test('剩余完整性：选区首次任务GET503后只回读同一run，正式mask与本地历史各采用一次', async () => {
  const fixture = remainingAsyncFixture(), runtime = new api.HostRuntime(fixture.h), state = api.createBusinessState(runtime)
  const query = new api.QueryClient({ defaultOptions: { queries: { retry: 1, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } })
  let selection
  function Probe() { selection = api.useSelection('s-final', 2); return null }
  const container = document.createElement('div'); document.body.append(container)
  const renderer = api.createReactRoot(container)
  renderer.render(api.createElement(api.HostContext.Provider, { value: state }, api.createElement(api.QueryClientProvider, { client: query }, api.createElement(Probe))))
  try {
    await waitFor(() => selection && fixture.calls.some(({ url }) => url.endsWith('/selection')), '初始选区读取完成')
    selection.addStroke([{ x: .2, y: .3 }, { x: .4, y: .5 }])
    await waitFor(() => !selection.busy && fixture.reads > 0, '本次选区等待结束')
    assert.equal(selection.selection?.maskId, 'm-remaining', '任务读取暂错不能丢正式已保存选区')
    assert.equal(selection.canUndo, true, '本次成功笔刷仅一次进入本地选区历史')
    assert.equal(selection.canRedo, false)
    assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1)
    assert.ok(fixture.reads >= 2)
  } finally { renderer.unmount(); runtime.dispose(); state.canvas.dispose(); query.clear(); container.remove() }
})

test('剩余完整性：任务成功后选区GET暂错继续读正式mask，不重复选择POST', async () => {
  const fixture = remainingAsyncFixture({ failures: 0, finalReadFailure: true }), runtime = new api.HostRuntime(fixture.h)
  try {
    const selection = await api.createSessionsApi(api.createClient(runtime), runtime).select('s-final', { revision: 2, points: [{ x: .1, y: .2 }] })
    assert.equal(selection.mask.id, 'm-remaining')
    assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1)
    assert.equal(fixture.calls.filter(({ method, url }) => method === 'GET' && url.endsWith('/selection')).length, 2)
  } finally { runtime.dispose() }
})

test('剩余完整性：会话和批量导出阶段GET503/网络断连后恢复原文件，不新建导出', async () => {
  for (const batch of [false, true]) {
    const fixture = remainingAsyncFixture({ kind: 'export', failures: 1 }), original = fixture.h.request
    let extraFailure = true
    fixture.h.request = (config) => {
      if (config.url === '/aipicture/runs/r-remaining-async' && fixture.reads === 1 && extraFailure) {
        extraFailure = false; fixture.calls.push(config); return Promise.reject(new TypeError('读取网络断连'))
      }
      return original(config)
    }
    const runtime = new api.HostRuntime(fixture.h)
    try {
      const client = api.createClient(runtime)
      const result = batch ? await api.createBatchesApi(client, runtime).export('b-existing') : await api.createSessionsApi(client, runtime).export('s-final', ['a1'])
      assert.equal(await result.blob.text(), 'saved-original-export-bytes'); assert.equal(result.filename, '原物料名称.zip')
      assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1)
      assert.equal(fixture.calls.filter(({ url }) => url === '/aipicture/exports/e-remaining/download').length, 1)
      assert.ok(fixture.reads >= 2)
    } finally { runtime.dispose() }
  }
})

test('剩余完整性：选区和导出正式failed/canceled/unknown保留真实错误且不重POST', async () => {
  for (const status of ['failed', 'canceled', 'external_result_unknown']) {
    for (const kind of ['selection', 'export']) {
      const fixture = remainingAsyncFixture({ kind, failures: 0, status }), runtime = new api.HostRuntime(fixture.h)
      try {
        const sessions = api.createSessionsApi(api.createClient(runtime), runtime)
        const action = kind === 'export' ? sessions.export('s-final', []) : sessions.select('s-final', { revision: 2, points: [{ x: .1, y: .2 }] })
        await assert.rejects(action, new RegExp(`正式异步${status}原错误`))
        assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1)
        assert.equal(fixture.calls.some(({ url }) => url.includes('/exports/e-remaining/download')), false)
      } finally { runtime.dispose() }
    }
  }
})

test('剩余完整性：401/403/404与明确空任务立即拒绝，不自动读循环或再次POST', async () => {
  for (const code of [401, 403, 404, null]) {
    const calls = [], runtime = new api.HostRuntime(host({ request: async (config) => {
      calls.push(config)
      if (config.method === 'POST') return { code: 200, data: { id: 'r-inaccessible', status: 'queued', candidates: [] } }
      return code === null ? { code: 200, data: null } : { code, msg: `正式拒绝${code}` }
    } }))
    try {
      await assert.rejects(api.createSessionsApi(api.createClient(runtime), runtime).prepareSelection('s-final'), code === null ? /任务不存在/ : new RegExp(`正式拒绝${code}`))
      assert.equal(calls.filter(({ method }) => method === 'POST').length, 1)
      assert.equal(calls.filter(({ method }) => method === 'GET').length, 1)
    } finally { runtime.dispose() }
  }
})

test('剩余完整性：失活或同一runtime重置即停止旧任务等待，不向新身份回读旧run', async () => {
  const fixture = remainingAsyncFixture({ failures: 99 }), runtime = new api.HostRuntime(fixture.h)
  const pending = api.createSessionsApi(api.createClient(runtime), runtime).prepareSelection('s-final')
  const captured = pending.then(() => null, (error) => error)
  try {
    await waitFor(() => fixture.calls.some(({ method }) => method === 'POST'), '原任务已被接受')
    runtime.reset(); runtime.host = host({ identity: { userId: 'u2' }, request: async () => { throw new Error('不得读旧任务') } })
    const error = await captured
    assert.equal(error?.name, 'AbortError')
    assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1)
    assert.equal(fixture.calls.filter(({ method }) => method === 'GET').length, 0)
  } finally { runtime.dispose() }
})

test('剩余完整性：选择预热和正式导出正常成功仍只请求一次，保留原格式与文件名', async () => {
  const fixture = remainingAsyncFixture({ failures: 0 }), runtime = new api.HostRuntime(fixture.h)
  try {
    await api.createSessionsApi(api.createClient(runtime), runtime).prepareSelection('s-final')
    assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1)
    assert.equal(fixture.calls.filter(({ method }) => method === 'GET').length, 1)
  } finally { runtime.dispose() }
})


test('剩余完整性：迟到生成回复不能清掉新用户或重新激活后的新草稿', async () => {
  let release
  const original = host({ request: async (config) => config.method === 'POST' ? new Promise((resolve) => { release = resolve }) : { code: 200, data: [] } })
  const routes = [], h = { ...original, navigate: (route) => routes.push(route) }
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, h)
  try {
    await waitFor(() => container.shadowRoot.querySelector('textarea'), '初始创作输入')
    fillField(container.shadowRoot.querySelector('textarea'), '旧请求中的提示词'); await pause()
    container.shadowRoot.querySelector('form').dispatchEvent(new browser.Event('submit', { bubbles: true, cancelable: true }))
    await waitFor(() => release, '旧提交保留在途')
    mounted.update({ ...h, active: false }); mounted.update({ ...h, identity: { userId: 'u2' } })
    await waitFor(() => container.shadowRoot.querySelector('textarea'), '新身份重新挂载')
    fillField(container.shadowRoot.querySelector('textarea'), '新用户草稿\n 保留格式'); await pause()
    release({ code: 200, data: { id: 'old-late-run', status: 'queued', candidates: [] } }); await pause()
    assert.equal(sessionStorage.getItem('aipicture:prompt-draft:u2'), '新用户草稿\n 保留格式')
    assert.equal(container.shadowRoot.querySelector('textarea').value, '新用户草稿\n 保留格式')
    assert.equal(routes.length, 0, '旧身份迟到成功不生成新用户导航')
  } finally { mounted.unmount(); container.remove(); for (const id of ['u1', 'u2']) sessionStorage.removeItem(`aipicture:prompt-draft:${id}`) }
})

test('剩余完整性：正式partial_success也清除已应用参数，不把排队状态当完成', async () => {
  const fixture = remainingToolFixture('partial_success'), container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  try {
    const root = container.shadowRoot
    await waitFor(() => root.querySelector('input[aria-label="会话标题"]'), '正式编辑器')
    const form = await prepareRemainingForm(root, 'background'); form.submit()
    await waitFor(() => fixture.calls.some(({ method }) => method === 'POST'), '原命令已接受')
    assert.equal(form.value(), form.expected)
    fixture.release()
    await waitFor(() => form.value() === '', '正式部分成功属于已采用结果')
    assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1)
  } finally { fixture.release(); mounted.unmount(); container.remove() }
})

test('剩余完整性：持续暂错仍受15分钟界限约束，超限说明原读错且不再POST', async () => {
  const fixture = remainingAsyncFixture({ failures: 99 }), original = fixture.h.request, clock = Date.now
  let advanced = false
  fixture.h.request = async (config) => {
    const value = await original(config)
    if (config.url.includes('/runs/')) { advanced = true; Date.now = () => clock() + 15 * 60 * 1000 + 10 }
    return value
  }
  const runtime = new api.HostRuntime(fixture.h)
  try {
    await assert.rejects(api.createSessionsApi(api.createClient(runtime), runtime).prepareSelection('s-final'), /仍无法读取.*当前任务读取503原错误/)
    assert.equal(advanced, true)
    assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1)
    assert.equal(fixture.calls.filter(({ method }) => method === 'GET').length, 1)
  } finally { Date.now = clock; runtime.dispose() }
})

test('剩余完整性：迟到正式调色回读不清除任务结束后用户新改参数', async () => {
  const fixture = editorFixture(), ordinary = fixture.h.request
  const container = document.createElement('div'); document.body.append(container)
  let publish, release, reads = 0
  const formal = { id: 'r-late-adjust', tool: 'adjust_image', status: 'succeeded', progress: 100, candidates: [], result: {}, error: null }
  fixture.h.subscribeRun = (_id, onSnapshot) => { publish = onSnapshot; return () => {} }
  fixture.h.request = async (config) => {
    if (config.method === 'POST' && config.url.endsWith('/tools')) {
      fixture.calls.push(config)
      return { code: 200, data: { run: { ...formal, status: 'queued', progress: 0 }, session: fixture.state } }
    }
    if (config.url === '/aipicture/runs/r-late-adjust') {
      fixture.calls.push(config); reads++
      if (reads === 1) return { code: 200, data: { ...formal, status: 'running', progress: 30 } }
      // 第一次 Query 快照先 running；awaitRun 一秒后发起的 GET 保持在途。
      if (reads === 2) return await new Promise(resolve => { release = () => resolve({ code: 200, data: formal }) })
      return { code: 200, data: formal }
    }
    return ordinary(config)
  }
  const mounted = api.mount(container, fixture.h)
  try {
    const root = container.shadowRoot
    await waitFor(() => root.querySelector('input[aria-label="会话标题"]'), '读取真实编辑器')
    const form = await prepareRemainingForm(root, 'adjust'); form.submit()
    await waitFor(() => release && publish, '原等待GET已在途且正式订阅存在')
    publish({ code: 200, data: formal })
    const field = root.querySelector('input[type="range"]')
    await waitFor(() => !field.disabled, '正式订阅终态先解锁原表单')
    fillField(field, '0.65'); await pause()
    field.dispatchEvent(new browser.Event('pointerup', { bubbles: true, composed: true })); await pause()
    assert.equal(field.value, '0.65')
    release(); await pause(); await pause()
    assert.equal(field.value, '0.65', '旧命令迟到成功只可清其提交参数，不可清新草稿')
    assert.equal(fixture.calls.filter(({ method, url }) => method === 'POST' && url.endsWith('/tools')).length, 1)
  } finally { release?.(); mounted.unmount(); container.remove() }
})

function unicodeGenerationFixture({ failure = false, references = false } = {}) {
  const calls = [], routes = []
  const assets = Array.from({ length: 4 }, (_, index) => ({ id: `unicode-ref-${index}`, kind: 'original', source: 'upload', image_format: 'PNG', width: 64, height: 64, size_bytes: 8 }))
  const h = host({ identity: { userId: 'unicode-u' }, navigate: (route) => routes.push(route), request: async (config) => {
    calls.push(config)
    if (config.responseType === 'blob') return new Blob(['technical-image'], { type: 'image/png' })
    if (config.method === 'POST') return failure ? { code: 503, msg: '原生成503错误' } : { code: 200, data: { id: 'r-unicode', status: 'queued', candidates: [], result: {} } }
    return { code: 200, data: references ? [{ id: 'unicode-group', assets, cover: assets[0], session_id: null }] : [] }
  } })
  return { h, calls, routes }
}
async function withUnicodeCreate(fixture, check) {
  sessionStorage.removeItem('aipicture:prompt-draft:unicode-u')
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  try {
    await waitFor(() => container.shadowRoot.querySelector('textarea[aria-label="画面描述"]'), '实际创作输入已挂载')
    await check(container.shadowRoot, fixture)
  } finally { mounted.unmount(); container.remove(); sessionStorage.removeItem('aipicture:prompt-draft:unicode-u') }
}
function submitUnicodeCreate(root) { root.querySelector('form').dispatchEvent(new browser.Event('submit', { bubbles: true, cancelable: true })) }
function chooseSelect(select, value) {
  select.value = String(value)
  select.dispatchEvent(new browser.Event('change', { bubbles: true, composed: true }))
}
function unicodeMarketingFixture() {
  const fixture = editorFixture(), ordinary = fixture.h.request
  fixture.h.page = 'marketing'
  fixture.h.request = async (config) => {
    if (config.method === 'POST' && config.url.endsWith('/tools')) {
      fixture.calls.push(config)
      return { code: 200, data: { session: fixture.state, run: { id: 'r-unicode-marketing', status: 'succeeded', tool: 'generate_marketing', candidates: [], result: {}, progress: 100 } } }
    }
    return ordinary(config)
  }
  return fixture
}
async function withUnicodeMarketing(check) {
  const fixture = unicodeMarketingFixture(), container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  try {
    await waitFor(() => container.shadowRoot.querySelector('input[placeholder^="可选：海报文案"]'), '实际营销文案已挂载')
    await check(container.shadowRoot, fixture)
  } finally { mounted.unmount(); container.remove() }
}
const marketingGenerateButtons = (root) => [...root.querySelectorAll('button')].filter((button) => button.textContent.trim() === '生成')

test('码点边界：800个补充平面字符的创作输入完整计800字并实际提交', async () => {
  await withUnicodeCreate(unicodeGenerationFixture(), async (root, fixture) => {
    const input = root.querySelector('textarea'), text = '🧋'.repeat(800)
    fillField(input, text); await pause()
    assert.equal(input.value, text)
    assert.equal(input.hasAttribute('maxlength'), false, 'native UTF16上限不能截断合法码点输入')
    assert.match(root.textContent, /800\/1500 字/)
    assert.equal(findButton(root, '生成').disabled, false)
    submitUnicodeCreate(root)
    await waitFor(() => fixture.calls.some(({ method }) => method === 'POST'), '正式生成提交')
    const request = fixture.calls.find(({ method }) => method === 'POST')
    assert.equal(request.data.prompt, text)
    assert.equal(request.data.count, 4); assert.equal(request.data.ratio, '1:1')
  })
})

test('码点边界：创作1500允许、1501拒绝且BMP边界兼容，不截成半个字符', async () => {
  await withUnicodeCreate(unicodeGenerationFixture(), async (root, fixture) => {
    const input = root.querySelector('textarea')
    for (const text of ['🧋'.repeat(1500), '中'.repeat(1500)]) {
      fillField(input, text); await pause()
      assert.equal(input.value, text); assert.match(root.textContent, /1500\/1500 字/)
      assert.equal(findButton(root, '生成').disabled, false)
    }
    const tooLong = '🧋'.repeat(1501)
    fillField(input, tooLong); await pause()
    assert.equal(input.value, tooLong, '超限文本仍可完整编辑，不悄悄丢字')
    assert.match(root.textContent, /1501\/1500 字/)
    assert.equal(findButton(root, '生成').disabled, true)
    submitUnicodeCreate(root); await pause()
    assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 0)
    fillField(input, '🧋'.repeat(1500)); await pause(); submitUnicodeCreate(root)
    await waitFor(() => fixture.calls.filter(({ method }) => method === 'POST').length === 1, '缩短后可提交')
  })
})

test('码点边界：混合文字计数、五比例/六候选、seed0/排除项/三参考图不丢参数', async () => {
  await withUnicodeCreate(unicodeGenerationFixture({ references: true }), async (root, fixture) => {
    const text = '中'.repeat(800) + '🧋'.repeat(100)
    fillField(root.querySelector('textarea'), text); await pause()
    assert.match(root.textContent, /900\/1500 字/)
    for (const ratio of ['1:1', '4:5', '3:4', '9:16', '16:9']) {
      findButton(root, ratio).click(); await pause()
      assert.equal(findButton(root, ratio).getAttribute('aria-pressed'), 'true')
    }
    for (const count of [1, 2, 3, 4, 5, 6]) {
      findButton(root, String(count)).click(); await pause()
      assert.equal(findButton(root, String(count)).getAttribute('aria-pressed'), 'true')
    }
    findButton(root, '高级设置').click()
    await waitFor(() => root.querySelector('input[aria-label="排除项"]'), '原高级参数打开')
    fillField(root.querySelector('input[aria-label="排除项"]'), '  不要水印🧋  ')
    fillField(root.querySelector('input[type="number"]'), '0'); await pause()
    await waitFor(() => [...root.querySelectorAll('button')].filter((button) => button.title.startsWith('参考图 ')).length === 4, '本人参考素材')
    for (let index = 0; index < 3; index++) {
      [...root.querySelectorAll('button')].filter((button) => button.title.startsWith('参考图 '))[index].click(); await pause()
    }
    assert.equal([...root.querySelectorAll('button')].filter((button) => button.title.startsWith('参考图 '))[3].disabled, true)
    submitUnicodeCreate(root)
    await waitFor(() => fixture.calls.some(({ method }) => method === 'POST'), '实际全部参数提交')
    const body = fixture.calls.find(({ method }) => method === 'POST').data
    assert.equal(body.prompt, text); assert.equal(body.ratio, '16:9'); assert.equal(body.count, 6)
    assert.equal(body.seed, 0); assert.equal(body.negative_prompt, '不要水印🧋')
    assert.deepEqual(body.reference_asset_ids, ['unicode-ref-0', 'unicode-ref-1', 'unicode-ref-2'])
  })
})

test('码点边界：IME和ShiftEnter不提交，Enter仍trim提交且失败保留原草稿', async () => {
  await withUnicodeCreate(unicodeGenerationFixture({ failure: true }), async (root, fixture) => {
    const input = root.querySelector('textarea'), text = ' \nHello 🧋 \n '
    fillField(input, text); await pause()
    const form = root.querySelector('form')
    form.requestSubmit = () => submitUnicodeCreate(root)
    input.dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'Enter', isComposing: true, bubbles: true, composed: true }))
    input.dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, bubbles: true, composed: true }))
    await pause(); assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 0)
    input.dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true }))
    await waitFor(() => root.textContent.includes('原生成503错误'), '正式失败仍展示原错误')
    assert.equal(fixture.calls.find(({ method }) => method === 'POST').data.prompt, 'Hello 🧋')
    assert.equal(input.value, text); assert.equal(sessionStorage.getItem('aipicture:prompt-draft:unicode-u'), text)
  })
})

test('码点边界：150个补充平面营销字符完整计150字，比例和候选实际提交', async () => {
  await withUnicodeMarketing(async (root, fixture) => {
    const input = root.querySelector('input[placeholder^="可选：海报文案"]'), text = '🧋'.repeat(150)
    fillField(input, text); await pause()
    assert.equal(input.value, text); assert.equal(input.hasAttribute('maxlength'), false)
    assert.match(root.textContent, /150\/200 字/)
    const selects = [...root.querySelectorAll('select')]
    chooseSelect(selects.find((select) => [...select.options].some((option) => option.value === '16:9')), '16:9')
    chooseSelect(selects.find((select) => [...select.options].some((option) => option.textContent === '4 张')), '4')
    await pause(); marketingGenerateButtons(root)[3].click()
    await waitFor(() => fixture.calls.some(({ method }) => method === 'POST'), '实际海报提交')
    const params = fixture.calls.find(({ method }) => method === 'POST').data.params
    assert.equal(params.caption, text); assert.equal(params.kind, 'poster'); assert.equal(params.ratio, '16:9'); assert.equal(params.count, 4)
  })
})

test('码点边界：营销200允许、201拒绝全部四类，BMP200和trim保持兼容', async () => {
  await withUnicodeMarketing(async (root, fixture) => {
    const input = root.querySelector('input[placeholder^="可选：海报文案"]')
    for (const text of ['🧋'.repeat(200), '中'.repeat(200)]) {
      fillField(input, text); await pause()
      assert.equal(input.value, text); assert.match(root.textContent, /200\/200 字/)
      assert.ok(marketingGenerateButtons(root).every((button) => !button.disabled))
    }
    const tooLong = '🧋'.repeat(201)
    fillField(input, tooLong); await pause()
    assert.equal(input.value, tooLong); assert.match(root.textContent, /201\/200 字/)
    assert.ok(marketingGenerateButtons(root).every((button) => button.disabled))
    marketingGenerateButtons(root).forEach((button) => button.click()); await pause()
    assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 0)
    fillField(input, ` ${'中'.repeat(200)} `); await pause(); marketingGenerateButtons(root)[0].click()
    await waitFor(() => fixture.calls.some(({ method }) => method === 'POST'), '实际trim后200字可提交')
    assert.equal(fixture.calls.find(({ method }) => method === 'POST').data.params.caption, '中'.repeat(200))
  })
})

test('码点边界：四营销场景与1到4候选仍按实际选项逐次送出', async () => {
  await withUnicodeMarketing(async (root, fixture) => {
    const input = root.querySelector('input[placeholder^="可选：海报文案"]')
    fillField(input, '中'.repeat(100) + '🧋'.repeat(100)); await pause()
    assert.match(root.textContent, /200\/200 字/)
    const counts = [...root.querySelectorAll('select')].find((select) => [...select.options].some((option) => option.textContent === '4 张'))
    const ratios = [...root.querySelectorAll('select')].find((select) => [...select.options].some((option) => option.value === '16:9'))
    for (const [index, kind] of ['product', 'scene', 'model', 'poster'].entries()) {
      chooseSelect(counts, index + 1); chooseSelect(ratios, ['1:1', '4:5', '3:4', '9:16'][index]); await pause()
      marketingGenerateButtons(root)[index].click()
      await waitFor(() => fixture.calls.filter(({ method }) => method === 'POST').length === index + 1, '逐场景真实提交')
      const params = fixture.calls.filter(({ method }) => method === 'POST')[index].data.params
      assert.equal(params.kind, kind); assert.equal(params.count, index + 1); assert.equal(params.ratio, ['1:1', '4:5', '3:4', '9:16'][index])
      await waitFor(() => marketingGenerateButtons(root).length === 4 && marketingGenerateButtons(root).every((button) => !button.disabled), '正式同步完成后再提交下一场景')
    }
  })
})

// 正式文档命令失败恢复：实际React/Konva事件，工具响应仅本地受控，不发正式API。
function canvasCommandFixture(code = 503) {
  const fixture = editorFixture()
  const notices = []
  const layer = { id: 'command-text', kind: 'text', name: '命令文字层', text: '正式原文字', width: 128, height: 48,
    font_size: 32, fill: '#141414', visible: true, opacity: 1, locked: false,
    transform: { x: 40, y: 80, scale_x: -1.5, scale_y: 1.5, rotation: 30 } }
  fixture.state.document = { width: 320, height: 320, layers: [layer] }
  fixture.h.notify = (notice) => notices.push(notice)
  const ordinary = fixture.h.request
  let accepted = false
  fixture.h.request = async (config) => {
    if (config.method === 'POST' && config.url.endsWith('/tools')) {
      fixture.calls.push(config)
      if (!accepted) return { code, msg: '技术命令明确拒绝，正式文档未改变' }
      const { tool, params } = config.data
      const current = fixture.state.document.layers[0]
      let next = current
      if (tool === 'move_layer' || tool === 'scale_layer') {
        const transform = { ...current.transform, x: params.x, y: params.y }
        if (tool === 'scale_layer') {
          transform.scale_x = Math.sign(transform.scale_x) * params.scale_x
          transform.scale_y = Math.sign(transform.scale_y) * params.scale_y
        }
        next = { ...current, transform }
      } else if (tool === 'set_layer_text') next = { ...current, text: params.text }
      if (tool === 'crop_canvas') {
        fixture.state.document = { ...fixture.state.document,
          width: Math.round(320 * params.rect.width), height: Math.round(320 * params.rect.height) }
      } else fixture.state.document = { ...fixture.state.document, layers: [next] }
      fixture.state.row_version++
      return { code: 200, data: { run: { id: 'r-command', tool, status: 'succeeded', result: {} }, session: fixture.state } }
    }
    return ordinary(config)
  }
  return { ...fixture, layer, notices, accept() { accepted = true } }
}
async function withCanvasCommand(code, run) {
  const fixture = canvasCommandFixture(code)
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  const stageOf = () => api.Konva.stages.find((stage) => stage.container()?.getRootNode() === container.shadowRoot)
  const textOf = () => stageOf()?.find('Text').find((text) => text.text() === fixture.state.document.layers[0].text)
  try {
    await waitFor(textOf, '正式文字层已实际渲染')
    await run({ fixture, root: container.shadowRoot, container, mounted, stageOf, textOf })
  } finally { mounted.unmount(); container.remove() }
}
async function rejectedCanvasCommand(fixture) {
  await waitFor(() => fixture.notices.some((notice) => notice.message.includes('技术命令明确拒绝')), '正式拒绝需保留真实错误')
  await pause(); await pause()
  assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1, '失败不自动重发工具POST')
}
test('画布正式命令：拖动POST503后回正式坐标并保留显式重试原参数', async () => {
  await withCanvasCommand(503, async ({ fixture, textOf }) => {
    const group = textOf().getParent()
    group.fire('dragstart', { evt: new browser.MouseEvent('mousedown') }, true)
    group.position({ x: 104 + 17, y: 104 - 9 })
    group.fire('dragend', { evt: new browser.MouseEvent('mouseup') }, true)
    await rejectedCanvasCommand(fixture)
    assert.deepEqual(group.position(), { x: 104, y: 104 }, '拒绝后实际节点必须回正式坐标，不能由drop盖住正式回读')
    const retry = fixture.notices.find((notice) => notice.action)?.action
    assert.ok(retry, '原拖动参数必须有用户明确重试入口')
    fixture.accept(); retry.run()
    await waitFor(() => fixture.state.row_version === 8 && group.x() === 121 && group.y() === 95, '显式重试成功采用正式坐标')
    const calls = fixture.calls.filter(({ method }) => method === 'POST')
    assert.deepEqual(calls[1].data.params, calls[0].data.params)
  })
})
test('画布正式命令：拖角POST409后回正式倍率和位置并保留显式重试', async () => {
  await withCanvasCommand(409, async ({ fixture, textOf }) => {
    const text = textOf(), group = text.getParent()
    text.fire('click', { evt: new browser.MouseEvent('click') }, true)
    await waitFor(() => group.getLayer().find('Transformer').some((item) => item.nodes()[0] === group), '原图层拖角入口可用')
    const scaler = group.getLayer().find('Transformer').find((item) => item.nodes()[0] === group)
    scaler.fire('transformstart'); group.scale({ x: -2, y: 2 }); group.position({ x: 124, y: 114 }); scaler.fire('transformend')
    await rejectedCanvasCommand(fixture)
    assert.equal(group.scaleX(), -1.5, 'pinned不得盖住正式原倍率')
    assert.equal(group.scaleY(), 1.5)
    assert.deepEqual(group.position(), { x: 104, y: 104 })
    const retry = fixture.notices.find((notice) => notice.action)?.action
    assert.ok(retry)
    fixture.accept(); retry.run()
    await waitFor(() => fixture.state.row_version === 8 && group.scaleX() === -2 && group.scaleY() === 2, '显式重试保持翻转方向并采用正式倍率')
    const calls = fixture.calls.filter(({ method }) => method === 'POST')
    assert.deepEqual(calls[1].data.params, calls[0].data.params)
  })
})
test('画布正式命令：inline文字POST503后保留原输入，正式成功才关闭', async () => {
  await withCanvasCommand(503, async ({ fixture, root, textOf }) => {
    textOf().fire('dblclick', { evt: new browser.MouseEvent('dblclick') }, true)
    const editorOf = () => [...root.querySelectorAll('textarea')].find((input) => input.value !== '' && !input.hasAttribute('aria-label'))
    await waitFor(editorOf, '原双击内联文字入口')
    fillField(editorOf(), '必须保留的新文字'); await pause()
    editorOf().dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true }))
    await rejectedCanvasCommand(fixture)
    assert.equal(editorOf()?.value, '必须保留的新文字', '失败不能立即卸载并丢失用户文字')
    assert.equal(fixture.state.document.layers[0].text, '正式原文字')
    fixture.accept()
    editorOf().dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true }))
    await waitFor(() => fixture.state.document.layers[0].text === '必须保留的新文字' && !editorOf(), '成功才关闭编辑器并采用正式新文字')
  })
})
test('画布正式命令：裁剪POST503后保留原框，正式成功才关闭', async () => {
  await withCanvasCommand(503, async ({ fixture, root, stageOf }) => {
    findButton(root, '裁剪').click()
    const cropOf = () => stageOf()?.find('Rect').find((rect) => rect.draggable() && rect.dash()?.length)
    await waitFor(cropOf, '原裁剪框入口')
    cropOf().setAttrs({ x: 20, y: 30, scaleX: 0.5, scaleY: 0.4 }); cropOf().fire('transform')
    await pause()
    const expected = { x: cropOf().x(), y: cropOf().y(), width: cropOf().width(), height: cropOf().height() }
    findButton(root, '确定').click()
    await rejectedCanvasCommand(fixture)
    assert.ok(findButton(root, '确定') && cropOf(), '失败保留原裁剪模式而非清空rect')
    assert.deepEqual({ x: cropOf().x(), y: cropOf().y(), width: cropOf().width(), height: cropOf().height() }, expected)
    assert.equal(fixture.state.document.width, 320)
    fixture.accept(); findButton(root, '确定').click()
    await waitFor(() => fixture.state.row_version === 8 && !findButton(root, '确定') && !cropOf(), '正式成功后才关闭裁剪')
    const calls = fixture.calls.filter(({ method }) => method === 'POST')
    assert.deepEqual(calls[1].data.params, calls[0].data.params)
  })
})

test('画布命令边界：实际adapter固定提交版本，后续hydrate不改变它，普通请求仍取当前版本', async () => {
  const calls = []
  const runtime = new api.HostRuntime(host({ request: async (config) => {
    calls.push(config)
    return { code: 200, data: { run: { id: 'r-version', status: 'succeeded' } } }
  } }))
  const sessions = api.createSessionsApi(api.createClient(runtime), runtime)
  try {
    await runtime.hydrate(session('s-version', 7))
    await runtime.hydrate(session('s-version', 8))
    await sessions.invoke('s-version', 'move_layer', { layer_id: 'base', x: 17 }, 7)
    await sessions.invoke('s-version', 'move_layer', { layer_id: 'base', x: 18 })
    assert.equal(calls[0].data.expectedVersion, 7)
    assert.equal(calls[1].data.expectedVersion, 8, '未指定版本继续沿现有row_version缓存机制')
    assert.equal(calls[0].data.schemaVersion, '1')
    assert.equal(calls[0].data.toolRegistryVersion, '2026.10-r2')
    assert.notEqual(calls[0].data.idempotencyKey, calls[1].data.idempotencyKey, '不同显式意图沿原幂等key生成，不复用未知结果')
    await assert.rejects(() => sessions.invoke('s-version', 'move_layer', {}, -1), /会话版本无效/)
    assert.equal(calls.length, 2, '非法固定版本不能发出请求')
  } finally { runtime.dispose() }
})
test('画布命令边界：409回读外部新版本后恢复该正式变换，旧重试不覆新文档', async () => {
  await withCanvasCommand(409, async ({ fixture, textOf }) => {
    const ordinary = fixture.h.request
    fixture.h.request = async (config) => {
      const result = await ordinary(config)
      if (config.method === 'POST' && config.url.endsWith('/tools')) {
        const current = fixture.state.document.layers[0]
        fixture.state.document = { ...fixture.state.document, layers: [{ ...current,
          transform: { x: 70, y: 90, scale_x: -2, scale_y: 0.5, rotation: 45 } }] }
        fixture.state.row_version = 8
      }
      return result
    }
    const group = textOf().getParent()
    group.fire('dragstart', { evt: new browser.MouseEvent('mousedown') }, true)
    group.position({ x: 121, y: 95 }); group.fire('dragend', { evt: new browser.MouseEvent('mouseup') }, true)
    await rejectedCanvasCommand(fixture)
    assert.deepEqual(group.position(), { x: 134, y: 114 }, '恢复最新正式坐标而不是旧提交前坐标')
    assert.equal(group.scaleX(), -2); assert.equal(group.scaleY(), 0.5); assert.equal(group.rotation(), 45)
    fixture.notices.find((notice) => notice.action).action.run(); await pause()
    assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1)
    assert.ok(fixture.notices.some((notice) => notice.message.includes('画布已更新')), '原参数保留但旧意图不得静默用新版本覆盖')
  })
})
test('画布命令边界：另一个命令繁忙时旧失败反馈不能再次POST', async () => {
  await withCanvasCommand(503, async ({ fixture, root, textOf }) => {
    const group = textOf().getParent()
    group.fire('dragstart', { evt: new browser.MouseEvent('mousedown') }, true)
    group.position({ x: 121, y: 95 }); group.fire('dragend', { evt: new browser.MouseEvent('mouseup') }, true)
    await rejectedCanvasCommand(fixture)
    const retry = fixture.notices.find((notice) => notice.action)?.action
    assert.ok(retry)
    let release
    const ordinary = fixture.h.request
    fixture.h.request = (config) => {
      if (config.method === 'POST' && config.data?.tool === 'flip_layer') {
        fixture.calls.push(config)
        return new Promise((resolve) => { release = () => resolve({ code: 200, data: {
          run: { id: 'r-busy-flip', status: 'succeeded' }, session: fixture.state,
        } }) })
      }
      return ordinary(config)
    }
    findButton(root, '水平翻转').click()
    try {
      await waitFor(() => release && findButton(root, '裁剪').disabled, '原工具繁忙锁生效')
      retry.run(); retry.run(); await pause()
      assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 2, '繁忙阻止尚未使用的旧重试action')
    } finally { release?.() }
  })
})
test('画布命令边界：等待响应期间文字Esc与裁剪取消不被迟到拒绝重新打开', async () => {
  for (const kind of ['text', 'crop']) {
    await withCanvasCommand(503, async ({ fixture, root, textOf }) => {
      let release
      const ordinary = fixture.h.request
      fixture.h.request = (config) => {
        if (config.method === 'POST' && config.url.endsWith('/tools')) {
          fixture.calls.push(config)
          return new Promise((resolve) => { release = () => resolve({ code: 503, msg: '技术迟到明确拒绝' }) })
        }
        return ordinary(config)
      }
      const editorOf = () => [...root.querySelectorAll('textarea')].find((input) => input.value !== '' && !input.hasAttribute('aria-label'))
      if (kind === 'text') {
        textOf().fire('dblclick', { evt: new browser.MouseEvent('dblclick') }, true)
        await waitFor(editorOf, '原文字编辑器')
        fillField(editorOf(), '取消的文字'); await pause()
        editorOf().dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true }))
        await waitFor(() => release && editorOf()?.readOnly, '等待正式响应保留并锁定原输入')
        editorOf().dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true }))
        editorOf().dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true }))
      } else {
        findButton(root, '裁剪').click(); await pause()
        findButton(root, '确定').click()
        await waitFor(() => release && findButton(root, '确定')?.disabled, '等待正式响应保留并锁定原框')
        findButton(root, '取消').click()
      }
      try {
        await pause()
        assert.equal(kind === 'text' ? Boolean(editorOf()) : Boolean(findButton(root, '确定')), false, '明确取消立即关闭原编辑模式')
        release(); await waitFor(() => fixture.notices.some((notice) => notice.message.includes('技术迟到明确拒绝')), '原错误仍显示而非假成功')
        await pause()
        assert.equal(kind === 'text' ? Boolean(editorOf()) : Boolean(findButton(root, '确定')), false, '迟到拒绝不得重新打开已经取消的模式')
        assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1)
      } finally { release?.() }
    })
  }
})
test('画布命令边界：换owner和同owner失活重挂载隔离旧重试与迟到文字回复', async () => {
  for (const mode of ['owner', 'inactive']) {
    await withCanvasCommand(503, async ({ fixture, root, textOf, mounted, container }) => {
      const group = textOf().getParent()
      group.fire('dragstart', { evt: new browser.MouseEvent('mousedown') }, true)
      group.position({ x: 121, y: 95 }); group.fire('dragend', { evt: new browser.MouseEvent('mouseup') }, true)
      await rejectedCanvasCommand(fixture)
      const retry = fixture.notices.find((notice) => notice.action)?.action
      assert.ok(retry)
      let release
      const ordinary = fixture.h.request
      fixture.h.request = (config) => {
        if (config.method === 'POST' && config.data?.tool === 'set_layer_text') {
          fixture.calls.push(config)
          return new Promise((resolve) => { release = () => resolve({ code: 503, msg: '技术旧实例迟到错误' }) })
        }
        return ordinary(config)
      }
      textOf().fire('dblclick', { evt: new browser.MouseEvent('dblclick') }, true)
      const editorOf = () => [...container.shadowRoot.querySelectorAll('textarea')].find((input) => input.value !== '' && !input.hasAttribute('aria-label'))
      await waitFor(editorOf, '旧实例文字编辑器')
      fillField(editorOf(), '旧owner待提交文字'); await pause()
      editorOf().dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true }))
      await waitFor(() => release, '旧实例POST正在等待')
      const next = canvasCommandFixture()
      if (mode === 'owner') next.h.identity = { userId: 'new-owner' }
      else mounted.update({ ...fixture.h, active: false })
      mounted.update(next.h)
      const nextText = () => api.Konva.stages.filter((stage) => stage.container()?.getRootNode() === container.shadowRoot)
        .flatMap((stage) => stage.find('Text')).find((text) => text.text() === '正式原文字')
      try {
        await waitFor(nextText, '新实例正式快照渲染')
        nextText().fire('dblclick', { evt: new browser.MouseEvent('dblclick') }, true)
        await waitFor(editorOf, '新实例文字编辑器')
        fillField(editorOf(), '新实例尚未提交草稿'); await pause()
        retry.run(); release(); await pause(); await pause()
        assert.equal(editorOf()?.value, '新实例尚未提交草稿', '旧回复不关闭或清掉新实例文字')
        assert.equal(next.calls.filter(({ method }) => method === 'POST').length, 0, '旧反馈action不能向新owner或新实例发POST')
        assert.equal(next.notices.length, 0, '旧错误不会投递新实例通知')
      } finally { release?.() }
    })
  }
})
test('画布命令边界：正式failed响应不清文字或裁剪参数，不能把POST200当成功', async () => {
  for (const kind of ['text', 'crop']) {
    await withCanvasCommand(503, async ({ fixture, root, textOf }) => {
      const ordinary = fixture.h.request
      fixture.h.request = (config) => {
        if (config.method === 'POST' && config.url.endsWith('/tools')) {
          fixture.calls.push(config)
          return Promise.resolve({ code: 200, data: { run: { id: 'r-command-failed', status: 'failed', error: '正式命令失败', result: {} }, session: fixture.state } })
        }
        return ordinary(config)
      }
      const editorOf = () => [...root.querySelectorAll('textarea')].find((input) => input.value !== '' && !input.hasAttribute('aria-label'))
      if (kind === 'text') {
        textOf().fire('dblclick', { evt: new browser.MouseEvent('dblclick') }, true); await waitFor(editorOf, '双击文字')
        fillField(editorOf(), '正式失败仍需保留'); await pause()
        editorOf().dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true }))
      } else { findButton(root, '裁剪').click(); await pause(); findButton(root, '确定').click() }
      await waitFor(() => fixture.notices.some((notice) => notice.message.includes('正式命令失败')), '正式失败不能假成功')
      await pause()
      assert.equal(kind === 'text' ? editorOf()?.value : Boolean(findButton(root, '确定')), kind === 'text' ? '正式失败仍需保留' : true)
      assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1)
    })
  }
})

function layerPropertyFixture(mode = 503) {
  const fixture = canvasCommandFixture(mode)
  const ordinary = fixture.h.request
  let accepted = false
  fixture.h.request = async (config) => {
    if (config.method === 'POST' && config.url.endsWith('/tools')) {
      fixture.calls.push(config)
      const { tool, params } = config.data
      if (!accepted) return mode === 'failed'
        ? { code: 200, data: { run: { id: 'r-property-failed', tool, status: 'failed', error: '技术属性正式失败' }, session: fixture.state } }
        : { code: mode, msg: '技术属性明确拒绝，正式文档未改变' }
      const current = fixture.state.document.layers[0]
      const next = { ...current, transform: { ...current.transform } }
      if (tool === 'rotate_layer') next.transform.rotation = params.rotation
      if (tool === 'set_layer_opacity') next.opacity = params.opacity
      if (tool === 'scale_layer') {
        next.transform.scale_x = Math.sign(current.transform.scale_x) * params.scale_x
        next.transform.scale_y = Math.sign(current.transform.scale_y) * params.scale_y
      }
      if (tool === 'set_layer_text') Object.assign(next, { text: params.text, font_size: params.font_size, fill: params.fill })
      fixture.state.document = { ...fixture.state.document, layers: [next] }
      fixture.state.row_version++
      return { code: 200, data: { run: { id: 'r-property-success', tool, status: 'succeeded', result: {} }, session: fixture.state } }
    }
    return ordinary(config)
  }
  return { ...fixture, accept() { accepted = true } }
}
async function withLayerProperties(mode, run) {
  const fixture = layerPropertyFixture(mode)
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  const stageOf = () => api.Konva.stages.find((stage) => stage.container()?.getRootNode() === container.shadowRoot)
  const textOf = () => stageOf()?.find('Text').find((text) => text.text() === fixture.state.document.layers[0].text)
  try {
    await waitFor(textOf, '正式属性文字节点已渲染')
    const root = container.shadowRoot
    findButton(root, '图层').click()
    await waitFor(() => root.querySelector('input[type="color"]'), '原文字及图层属性入口')
    await run({ fixture, root, container, mounted, stageOf, textOf })
  } finally { mounted.unmount(); container.remove() }
}
function propertyRange(root, label) {
  return [...root.querySelectorAll('label')].find((item) => item.textContent.startsWith(label))?.querySelector('input[type="range"]')
}
function releaseRange(input) {
  input.dispatchEvent(new browser.Event('pointerup', { bubbles: true, composed: true }))
  input.dispatchEvent(new browser.Event('lostpointercapture', { bubbles: true, composed: true }))
}
for (const scenario of [
  { label: '旋转', value: '75', mode: 503, actual: (text) => text.getParent().rotation(), formal: 30 },
  { label: '透明度', value: '0.4', mode: 409, actual: (text) => text.getParent().opacity(), formal: 1 },
  { label: '字号', value: '48', mode: 503, actual: (text) => text.fontSize(), formal: 32 },
  { label: '颜色', value: '#aa2244', mode: 'failed', actual: (text) => text.fill(), formal: '#141414' },
]) {
  test(`图层属性恢复：${scenario.label}拒绝后实际节点回正式值并保留参数供明确重试`, async () => {
    await withLayerProperties(scenario.mode, async ({ fixture, root, textOf }) => {
      const input = scenario.label === '颜色' ? root.querySelector('input[type="color"]') : propertyRange(root, scenario.label)
      fillField(input, scenario.value); await pause()
      if (scenario.label !== '颜色') releaseRange(input)
      await waitFor(() => fixture.notices.some((notice) => notice.message.includes('技术属性')), '正式拒绝/failed必须反馈')
      await pause(); await pause()
      assert.equal(scenario.actual(textOf()), scenario.formal, '失败预览不得覆盖正式文档/导出值')
      assert.equal(input.value, scenario.value, '被拒绝的用户参数保留，不伪装为成功或丢失')
      assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1, 'pointerup和lostcapture不得重复提交，也不自动重POST')
      const retry = fixture.notices.find((notice) => notice.action?.label === '重试')?.action
      assert.ok(retry, '复用原正式命令反馈提供用户明确重试')
      fixture.accept(); retry.run()
      await waitFor(() => fixture.state.row_version === 8 && scenario.actual(textOf()) === (scenario.label === '颜色' ? scenario.value : Number(scenario.value)), '显式重试正式成功才采用属性')
      const calls = fixture.calls.filter(({ method }) => method === 'POST')
      assert.deepEqual(calls[1].data.params, calls[0].data.params)
      assert.equal(calls[1].data.expectedVersion, calls[0].data.expectedVersion)
      if (scenario.label === '字号' || scenario.label === '颜色') assert.equal(calls[1].data.params.text, '正式原文字', '样式调整不得丢文案')
    })
  })
}

function wallSelectionFixture({ rejected = false } = {}) {
  const fixture = editorFixture()
  const base = fixture.state.document.layers[0]
  const a = { ...fixture.state.document, layers: [{ ...base, id: 'background', name: '背景' }, { ...base, id: 'subject', name: '主体', locked: false }] }
  const b = { ...fixture.state.document, layers: [{ ...base, asset_id: 'a2' }] }
  fixture.state.document = a
  const ordinary = fixture.h.request
  fixture.h.request = async (config) => {
    if (config.method === 'PATCH') {
      fixture.calls.push(config)
      if (rejected) return { code: 503, msg: '技术换图拒绝' }
      fixture.state.current_asset_id = 'a2'; fixture.state.document = b; fixture.state.row_version++
      return { code: 200, data: fixture.state }
    }
    if (config.method === 'POST' && config.url.endsWith('/tools')) {
      fixture.calls.push(config)
      return { code: 200, data: { run: { id: 'r-wall-tool', tool: config.data.tool, status: 'succeeded' }, session: fixture.state } }
    }
    return ordinary(config)
  }
  return { ...fixture, a, b }
}
async function withWallSelection(options, run) {
  const fixture = wallSelectionFixture(options)
  const container = document.createElement('div'); document.body.append(container)
  const mounted = api.mount(container, fixture.h)
  try {
    const root = container.shadowRoot
    await waitFor(() => findButton(root, '图层'), '工作台初始正式文档')
    findButton(root, '图层').click()
    await waitFor(() => [...root.querySelectorAll('li button')].some((button) => button.textContent.startsWith('主体')), 'A实际拆层列表')
    ;[...root.querySelectorAll('li button')].find((button) => button.textContent.startsWith('主体')).click()
    await pause()
    await run({ fixture, root, mounted, container })
  } finally { mounted.unmount(); container.remove() }
}
test('图片墙正式选择：A主体切B底图后翻转和调色参数不能保留旧subject', async () => {
  await withWallSelection({}, async ({ fixture, root }) => {
    root.querySelector('button[title^="采用这张"]').click()
    await waitFor(() => fixture.state.current_asset_id === 'a2', '墙B正式回读')
    await waitFor(() => [...root.querySelectorAll('li button')].some((button) => button.textContent.startsWith('底图')), 'B正式列表只有base')
    findButton(root, '水平翻转').click(); await pause(); await pause()
    const flip = fixture.calls.find((call) => call.data?.tool === 'flip_layer')
    assert.ok(flip); assert.notEqual(flip.data.params.layer_id, 'subject', '工具目标必须来自正式B文档而非视觉fallback')
    findButton(root, '调色').click(); await pause()
    fillField(propertyRange(root, '亮度'), '0.3'); await pause(); releaseRange(propertyRange(root, '亮度')); await pause()
    findButton(root, '应用').click(); await pause(); await pause()
    const adjust = fixture.calls.find((call) => call.data?.tool === 'adjust_image')
    assert.ok(adjust); assert.notEqual(adjust.data.params.layer_id, 'subject')
  })
})

test('图层属性边界：排队不解锁或丢参数，failed取消未知回正式，partial成功才采用', async () => {
  for (const terminal of ['failed', 'canceled', 'external_result_unknown', 'partial_success']) {
    await withLayerProperties(503, async ({ fixture, root, textOf }) => {
      let released = false
      const ordinary = fixture.h.request
      fixture.h.request = async (config) => {
        if (config.method === 'POST' && config.url.endsWith('/tools')) {
          fixture.calls.push(config); fixture.state.active_run_id = 'r-property-queued'
          return { code: 200, data: { run: { id: 'r-property-queued', tool: 'set_layer_opacity', status: 'queued', result: {} }, session: fixture.state } }
        }
        if (config.url.endsWith('/runs/r-property-queued')) {
          fixture.calls.push(config)
          return { code: 200, data: { id: 'r-property-queued', tool: 'set_layer_opacity', status: released ? terminal : 'running',
            error: terminal === 'partial_success' ? null : `技术属性终态${terminal}`, result: {}, candidates: [] } }
        }
        return ordinary(config)
      }
      const input = propertyRange(root, '透明度')
      fillField(input, '0.4'); await pause(); releaseRange(input)
      await waitFor(() => fixture.calls.some((call) => call.url.endsWith('/runs/r-property-queued')) && input.disabled, '原参数与属性局部等待锁保持至正式终态')
      assert.equal(input.value, '0.4')
      releaseRange(input); input.closest('label').dispatchEvent(new browser.MouseEvent('dblclick', { bubbles: true })); await pause()
      assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1, '排队期间不能双击复位或再提交')
      if (terminal === 'partial_success') {
        fixture.state.document = { ...fixture.state.document, layers: [{ ...fixture.state.document.layers[0], opacity: 0.4 }] }
        fixture.state.row_version++
      }
      fixture.state.active_run_id = null; released = true
      await waitFor(() => !input.disabled, '正式终态回读后解锁，不能POST接受即清')
      await waitFor(() => textOf().getParent().opacity() === (terminal === 'partial_success' ? 0.4 : 1), '正式终态后的session回读实际渲染已应用值')
      assert.equal(input.value, '0.4', '终态失败/取消/未知保留明确重试参数')
      assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1)
    })
  }
})
test('图层属性边界：409正式外部更新恢复新节点，旧反馈不能覆写新版本且文字草稿保留', async () => {
  await withLayerProperties(409, async ({ fixture, root, textOf }) => {
    const ordinary = fixture.h.request
    fixture.h.request = async (config) => {
      const result = await ordinary(config)
      if (config.method === 'POST' && config.url.endsWith('/tools')) {
        fixture.state.document = { ...fixture.state.document, layers: [{ ...fixture.state.document.layers[0],
          font_size: 40, text: '正式外部更新文字', transform: { ...fixture.state.document.layers[0].transform, rotation: -15 } }] }
        fixture.state.row_version = 8
      }
      return result
    }
    const textInput = root.querySelector('aside textarea:not([aria-label])')
    fillField(textInput, '保留我的未提交草稿'); await pause()
    textInput.dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true }))
    await waitFor(() => fixture.notices.some((notice) => notice.action?.label === '重试'), '正式409回读后原反馈')
    assert.equal(textInput.value, '保留我的未提交草稿')
    assert.equal(textOf().text(), '正式外部更新文字'); assert.equal(textOf().fontSize(), 40)
    fixture.notices.find((notice) => notice.action?.label === '重试').action.run(); await pause()
    assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1)
    assert.ok(fixture.notices.some((notice) => notice.message.includes('画布已更新')))
  })
})
test('图层属性边界：正常缩放和文字样式成功保持翻转与完整文案，双击复位只提交一次', async () => {
  await withLayerProperties(503, async ({ fixture, root, textOf }) => {
    fixture.accept()
    const scale = propertyRange(root, '图层缩放')
    fillField(scale, '2'); await pause(); releaseRange(scale)
    await waitFor(() => fixture.state.row_version === 8 && textOf().getParent().scaleX() === -2, '正式等比倍率保留原flip signs')
    assert.equal(textOf().getParent().scaleY(), 2)
    scale.closest('label').dispatchEvent(new browser.MouseEvent('dblclick', { bubbles: true })); await pause()
    await waitFor(() => fixture.state.row_version === 9 && textOf().getParent().scaleX() === -1, '明确双击复位正式成功')
    const input = root.querySelector('aside textarea:not([aria-label])')
    fillField(input, '新文案与原字号颜色一起保存'); await pause()
    input.dispatchEvent(new browser.Event('focusout', { bubbles: true }))
    await waitFor(() => fixture.state.row_version === 10 && fixture.state.document.layers[0].text === '新文案与原字号颜色一起保存', '原失焦提交文字仍保存完整样式')
    const calls = fixture.calls.filter(({ method }) => method === 'POST')
    assert.equal(calls.length, 3)
    assert.deepEqual(calls[2].data.params, { layer_id: 'command-text', text: '新文案与原字号颜色一起保存', font_size: 32, fill: '#141414' })
  })
})
test('图层属性边界：关面板及换owner/同owner失活后旧回复与重试不影响新实例或其他层', async () => {
  for (const mode of ['close', 'owner', 'inactive']) {
    await withLayerProperties(503, async ({ fixture, root, mounted, container }) => {
      let release
      const ordinary = fixture.h.request
      fixture.h.request = (config) => {
        if (config.method === 'POST' && config.url.endsWith('/tools')) {
          fixture.calls.push(config)
          return new Promise((resolve) => { release = () => resolve({ code: 503, msg: '技术旧属性迟到拒绝' }) })
        }
        return ordinary(config)
      }
      fillField(propertyRange(root, '旋转'), '75'); await pause(); releaseRange(propertyRange(root, '旋转'))
      await waitFor(() => release, '旧实例属性POST等待')
      if (mode === 'close') { findButton(root, '图层').click(); await pause(); await pause(); await new Promise((resolve) => setTimeout(resolve, 230)) }
      else {
        const next = layerPropertyFixture()
        next.state.document = { ...next.state.document, layers: [{ ...next.state.document.layers[0], id: 'other-layer', text: '另一个正式图层' }] }
        if (mode === 'owner') next.h.identity = { userId: 'next-property-owner' }
        else mounted.update({ ...fixture.h, active: false })
        mounted.update(next.h)
        await waitFor(() => findButton(container.shadowRoot, '图层'), '新实例重新挂载')
        findButton(container.shadowRoot, '图层').click()
        await waitFor(() => propertyRange(container.shadowRoot, '旋转'), '新实例属性面板')
        fillField(propertyRange(container.shadowRoot, '旋转'), '90'); await pause()
        release(); await pause(); await pause()
        const other = api.Konva.stages.find((stage) => stage.container()?.getRootNode() === container.shadowRoot).find('Text').find((text) => text.text() === '另一个正式图层')
        assert.equal(other.getParent().rotation(), 90, '旧finally不清新owner/实例其他层的未提交预览')
        assert.equal(next.calls.filter(({ method }) => method === 'POST').length, 0)
        assert.equal(next.notices.length, 0)
        return
      }
      release(); await pause(); await pause()
      assert.equal(Boolean(propertyRange(root, '旋转')), false, '旧拒绝不重新打开关闭面板')
      assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1)
    })
  }
})
test('图片墙选择边界：PATCH503不改A的subject选择，后续翻转仍指向A', async () => {
  await withWallSelection({ rejected: true }, async ({ fixture, root }) => {
    root.querySelector('button[title^="采用这张"]').click()
    await waitFor(() => fixture.calls.some((call) => call.method === 'PATCH'), '技术PATCH明确拒绝')
    await pause(); await pause()
    assert.equal(fixture.state.current_asset_id, 'a1')
    findButton(root, '水平翻转').click(); await pause(); await pause()
    assert.equal(fixture.calls.find((call) => call.data?.tool === 'flip_layer')?.data.params.layer_id, 'subject')
  })
})
test('图片墙选择边界：撤销重做正式doc移除旧层需校准，仍存在的有效id继续保留', async () => {
  await withWallSelection({}, async ({ fixture, root }) => {
    const ordinary = fixture.h.request
    fixture.h.request = async (config) => {
      if (config.method === 'POST' && /\/(undo|redo)$/.test(config.url)) {
        fixture.calls.push(config)
        fixture.state.document = config.url.endsWith('/undo') ? fixture.b : fixture.a
        fixture.state.row_version++
        return { code: 200, data: fixture.state }
      }
      return ordinary(config)
    }
    root.querySelector('button[title^="撤销"]').click(); await pause(); await pause()
    findButton(root, '水平翻转').click(); await pause(); await pause()
    assert.notEqual(fixture.calls.find((call) => call.data?.tool === 'flip_layer').data.params.layer_id, 'subject')
    root.querySelector('button[title^="重做"]').click(); await pause(); await pause()
    await waitFor(() => [...root.querySelectorAll('li button')].some((button) => button.textContent.startsWith('主体')), '重做恢复subject')
    ;[...root.querySelectorAll('li button')].find((button) => button.textContent.startsWith('主体')).click(); await pause()
    fixture.state.document = { ...fixture.a, layers: [...fixture.a.layers] }
    findButton(root, '水平翻转').click(); await pause(); await pause()
    const flips = fixture.calls.filter((call) => call.data?.tool === 'flip_layer')
    assert.equal(flips.at(-1).data.params.layer_id, 'subject', '正式新引用仍有subject时不无故清有效选择')
  })
})
test('图片墙选择边界：工具引发正式GET移除旧层与两个挂载的选择互相隔离', async () => {
  const first = wallSelectionFixture(), second = wallSelectionFixture()
  const containers = [document.createElement('div'), document.createElement('div')]
  containers.forEach((container) => document.body.append(container))
  const mounts = [api.mount(containers[0], first.h), api.mount(containers[1], second.h)]
  try {
    const roots = containers.map((container) => container.shadowRoot)
    for (const root of roots) {
      await waitFor(() => findButton(root, '图层'), '两个实例分别读正式A')
      findButton(root, '图层').click()
      await waitFor(() => [...root.querySelectorAll('li button')].some((button) => button.textContent.startsWith('主体')), '原列表')
      ;[...root.querySelectorAll('li button')].find((button) => button.textContent.startsWith('主体')).click(); await pause()
    }
    const ordinary = first.h.request
    first.h.request = async (config) => {
      if (config.method === 'POST' && config.data?.tool === 'split_layers') {
        first.calls.push(config)
        first.state.document = first.b; first.state.row_version++
        return { code: 200, data: { run: { id: 'r-wall-refresh', status: 'failed', tool: 'split_layers', error: '技术当前工具失败但正式GET已有新文档' }, session: first.state } }
      }
      return ordinary(config)
    }
    findButton(roots[0], '拆层').click(); await pause(); await pause()
    findButton(roots[0], '水平翻转').click(); findButton(roots[1], '水平翻转').click(); await pause(); await pause()
    assert.notEqual(first.calls.find((call) => call.data?.tool === 'flip_layer').data.params.layer_id, 'subject')
    assert.equal(second.calls.find((call) => call.data?.tool === 'flip_layer').data.params.layer_id, 'subject', '另一个实例仍保留自己的有效A主体')
  } finally { mounts.forEach((mount) => mount.unmount()); containers.forEach((container) => container.remove()) }
})

test('图层属性边界：订阅先failed解锁另一表单，迟到GET只撤旧旋转字段而保留同层新文字预览', async () => {
  await withLayerProperties(503, async ({ fixture, root, textOf }) => {
    let publish, release, reads = 0
    const formal = { id: 'r-late-property', tool: 'rotate_layer', status: 'failed', error: '技术旧旋转失败', progress: 100, result: {}, candidates: [] }
    fixture.h.subscribeRun = (_id, onSnapshot) => { publish = onSnapshot; return () => {} }
    const ordinary = fixture.h.request
    fixture.h.request = async (config) => {
      if (config.method === 'POST' && config.url.endsWith('/tools')) {
        fixture.calls.push(config)
        return { code: 200, data: { run: { ...formal, status: 'queued', progress: 0 }, session: fixture.state } }
      }
      if (config.url.endsWith('/runs/r-late-property')) {
        fixture.calls.push(config); reads++
        if (reads === 1) return { code: 200, data: { ...formal, status: 'running', progress: 30 } }
        if (reads === 2) return await new Promise((resolve) => { release = () => resolve({ code: 200, data: formal }) })
        return { code: 200, data: formal }
      }
      return ordinary(config)
    }
    fillField(propertyRange(root, '旋转'), '75'); await pause(); releaseRange(propertyRange(root, '旋转'))
    await waitFor(() => release && publish, '旧命令等待GET与正式订阅已存在')
    publish({ code: 200, data: formal })
    const input = root.querySelector('aside textarea:not([aria-label])')
    await waitFor(() => !input.disabled, '同层另一表单由正式订阅解锁')
    fillField(input, '同层后续未提交的新文案'); await pause()
    release(); await waitFor(() => !propertyRange(root, '旋转').disabled, '旧旋转等待GET也收到正式失败')
    const text = api.Konva.stages.find((stage) => stage.container()?.getRootNode() === root).find('Text').find((node) => node.text() === '同层后续未提交的新文案')
    assert.ok(text, '不能整体清空另一表单新文字preview')
    assert.equal(text.getParent().rotation(), 30, '旧拒绝旋转不能混进同层新文字预览继续盖住正式30度')
    assert.equal(input.value, '同层后续未提交的新文案')
    assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1)
  })
})

function startPropertyPointer(input, pointerId = 14) {
  const captures = []
  input.setPointerCapture = (id) => captures.push(id)
  input.dispatchEvent(new browser.PointerEvent('pointerdown', { pointerId, bubbles: true, composed: true }))
  assert.deepEqual(captures, [pointerId], '真实React原pointerdown路径仍调用capture')
}
test('滑杆手势单次：pointerup拒绝结算后迟到lostcapture不能重POST', async () => {
  await withLayerProperties(503, async ({ fixture, root, textOf }) => {
    const input = propertyRange(root, '旋转')
    startPropertyPointer(input)
    fillField(input, '75'); await pause()
    input.dispatchEvent(new browser.PointerEvent('pointerup', { pointerId: 14, bubbles: true, composed: true }))
    await waitFor(() => fixture.notices.some((notice) => notice.action?.label === '重试') && !input.disabled, '503与正式回读完整结算')
    input.dispatchEvent(new browser.PointerEvent('lostpointercapture', { pointerId: 14, bubbles: true, composed: true }))
    await pause(); await pause()
    assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1, '保留草稿不能重新放回同一手势的可提交槽')
    assert.equal(input.value, '75'); assert.equal(textOf().getParent().rotation(), 30)
  })
})
test('滑杆手势单次：正常捕获成功和提前lostcapture均只提交一次', async () => {
  for (const lostFirst of [false, true]) {
    await withLayerProperties(503, async ({ fixture, root }) => {
      fixture.accept()
      const input = propertyRange(root, '透明度'); startPropertyPointer(input)
      fillField(input, '0.4'); await pause()
      input.dispatchEvent(new browser.PointerEvent(lostFirst ? 'lostpointercapture' : 'pointerup', { pointerId: 14, bubbles: true, composed: true }))
      await waitFor(() => fixture.state.row_version === 8 && !input.disabled, '正式成功结算')
      input.dispatchEvent(new browser.PointerEvent(lostFirst ? 'pointerup' : 'lostpointercapture', { pointerId: 14, bubbles: true, composed: true }))
      await pause(); await pause()
      assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1)
    })
  }
})
test('滑杆手势单次：失败参数保留，新明确pointergesture无需再次改值即可重试', async () => {
  await withLayerProperties(503, async ({ fixture, root, textOf }) => {
    const input = propertyRange(root, '旋转'); startPropertyPointer(input)
    fillField(input, '75'); await pause(); releaseRange(input)
    await waitFor(() => fixture.notices.some((notice) => notice.action?.label === '重试') && !input.disabled, '原手势拒绝已完成')
    fixture.accept(); startPropertyPointer(input, 15)
    input.dispatchEvent(new browser.PointerEvent('pointerup', { pointerId: 15, bubbles: true, composed: true }))
    await waitFor(() => fixture.state.row_version === 8 && textOf().getParent().rotation() === 75, '新明确手势才重新提交保留值')
    input.dispatchEvent(new browser.PointerEvent('lostpointercapture', { pointerId: 15, bubbles: true, composed: true })); await pause()
    assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 2)
  })
})
test('滑杆手势单次：失败后明确双击复位按正式基线提交一次，旧lostcapture不参与', async () => {
  await withLayerProperties(503, async ({ fixture, root, textOf }) => {
    const input = propertyRange(root, '旋转'); startPropertyPointer(input)
    fillField(input, '75'); await pause(); releaseRange(input)
    await waitFor(() => fixture.notices.some((notice) => notice.action?.label === '重试') && !input.disabled, '原拒绝结算')
    fixture.accept(); input.closest('label').dispatchEvent(new browser.MouseEvent('dblclick', { bubbles: true }))
    await waitFor(() => fixture.state.row_version === 8 && textOf().getParent().rotation() === 0, '明确复位提交原origin0')
    input.dispatchEvent(new browser.PointerEvent('lostpointercapture', { pointerId: 14, bubbles: true, composed: true })); await pause()
    assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 2)
  })
})
test('滑杆手势单次：原键盘改值预览不新增自动POST，后续明确指针提交使用新值', async () => {
  await withLayerProperties(503, async ({ fixture, root, textOf }) => {
    const input = propertyRange(root, '旋转')
    input.dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }))
    fillField(input, '31'); await pause()
    input.dispatchEvent(new browser.KeyboardEvent('keyup', { key: 'ArrowRight', bubbles: true })); await pause()
    assert.equal(textOf().getParent().rotation(), 31, '原键盘输入仍预览，未新增键盘自动提交规则')
    assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 0)
    fixture.accept(); startPropertyPointer(input)
    input.dispatchEvent(new browser.PointerEvent('pointerup', { pointerId: 14, bubbles: true, composed: true }))
    await waitFor(() => fixture.state.row_version === 8 && fixture.state.document.layers[0].transform.rotation === 31, '后续明确指针使用完整键盘新值')
    assert.equal(fixture.calls.filter(({ method }) => method === 'POST').length, 1)
  })
})

// 画布焦点与选区失败：使用真实React/Query/Konva，响应仅在本地宿主内受控。
function selectionLifecycleFixture() {
  const fixture = editorFixture(), notices = []
  fixture.state.document = { width: 64, height: 80, layers: [] }
  fixture.state.assets = []
  let formal = null, mode = 'success', serial = 0, release, readFailures = 0, pendingInput, holdRead = false, releaseRead
  const ordinary = fixture.h.request
  fixture.h.notify = (notice) => notices.push(notice)
  fixture.h.request = async (config) => {
    if (config.url.endsWith('/selection/prepare')) {
      fixture.calls.push(config)
      return { code: 200, data: { id: 'selection-prepare', status: 'succeeded', result: {} } }
    }
    if (config.url.endsWith('/selection')) {
      fixture.calls.push(config)
      if (config.method === 'GET') {
        if (holdRead) return new Promise((resolve) => { releaseRead = () => resolve({ code: 200, data: formal }) })
        if (readFailures > 0) { readFailures--; return { code: 503, msg: '正式选区读取503原错误' } }
        return { code: 200, data: formal }
      }
      if (config.method === 'DELETE') {
        if (mode === 'delete503' || mode === 'delete403') return { code: mode === 'delete503' ? 503 : 403, msg: '清除选区503原错误' }
        formal = null
        return { code: 200 }
      }
      const id = ++serial
      pendingInput = config.data
      if (mode === 'hold503') return new Promise((resolve) => { release = () => resolve({ code: 503, msg: '迟到选区503原错误' }) })
      if (mode === 'post503' || mode === 'post409' || mode === 'replay409') return { code: mode === 'post503' ? 503 : 409, msg: '选择提交原错误' }
      if (mode === 'success') {
        formal = { revision: fixture.state.revision, markers: config.data.points?.map((point, index) => ({ ...point, index: index + 1 })) ?? [],
          mask: { id: `selection-mask-${id}`, kind: 'mask', source: 'selection', image_format: 'PNG', width: 64, height: 80 } }
        return { code: 200, data: { id: `selection-run-${id}`, status: 'succeeded', result: {} } }
      }
      return { code: 200, data: { id: `selection-run-${id}`, status: 'queued', result: {} } }
    }
    if (config.url.startsWith('/aipicture/runs/selection-run-')) {
      fixture.calls.push(config)
      if (mode === 'success') formal = { revision: fixture.state.revision, markers: pendingInput.points?.map((point, index) => ({ ...point, index: index + 1 })) ?? [],
        mask: { id: `selection-mask-${serial}`, kind: 'mask', source: 'selection', image_format: 'PNG', width: 64, height: 80 } }
      return { code: 200, data: { id: config.url.split('/').at(-1), status: mode === 'success' ? 'succeeded' : mode, error: mode === 'success' ? null : `正式选区${mode}原错误`, result: {} } }
    }
    return ordinary(config)
  }
  return { ...fixture, notices, get formal() { return formal }, setMode(value) { mode = value }, failReads(count) { readFailures = count },
    holdReads() { holdRead = true }, get reading() { return Boolean(releaseRead) }, releaseReads() { holdRead = false; releaseRead?.() }, release() { release?.() } }
}
async function withSelectionEditor(run) {
  const fixture = selectionLifecycleFixture(), container = document.createElement('div')
  document.body.append(container)
  const mounted = api.mount(container, fixture.h), root = container.shadowRoot
  const stageOf = () => api.Konva.stages.find((stage) => stage.container()?.getRootNode() === root)
  try {
    await waitFor(() => stageOf() && root.querySelector('input[aria-label="会话标题"]'), '正式编辑器已挂载')
    await run({ fixture, container, mounted, root, stageOf })
  } finally { fixture.release(); fixture.releaseReads(); mounted.unmount(); container.remove() }
}
function selectionStroke(stage) {
  let point = { x: 12, y: 16 }
  stage.getRelativePointerPosition = () => point
  stage.fire('mousedown', { evt: new browser.MouseEvent('mousedown') }, true)
  point = { x: 32, y: 40 }
  stage.fire('mousemove', { evt: new browser.MouseEvent('mousemove') }, true)
  stage.fire('mouseup', { evt: new browser.MouseEvent('mouseup') }, true)
}
const localStroke = (stage) => stage.find('Line').find((line) => line.stroke() === '#5f98ad' && line.opacity() === .45)
const localPins = (stage) => stage.find('Text').filter((text) => /^\d+$/.test(text.text()))

test('画布选区恢复：真实画布指针取得焦点，快捷键走当前焦点并保持影子输入隔离', async () => {
  await withSelectionEditor(async ({ fixture, root, stageOf }) => {
    const outside = document.createElement('button'); document.body.append(outside); outside.focus()
    try {
      // Node Konva没有浏览器content；派发到实际React Stage DOM容器，仍走原生事件冒泡及真实activeElement。
      stageOf().container().dispatchEvent(new browser.MouseEvent('mousedown', { bubbles: true, composed: true }))
      const focused = root.activeElement
      assert.ok(focused && focused.contains(stageOf().container()), '画布点击必须把真实焦点带入当前实例，而不是测试直接向root派发键盘')
      focused.dispatchEvent(new browser.KeyboardEvent('keydown', { key: '-', bubbles: true, composed: true }))
      await waitFor(() => stageOf().scaleX() < 1, '真实焦点缩小快捷键应生效')
      focused.dispatchEvent(new browser.KeyboardEvent('keydown', { key: '1', bubbles: true, composed: true }))
      await waitFor(() => Math.abs(stageOf().scaleX() - 1) < .001, '实际像素快捷键应恢复1倍')
      focused.dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, composed: true }))
      await waitFor(() => fixture.calls.some(({ url }) => url.endsWith('/undo')), '当前实例撤销应生效')
      const count = fixture.calls.filter(({ url }) => url.endsWith('/undo')).length
      const input = root.querySelector('input[aria-label="会话标题"]'); input.focus()
      input.dispatchEvent(new browser.MouseEvent('mousedown', { bubbles: true, composed: true }))
      assert.equal(root.activeElement, input)
      input.dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, composed: true }))
      const editable = document.createElement('div'); editable.contentEditable = 'true'; editable.tabIndex = 0
      focused.append(editable); editable.focus()
      editable.dispatchEvent(new browser.MouseEvent('mousedown', { bubbles: true, composed: true }))
      assert.equal(root.activeElement, editable, '嵌入画布内的contenteditable保留自己的焦点')
      editable.dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, composed: true }))
      editable.remove()
      outside.focus(); outside.dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, composed: true }))
      await pause(); assert.equal(fixture.calls.filter(({ url }) => url.endsWith('/undo')).length, count)
    } finally { outside.remove() }
  })
})

test('画布选区恢复：首笔POST503与旧修订409清临时笔刷，Esc不留下伪选区', async () => {
  for (const mode of ['post503', 'post409']) await withSelectionEditor(async ({ fixture, root, stageOf }) => {
    fixture.setMode(mode); findButton(root, '笔刷').click(); await pause()
    selectionStroke(stageOf())
    await waitFor(() => fixture.notices.some((notice) => notice.message === '选择提交原错误'), '原提交拒绝已反馈')
    await pause()
    assert.equal(Boolean(localStroke(stageOf())), false, '失败不应保留未正式保存的笔刷区域')
    root.querySelector('.aipicture-root').dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true }))
    await pause(); assert.equal(Boolean(localStroke(stageOf())), false)
    assert.equal(fixture.formal, null)
    assert.equal(fixture.calls.filter(({ method, url }) => method === 'POST' && url.endsWith('/selection')).length, 1)
  })
})

test('画布选区恢复：点选POST失败清临时标点，正式failed取消未知不当成功', async () => {
  for (const mode of ['post503', 'failed', 'canceled', 'external_result_unknown']) await withSelectionEditor(async ({ fixture, root, stageOf }) => {
    fixture.setMode(mode); findButton(root, '点选').click(); await pause()
    stageOf().getRelativePointerPosition = () => ({ x: 16, y: 20 })
    stageOf().fire('click', { evt: new browser.MouseEvent('click') }, true)
    await waitFor(() => fixture.notices.some((notice) => notice.type === 'error'), '正式选择失败已反馈')
    await pause()
    assert.equal(localPins(stageOf()).length, 0, '失败点选不能保留临时序号或猜测遮罩')
    assert.equal(fixture.formal, null)
    assert.equal(fixture.calls.filter(({ method, url }) => method === 'POST' && url.endsWith('/selection')).length, 1)
    assert.equal(fixture.calls.filter(({ method, url }) => method === 'POST' && url.endsWith('/selection/prepare')).length, 1, '同一修订的状态刷新不能重复派发预热')
  })
})

async function withSelectionProbe(run) {
  const fixture = selectionLifecycleFixture(), runtime = new api.HostRuntime(fixture.h), state = api.createBusinessState(runtime)
  const query = new api.QueryClient({ defaultOptions: { queries: { retry: 1, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } })
  let picking
  function Probe() { picking = api.useSelection('s-final', 2); return null }
  const container = document.createElement('div'); document.body.append(container)
  const renderer = api.createReactRoot(container)
  renderer.render(api.createElement(api.HostContext.Provider, { value: state }, api.createElement(api.QueryClientProvider, { client: query }, api.createElement(Probe))))
  try {
    await waitFor(() => picking && fixture.calls.some(({ method, url }) => method === 'GET' && url.endsWith('/selection')), '初始正式选区读取')
    const stroke = [{ x: .2, y: .3 }, { x: .4, y: .5 }]
    picking.addStroke(stroke); await waitFor(() => picking.selection?.maskId === 'selection-mask-1' && !picking.busy, '第一笔正式保存')
    picking.addStroke(stroke); await waitFor(() => picking.selection?.maskId === 'selection-mask-2' && !picking.busy, '第二笔正式保存')
    await run({ fixture, picking: () => picking, state, runtime })
  } finally { renderer.unmount(); runtime.dispose(); state.canvas.dispose(); query.clear(); container.remove() }
}

test('画布选区恢复：撤销DELETE503不提前移动历史游标，用户重试仍回第一笔', async () => {
  await withSelectionProbe(async ({ fixture, picking }) => {
    fixture.setMode('delete503'); picking().undo()
    await waitFor(() => fixture.notices.some((notice) => notice.message === '清除选区503原错误') && !picking().busy, 'DELETE失败已结束')
    assert.equal(picking().selection.maskId, 'selection-mask-2')
    assert.equal(picking().canRedo, false, '未正式撤销不能虚构已产生redo分支')
    fixture.setMode('success'); picking().undo()
    await waitFor(() => !picking().busy && picking().selection?.maskId === 'selection-mask-3', '明确再次撤销应重放第一笔')
    assert.equal(picking().canRedo, true)
    assert.equal(fixture.calls.filter(({ method, url }) => method === 'POST' && url.endsWith('/selection')).at(-1).data.strokes.length, 1)
  })
})

test('画布选区恢复：DELETE成功后回放POST409读正式空mask，不虚构回滚或重POST', async () => {
  await withSelectionProbe(async ({ fixture, picking }) => {
    fixture.setMode('replay409'); picking().undo()
    await waitFor(() => fixture.notices.some((notice) => notice.message === '选择提交原错误') && !picking().busy, '后续选择失败已结束')
    assert.equal(fixture.formal, null)
    assert.equal(picking().selection, null, '正式已清除，不能继续展示旧两笔mask')
    assert.equal(picking().canRedo, false)
    assert.equal(fixture.calls.filter(({ method, url }) => method === 'POST' && url.endsWith('/selection')).length, 3)
    fixture.setMode('success'); picking().undo()
    await waitFor(() => picking().selection?.maskId === 'selection-mask-4' && !picking().busy, '明确重试旧目标成功')
    assert.equal(picking().canRedo, true)
  })
})

test('画布选区恢复：成功撤销重做提交历史且清除可再撤销，未重复选择任务', async () => {
  await withSelectionProbe(async ({ fixture, picking }) => {
    picking().undo(); await waitFor(() => picking().selection?.maskId === 'selection-mask-3' && !picking().busy, '成功回第一笔')
    picking().redo(); await waitFor(() => picking().selection?.maskId === 'selection-mask-4' && !picking().busy, '成功重做两笔')
    assert.equal(fixture.calls.filter(({ method, url }) => method === 'POST' && url.endsWith('/selection')).at(-1).data.strokes.length, 2)
    picking().clear(); await waitFor(() => picking().selection === null && !picking().busy, '正式清除完成')
    picking().undo(); await waitFor(() => picking().selection?.maskId === 'selection-mask-5' && !picking().busy, '清除可撤销')
    assert.equal(fixture.calls.filter(({ method, url }) => method === 'POST' && url.endsWith('/selection')).length, 5)
  })
})

test('画布选区恢复：回放失败后的正式GET503只续读mask，重做失败不动游标', async () => {
  await withSelectionProbe(async ({ fixture, picking }) => {
    picking().undo(); await waitFor(() => picking().selection?.maskId === 'selection-mask-3' && !picking().busy, '先正式撤销一笔')
    fixture.setMode('delete403'); picking().redo()
    await waitFor(() => fixture.notices.length > 0 && !picking().busy, '正式DELETE拒绝已回读')
    assert.equal(picking().selection.maskId, 'selection-mask-3'); assert.equal(picking().canRedo, true)
    assert.equal(fixture.calls.filter(({ method, url }) => method === 'POST' && url.endsWith('/selection')).length, 3)
    fixture.setMode('replay409'); fixture.failReads(1); picking().redo()
    await waitFor(() => picking().selection === null && !picking().busy, '失败GET503后续读实际已清空mask')
    assert.equal(picking().canRedo, true, '正式回放未成功，原重做目标仍保留')
    assert.equal(fixture.calls.filter(({ method, url }) => method === 'POST' && url.endsWith('/selection')).length, 4)
  })
})

test('画布选区恢复：两实际焦点实例不串快捷键，内联文字指针不被画布抢焦点', async () => {
  await withCanvasCommand(503, async ({ fixture, root, stageOf, textOf }) => {
    await withSelectionEditor(async ({ fixture: other, root: secondRoot, stageOf: secondStage }) => {
      stageOf().container().dispatchEvent(new browser.MouseEvent('mousedown', { bubbles: true, composed: true }))
      root.activeElement.dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, composed: true }))
      await waitFor(() => fixture.calls.some(({ url }) => url.endsWith('/undo')), '第一实例真实焦点只撤销自己')
      assert.equal(other.calls.some(({ url }) => url.endsWith('/undo')), false)
      secondStage().container().dispatchEvent(new browser.MouseEvent('mousedown', { bubbles: true, composed: true }))
      secondRoot.activeElement.dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, composed: true }))
      await waitFor(() => other.calls.some(({ url }) => url.endsWith('/undo')), '第二实例焦点可独立操作')
      textOf().fire('dblclick', { evt: new browser.MouseEvent('dblclick') }, true)
      const editorOf = () => [...root.querySelectorAll('textarea')].find((input) => input.value !== '' && !input.hasAttribute('aria-label'))
      await waitFor(editorOf, '原内联文字编辑已打开')
      const text = editorOf()
      text.dispatchEvent(new browser.MouseEvent('mousedown', { bubbles: true, composed: true }))
      assert.equal(root.activeElement, text, '真实内联textarea不能被外层画布capture抢焦点')
      const count = fixture.calls.filter(({ url }) => url.endsWith('/undo')).length
      text.dispatchEvent(new browser.KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, composed: true }))
      await pause(); assert.equal(fixture.calls.filter(({ url }) => url.endsWith('/undo')).length, count)
    })
  })
})

test('画布选区恢复：旧身份迟到POST拒绝不清新实例未完成笔刷，失活不追加GET', async () => {
  await withSelectionEditor(async ({ fixture, container, mounted, root, stageOf }) => {
    fixture.setMode('hold503'); findButton(root, '点选').click(); await pause()
    stageOf().getRelativePointerPosition = () => ({ x: 16, y: 20 })
    stageOf().fire('click', { evt: new browser.MouseEvent('click') }, true)
    await waitFor(() => fixture.calls.some(({ method, url }) => method === 'POST' && url.endsWith('/selection')), '原选择POST已发起')
    const next = selectionLifecycleFixture(); next.setMode('hold503'); next.h.identity = { userId: 'other-owner' }
    mounted.update(next.h)
    try {
      await waitFor(() => stageOf() && root.querySelector('input[aria-label="会话标题"]'), '新身份正式画布重新挂载')
      findButton(root, '笔刷').click(); await pause(); selectionStroke(stageOf())
      await waitFor(() => Boolean(localStroke(stageOf())), '新实例当前笔刷预览已存在')
      const count = fixture.calls.length; fixture.release(); await pause(); await pause()
      assert.equal(Boolean(localStroke(stageOf())), true, '原身份迟到失败不能清新草稿')
      assert.equal(fixture.calls.length, count, '旧上下文不再回读selection或session')
      assert.equal(fixture.notices.length, 0, '原身份卸载后的错误不再弹出')
      next.release(); await waitFor(() => !localStroke(stageOf()), '新实例自己失败才清自己的临时笔刷')
    } finally { next.release() }
  })
})

test('画布选区恢复：明确退出未完笔刷立即清预览，迟到原失败不重新显示', async () => {
  await withSelectionEditor(async ({ fixture, root, stageOf }) => {
    fixture.setMode('hold503'); findButton(root, '笔刷').click(); await pause(); selectionStroke(stageOf())
    await waitFor(() => Boolean(localStroke(stageOf())), '本次未完笔刷仍等待正式结果')
    findButton(root, '完成').click(); await pause()
    assert.equal(Boolean(localStroke(stageOf())), false, '用户退出选择即清临时笔刷')
    fixture.release(); await waitFor(() => fixture.notices.length > 0, '原正式拒绝仍反馈')
    assert.equal(Boolean(localStroke(stageOf())), false)
    assert.equal(fixture.calls.filter(({ method, url }) => method === 'POST' && url.endsWith('/selection')).length, 1)
  })
})

test('画布选区恢复：POST仅被接受保留等待预览，正式成功且mask就绪才采用', async () => {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'Image'), OriginalImage = globalThis.Image
  Object.defineProperty(globalThis, 'Image', { configurable: true, value: function () {
    const image = new OriginalImage(); image.width = 64; image.height = 80
    let url = ''
    Object.defineProperty(image, 'src', { configurable: true, get: () => url, set(value) {
      url = value; queueMicrotask(() => image.onload?.(new browser.Event('load')))
    } })
    return image
  } })
  try {
    await withSelectionEditor(async ({ fixture, root, stageOf }) => {
      fixture.setMode('queued'); findButton(root, '笔刷').click(); await pause(); selectionStroke(stageOf())
      await waitFor(() => fixture.calls.some(({ url }) => url.startsWith('/aipicture/runs/selection-run-')), '已接受任务只轮询原run')
      assert.equal(fixture.formal, null); assert.equal(Boolean(localStroke(stageOf())), true)
      assert.equal(findButton(root, '清除').disabled, true, 'POST接受不冒充正式mask')
      fixture.setMode('success')
      await waitFor(() => fixture.formal && !localStroke(stageOf()), '正式成功和受控图片解码后采用遮罩')
      assert.ok(stageOf().find('Image').some((node) => node.width() === 64 && node.height() === 80 && node.opacity() === .45), 'mask仍按正式画布尺寸绘制')
      assert.equal(findButton(root, '撤销').disabled, false)
      assert.equal(fixture.calls.filter(({ method, url }) => method === 'POST' && url.endsWith('/selection')).length, 1)
    })
  } finally { Object.defineProperty(globalThis, 'Image', descriptor) }
})

test('画布选区恢复：旧修订拒绝回读最新文档，下一次明确选择使用正式新版本', async () => {
  await withSelectionEditor(async ({ fixture, root, stageOf }) => {
    fixture.setMode('post409'); findButton(root, '笔刷').click(); await pause()
    fixture.state.revision = 3; fixture.state.row_version = 8
    selectionStroke(stageOf())
    await waitFor(() => fixture.notices.some((notice) => notice.message === '选择提交原错误'), '旧修订原拒绝必须显示')
    await waitFor(() => !localStroke(stageOf()) && !findButton(root, '点选').disabled, '正式版本回读并清过期预览')
    const rejected = fixture.calls.find(({ method, url }) => method === 'POST' && url.endsWith('/selection'))
    assert.equal(rejected.data.revision, 2); assert.equal(rejected.data.expectedVersion, 7)
    fixture.setMode('success'); selectionStroke(stageOf())
    await waitFor(() => fixture.formal?.revision === 3, '用户新手势保存最新正式选区')
    const calls = fixture.calls.filter(({ method, url }) => method === 'POST' && url.endsWith('/selection'))
    assert.equal(calls.length, 2); assert.equal(calls[1].data.revision, 3); assert.equal(calls[1].data.expectedVersion, 8)
  })
})

test('画布选区恢复：正式拒绝立即清临时笔刷并显示错误，mask回读在途不延迟反馈', async () => {
  await withSelectionEditor(async ({ fixture, root, stageOf }) => {
    fixture.setMode('post503'); fixture.holdReads(); findButton(root, '笔刷').click(); await pause(); selectionStroke(stageOf())
    await waitFor(() => fixture.reading, '正式失败后只GET校准当前mask')
    await pause()
    assert.equal(Boolean(localStroke(stageOf())), false, '正式失败已知，不能等mask GET结束才撤去伪选区')
    assert.ok(fixture.notices.some((notice) => notice.message === '选择提交原错误'), '原失败需立即反馈')
    assert.equal(fixture.calls.filter(({ method, url }) => method === 'POST' && url.endsWith('/selection')).length, 1)
    fixture.releaseReads(); await waitFor(() => !findButton(root, '点选').disabled, '正式回读结束后恢复可操作')
  })
})

test.after(async () => {
  await browser.happyDOM.abort()
  const longTimers = [...pendingTimers.values()].filter((entry) => entry.delay > 5000)
  assert.equal(longTimers.length, 0, '卸载后不应遗留五分钟 Query/Mutation GC timer')
})
