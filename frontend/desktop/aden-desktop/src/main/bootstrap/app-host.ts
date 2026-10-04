import { app, dialog, ipcMain, type BrowserWindow } from 'electron'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { createRendererTrust } from '../config/trusted-origins'
import { resolveRuntimeConfig } from '../config/runtime-config'
import { createMainWindow, defaultWindowPaths } from './window-factory'
import { SessionController } from '../auth/session-controller'
import { AdenApiClient } from '../transport/api-client'
import { AdenAuthService } from '../auth/auth-service'
import { AdenSseCoordinator } from '../transport/sse-client'
import { DESKTOP_IPC as IPC } from '../../shared/contracts/desktop-api'
import { registerDesktopIpcHandlers } from '../ipc/register-handlers'
import { registerCollection } from '../collection/register-collection'

declare const __ADEN_PACKAGE_CHANNEL__: 'local-test' | 'release'

function configuredApiBaseUrl(): string | undefined {
  if (!app.isPackaged || __ADEN_PACKAGE_CHANNEL__ !== 'local-test') {
    return process.env.ADEN_API_BASE_URL
  }
  const file = join(app.getAppPath(), 'local-test.json')
  let config: unknown
  try {
    config = JSON.parse(readFileSync(file, 'utf8'))
  } catch {
    throw new Error('本地测试版配置缺失或格式错误：请检查安装目录 resources/app/local-test.json')
  }
  if (!config || typeof config !== 'object' || !('apiBaseUrl' in config)
      || typeof config.apiBaseUrl !== 'string') {
    throw new Error('本地测试版配置必须包含字符串 apiBaseUrl')
  }
  return config.apiBaseUrl
}

export class AdenAppHost {
  #window: BrowserWindow | null = null
  #disposeIpc: (() => void) | null = null

  start(baseDirectory: string): void {
    const development = !app.isPackaged
    const paths = defaultWindowPaths(baseDirectory)
    const config = resolveRuntimeConfig(
      configuredApiBaseUrl(), development, app.isPackaged && __ADEN_PACKAGE_CHANNEL__ === 'local-test'
    )
    const trust = createRendererTrust(development, process.env.ELECTRON_RENDERER_URL, paths.rendererPath)
    const smoke = process.env.ADEN_DESKTOP_SMOKE === '1'
    const window = createMainWindow({ ...paths, trust, show: !smoke })
    this.#window = window

    const session = new SessionController()
    const api = new AdenApiClient(config, session)
    const auth = new AdenAuthService(api)
    const sse = new AdenSseCoordinator(config, session, {
      sendBatch: (batch) => this.#send(IPC.eventBatch, batch),
      sendStatus: (status) => this.#send(IPC.streamStatus, status),
      requireBootstrap: (context, reason) => this.#send(IPC.streamStatus, {
        ...context, state: 'DEGRADED', reason
      })
    })
    const disposeDesktop = registerDesktopIpcHandlers({
      ipcMain,
      trust,
      webContents: window.webContents,
      session,
      api,
      auth,
      sse
    })
    const disposeCollection = registerCollection({ ipcMain, window, trust, session, api, auth })
    this.#disposeIpc = () => { disposeCollection(); disposeDesktop() }

    window.on('closed', () => {
      this.#disposeIpc?.()
      this.#disposeIpc = null
      this.#window = null
    })
    window.webContents.on('did-fail-load', (_event, code, description, url, isMainFrame) => {
      if (!isMainFrame || code === -3) return
      dialog.showErrorBox('Aden 页面加载失败', '桌面页面无法安全加载，请检查本地构建产物后重试。')
      if (smoke) app.exit(2)
      void description
      void url
    })
    if (smoke) this.#runSmoke(window)
  }

  activate(): void {
    if (this.#window) {
      if (this.#window.isMinimized()) this.#window.restore()
      this.#window.focus()
    }
  }

  #send(channel: string, payload: unknown): void {
    if (this.#window && !this.#window.isDestroyed()) this.#window.webContents.send(channel, payload)
  }

  #runSmoke(window: BrowserWindow): void {
    window.webContents.once('did-finish-load', async () => {
      try {
        const renderer = await window.webContents.executeJavaScript(`({
          requireType: typeof require,
          processType: typeof process,
          opened: window.open('https://example.invalid') !== null
        })`)
        if (renderer.requireType !== 'undefined'
            || renderer.processType !== 'undefined'
            || renderer.opened !== false) {
          throw new Error('窗口安全配置未锁定')
        }
        process.stdout.write('ADEN_ELECTRON_SMOKE_OK\n')
        app.exit(0)
      } catch {
        process.stderr.write('ADEN_ELECTRON_SMOKE_FAILED\n')
        app.exit(3)
      }
    })
  }
}
