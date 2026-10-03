import { app, clipboard, shell } from 'electron'
import { access, readFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { createHash } from 'node:crypto'
import { execFile, spawn } from 'node:child_process'
import { promisify } from 'node:util'
import type { CollectionDirectoryCopied, CollectionSetupOpened, CollectionSetupStatus } from '../../shared/contracts/collection-setup'

const EXTENSION_ID = 'mnejmmlalapfhnanlnckfdhmfpbahidm'
const HOST_NAME = 'com.aden.collector.dev'
const execute = promisify(execFile)
async function exists(path: string): Promise<boolean> { try { await access(path); return true } catch { return false } }
interface SetupOptions {
  extensionPath: string
  hostDirectory: string
  chromePath(): Promise<string | null>
  registeredManifest(): Promise<string | null>
  openFolder(path: string): Promise<string>
  openChrome(path: string): Promise<void>
  writeClipboard?(path: string): void
}

/** 仅使用应用自有固定路径与固定 Chrome 管理页；renderer 不得提供文件或 URL。 */
export class CollectionSetupService {
  constructor(private readonly options: SetupOptions = defaultOptions()) {}
  async status(): Promise<CollectionSetupStatus> {
    const o = this.options
    const problems: string[] = []
    let extensionVersion: string | null = null
    let extensionReady = false
    try {
      const manifest = JSON.parse(await readFile(join(o.extensionPath, 'manifest.json'), 'utf8'))
      const id = createHash('sha256').update(Buffer.from(manifest.key, 'base64')).digest('hex').slice(0, 32)
        .replace(/[0-9a-f]/g, c => String.fromCharCode(97 + parseInt(c, 16)))
      if (manifest.manifest_version !== 3 || id !== EXTENSION_ID || typeof manifest.version !== 'string') throw new Error()
      extensionVersion = manifest.version
      const files = ['background.js', 'config.js', 'panel.html', 'panel.js', 'panel.css', 'parser.js', 'picker.js', 'resources.js', 'spool.js', 'sections.js', 'page-watch.js']
      extensionReady = (await Promise.all(files.map(f => exists(join(o.extensionPath, f))))).every(Boolean)
      if (!extensionReady) problems.push('扩展文件不完整，请重新安装最新版 Aden。')
    } catch { problems.push('未找到有效的随包扩展，请重新安装最新版 Aden。') }
    const hostPath = join(o.hostDirectory, 'AdenCollectorHost.exe')
    const hostReady = await exists(hostPath)
    if (!hostReady) problems.push('Native Host 文件缺失，请修复或重新安装 Aden。')
    let registrationKind: CollectionSetupStatus['registrationKind'] = 'unavailable'
    let registeredHostPath: string | null = null
    try {
      const manifestPath = join(o.hostDirectory, `${HOST_NAME}.json`)
      const registered = await o.registeredManifest()
      if (registered && hostReady) {
        const manifest = JSON.parse(await readFile(registered, 'utf8'))
        const valid = manifest.name === HOST_NAME && manifest.type === 'stdio' &&
          typeof manifest.path === 'string' && Array.isArray(manifest.allowed_origins) &&
          manifest.allowed_origins.length === 1 && manifest.allowed_origins[0] === `chrome-extension://${EXTENSION_ID}/`
        if (valid && await exists(manifest.path)) {
          registeredHostPath = manifest.path
          if (samePath(registered, manifestPath) && samePath(manifest.path, hostPath)) {
            registrationKind = 'current'
          } else if (sameHash(await readFile(manifest.path), await readFile(hostPath))) {
            registrationKind = 'compatible'
          }
        }
      }
    } catch { /* 返回可操作的本地诊断，不把系统命令输出暴露给页面。 */ }
    const registrationReady = registrationKind !== 'unavailable'
    if (!registrationReady) problems.push('当前用户未注册可兼容的 Native Host。请使用本安装包修复；若已注册另一份 Aden，请先处理原安装，避免连接到错误版本。')
    const chromePath = await o.chromePath()
    if (!chromePath) problems.push('未在标准安装位置找到 Google Chrome。请安装 Chrome，或手动在 Chrome 地址栏打开 chrome://extensions。')
    return { extensionPath: o.extensionPath, extensionVersion, extensionId: EXTENSION_ID, extensionReady,
      hostPath, hostReady, registrationReady, registrationKind, registeredHostPath, chromePath, problems,
      checkedAt: new Date().toISOString() }
  }
  /** 不接收 renderer 路径；每次复制均重新检查自己的安装资源。 */
  async copyDirectory(): Promise<CollectionDirectoryCopied> {
    const status = await this.status()
    if (!status.extensionReady) throw new Error('插件目录不可用，请重新检测；可手工选择下方路径。')
    try { (this.options.writeClipboard ?? (path => clipboard.writeText(path)))(status.extensionPath) }
    catch { throw new Error('未能复制，请手工选择目录文字复制。') }
    return { copied: true, path: status.extensionPath, status }
  }
  async openInstall(): Promise<CollectionSetupOpened> {
    const status = await this.status()
    const result: CollectionSetupOpened = { status, folderOpened: false, chromeOpened: false, messages: [] }
    if (!status.extensionReady) { result.messages.push('扩展文件检查未通过，未打开安装流程。'); return result }
    try {
      const error = await this.options.openFolder(status.extensionPath)
      if (error) result.messages.push('扩展目录打开失败，请按下方路径手动打开。')
      else result.folderOpened = true
    } catch { result.messages.push('扩展目录打开失败，请按下方路径手动打开。') }
    if (status.chromePath) {
      try { await this.options.openChrome(status.chromePath); result.chromeOpened = true }
      catch { result.messages.push('Chrome 启动失败，请手动在 Chrome 地址栏打开 chrome://extensions。') }
    } else result.messages.push('未找到 Chrome，请手动打开 chrome://extensions。')
    result.messages.push('请在 Chrome 完成加载；打开安装流程不代表扩展已安装或已连接。')
    return result
  }
}

function samePath(a: string, b: string): boolean { return resolve(a).toLowerCase() === resolve(b).toLowerCase() }
function sameHash(a: Buffer, b: Buffer): boolean {
  return createHash('sha256').update(a).digest('hex') === createHash('sha256').update(b).digest('hex')
}
function defaultOptions(): SetupOptions {
  const hostDirectory = app.isPackaged ? join(process.resourcesPath, 'collector') : join(app.getAppPath(), 'test-results', 'collector-native', 'dist')
  return {
    extensionPath: app.isPackaged ? join(process.resourcesPath, 'collector', 'extension') : resolve(app.getAppPath(), '..', 'aden-collector-extension'),
    hostDirectory,
    chromePath: async () => {
      if (process.platform !== 'win32') return null
      for (const base of [process.env.PROGRAMFILES, process.env['PROGRAMFILES(X86)'], process.env.LOCALAPPDATA]) {
        if (!base) continue
        const path = join(base, 'Google', 'Chrome', 'Application', 'chrome.exe')
        if (await exists(path)) return path
      }
      return null
    },
    registeredManifest: async () => {
      if (process.platform !== 'win32' || !process.env.SystemRoot) return null
      try {
        const { stdout } = await execute(join(process.env.SystemRoot, 'System32', 'reg.exe'),
          ['query', `HKCU\\Software\\Google\\Chrome\\NativeMessagingHosts\\${HOST_NAME}`, '/ve'],
          { windowsHide: true, timeout: 5000, maxBuffer: 16384 })
        return stdout.match(/REG_SZ\s+([^\r\n]+)/)?.[1].trim() ?? null
      } catch { return null }
    },
    openFolder: path => shell.openPath(path),
    openChrome: path => new Promise<void>((resolvePromise, reject) => {
      const child = spawn(path, ['--new-window', 'chrome://extensions'], { detached: true, stdio: 'ignore', windowsHide: false })
      child.once('error', reject)
      child.once('spawn', () => { child.unref(); resolvePromise() })
    })
  }
}
