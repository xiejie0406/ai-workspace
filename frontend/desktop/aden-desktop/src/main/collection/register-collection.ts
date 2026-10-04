import { dialog, type BrowserWindow, type IpcMain } from 'electron'
import { readFile, stat, writeFile, rename, unlink } from 'node:fs/promises'
import { basename } from 'node:path'
import { randomUUID } from 'node:crypto'
import { COLLECTION_IPC, type CollectionRequest } from '../../shared/contracts/collection'
import { parseWorkspaceList } from '../../shared/contracts/operator-bootstrap'
import type { RendererTrust } from '../config/trusted-origins'
import type { SessionController } from '../auth/session-controller'
import type { AdenApiClient } from '../transport/api-client'
import type { AdenAuthService } from '../auth/auth-service'
import { assertPlainPayload, assertTrustedSender } from '../ipc/validate-sender'
import { serializeTransportError } from '../transport/error-mapper'
import { CollectionSession } from './collection-session'
import { productWorkbook, sourceManifest } from './export-workbook'

export function registerCollection(options: { ipcMain: IpcMain; window: BrowserWindow; trust: RendererTrust; session: SessionController; api: AdenApiClient; auth: AdenAuthService }): () => void {
  const { ipcMain, window, trust, session, api, auth } = options
  const collection = new CollectionSession()
  let lifetime: AbortController | null = null
  let fileBusy = false
  let gateBusy = false
  const assertContext = (input: CollectionRequest): void => {
    const now = session.context()
    if (!now.authenticated || !now.workspaceId || now.workspaceId !== input.workspaceId || now.workspaceEpoch !== input.workspaceEpoch || now.sessionEpoch !== input.sessionEpoch) {
      throw new TypeError('登录或工作空间已变化，请重新进入采集页')
    }
  }
  const verify = async (input: CollectionRequest): Promise<void> => {
    assertContext(input)
    const user = await auth.getInfo()
    if (!user.permissions.includes('*:*:*') && !user.permissions.includes('aden:workspace:list')) throw new TypeError('当前账号没有工作空间访问权限')
    const response = await api.request<unknown>({ path: '/api/v1/aden/workspaces' })
    assertContext(input)
    if (!parseWorkspaceList(response.data).items.some(w => w.workspaceId === input.workspaceId)) throw new TypeError('工作空间访问已撤销')
    if (!lifetime || lifetime.signal.aborted) {
      lifetime = session.trackedAbortController()
      lifetime.signal.addEventListener('abort', () => collection.reset(), { once: true })
    }
  }
  const save = async (data: Buffer | string, extension: string, name: string, input: CollectionRequest): Promise<object> => {
    const result = await dialog.showSaveDialog(window, { defaultPath: name, filters: [{ name: extension.toUpperCase(), extensions: [extension] }] })
    assertContext(input)
    if (result.canceled || !result.filePath) return { cancelled: true }
    const temp = `${result.filePath}.${randomUUID()}.tmp`
    try { await writeFile(temp, data, { flag: 'wx' }); assertContext(input); await rename(temp, result.filePath) }
    finally { await unlink(temp).catch(() => {}) }
    return { cancelled: false, filename: basename(result.filePath) }
  }
  for (const [action, channel] of Object.entries(COLLECTION_IPC)) {
    ipcMain.handle(channel, async (event, raw: unknown) => {
      try {
        assertTrustedSender(event, trust)
        if (event.sender !== window.webContents) throw new TypeError('采集仅允许本窗口调用')
        assertPlainPayload(raw, 64 * 1024)
        if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new TypeError('采集请求格式错误')
        const input = raw as CollectionRequest
        const allowed = ['workspaceId', 'sessionEpoch', 'workspaceEpoch', ...(action === 'start' ? ['urls'] : []), ...(action === 'update' ? ['entryId', 'skuId', 'values'] : []), ...(action === 'export' ? ['format'] : [])]
        if (Object.keys(raw).some(k => !allowed.includes(k))) throw new TypeError('采集请求包含未知字段')
        assertContext(input)
        let value: unknown
        if (action === 'cancel') value = collection.cancel()
        else if (action === 'snapshot') value = collection.snapshot()
        else {
          if (gateBusy || fileBusy) throw new TypeError('操作正在执行，请稍后再试')
          gateBusy = true
          try {
            await verify(input)
            if (action === 'start') {
              if (typeof input.urls !== 'string') throw new TypeError('缺少商品链接')
              value = collection.start(input.urls, lifetime!.signal)
            } else if (action === 'retry') value = collection.retry(lifetime!.signal)
            else if (action === 'update') {
              if (typeof input.entryId !== 'string' || typeof input.skuId !== 'string') throw new TypeError('缺少SKU标识')
              value = collection.update(input.entryId, input.skuId, input.values)
            } else {
              if (collection.snapshot().running) throw new TypeError('请在采集结束后保存或导出')
              fileBusy = true
              try {
                if (action === 'save') value = await save(JSON.stringify(collection.snapshot(), null, 2), 'json', '商品采集草稿.json', input)
                else if (action === 'load') {
                  const result = await dialog.showOpenDialog(window, { properties: ['openFile'], filters: [{ name: '商品采集草稿', extensions: ['json'] }] })
                  assertContext(input)
                  if (result.canceled || !result.filePaths[0]) value = { cancelled: true }
                  else {
                    if ((await stat(result.filePaths[0])).size > 20 * 1024 * 1024) throw new TypeError('草稿超过20MiB')
                    const draft = JSON.parse(await readFile(result.filePaths[0], 'utf8'))
                    assertContext(input)
                    value = { cancelled: false, filename: basename(result.filePaths[0]), snapshot: collection.load(draft) }
                  }
                } else if (action === 'export') {
                  if (!['xlsx', 'sources'].includes(input.format ?? '')) throw new TypeError('导出格式无效')
                  const snapshot = collection.snapshot()
                  value = input.format === 'xlsx' ? await save(productWorkbook(snapshot), 'xlsx', '商品资料-v2.xlsx', input)
                    : await save(JSON.stringify(sourceManifest(snapshot), null, 2), 'json', '商品来源清单.json', input)
                }
              } finally { fileBusy = false }
            }
          } finally { gateBusy = false }
        }
        assertContext(input)
        return { ok: true, value, context: session.context() }
      } catch (error) { return { ok: false, error: serializeTransportError(error), context: session.context() } }
    })
  }
  const reset = (): void => { lifetime?.abort(); collection.reset() }
  window.webContents.on('did-start-loading', reset)
  return () => { reset(); window.webContents.removeListener('did-start-loading', reset); Object.values(COLLECTION_IPC).forEach(c => ipcMain.removeHandler(c)) }
}
