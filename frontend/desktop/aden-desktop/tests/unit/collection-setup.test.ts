import { beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { CollectionSetupService } from '../../src/main/collection/collection-setup'

const files = vi.hoisted(() => new Map<string, string>())
vi.mock('electron', () => ({ app: {}, shell: {} }))
vi.mock('node:fs/promises', () => ({
  readFile: vi.fn(async (path: string) => { if (!files.has(path)) throw new Error('missing'); return files.get(path) }),
  access: vi.fn(async (path: string) => { if (!files.has(path)) throw new Error('missing') })
}))

describe('固定扩展安装流程与诚实本地状态', () => {
  beforeEach(() => files.clear())
  function setup() {
    const extensionPath = resolve('test-install', 'resources', 'collector', 'extension')
    const hostDirectory = resolve('test-install', 'resources', 'collector')
    const manifestPath = join(hostDirectory, 'com.aden.collector.dev.json')
    files.set(join(extensionPath, 'manifest.json'), readFileSync(resolve('../aden-collector-extension/manifest.json'), 'utf8'))
    for (const file of ['background.js', 'config.js', 'panel.html', 'panel.js', 'panel.css', 'parser.js', 'picker.js', 'resources.js', 'spool.js', 'sections.js', 'page-watch.js']) files.set(join(extensionPath, file), 'file')
    files.set(join(hostDirectory, 'AdenCollectorHost.exe'), 'exe')
    files.set(manifestPath, JSON.stringify({ name: 'com.aden.collector.dev', type: 'stdio', path: join(hostDirectory, 'AdenCollectorHost.exe'), allowed_origins: ['chrome-extension://mnejmmlalapfhnanlnckfdhmfpbahidm/'] }))
    const options = { extensionPath, hostDirectory, chromePath: vi.fn(async () => 'C:/Chrome/chrome.exe' as string | null), registeredManifest: vi.fn(async () => manifestPath), openFolder: vi.fn(async (_path: string) => ''), openChrome: vi.fn(async (_path: string) => {}), writeClipboard: vi.fn((_path: string) => {}) }
    return { options, service: new CollectionSetupService(options) }
  }
  it.each(['parser.js', 'sections.js', 'page-watch.js'])('copies only the complete directory and rejects it after %s disappears', async (removedFile) => {
    const { service, options } = setup()
    expect((await service.status()).extensionReady).toBe(true)
    await expect(service.copyDirectory()).resolves.toMatchObject({ copied: true, path: options.extensionPath })
    expect(options.writeClipboard).toHaveBeenCalledExactlyOnceWith(options.extensionPath)
    files.delete(join(options.extensionPath, removedFile))
    expect((await service.status()).extensionReady).toBe(false)
    await expect(service.copyDirectory()).rejects.toThrow('不可用')
    expect(options.writeClipboard).toHaveBeenCalledTimes(1)
  })
  it('reports clipboard failure without claiming copied or reading clipboard', async () => {
    const { service, options } = setup()
    options.writeClipboard.mockImplementation(() => { throw new Error('clipboard denied') })
    await expect(service.copyDirectory()).rejects.toThrow('手工选择')
    expect(options.openChrome).not.toHaveBeenCalled()
  })
  it('核对固定ID、文件、Host与注册，不将其称为已安装或已连接', async () => {
    const { service } = setup()
    const result = await service.status()
    expect(result).toMatchObject({ extensionReady: true, extensionId: 'mnejmmlalapfhnanlnckfdhmfpbahidm', extensionVersion: JSON.parse(readFileSync(resolve('../aden-collector-extension/manifest.json'), 'utf8')).version, hostReady: true, registrationReady: true, registrationKind: 'current', problems: [] })
    expect(result).not.toHaveProperty('connected'); expect(result).not.toHaveProperty('installed')
  })
  it('打开仅使用固定目录和检测到的Chrome，不把调用成功当加载成功', async () => {
    const { service, options } = setup(); const result = await service.openInstall()
    expect(options.openFolder).toHaveBeenCalledWith(options.extensionPath)
    expect(options.openChrome).toHaveBeenCalledWith('C:/Chrome/chrome.exe')
    expect(result).toMatchObject({ folderOpened: true, chromeOpened: true })
    expect(result.messages.join('')).toContain('不代表扩展已安装或已连接')
  })
  it('缺少扩展资源时拒绝打开安装流程', async () => {
    const { service, options } = setup(); files.delete(join(options.extensionPath, 'spool.js'))
    const result = await service.openInstall()
    expect(result.status.extensionReady).toBe(false)
    expect(options.openFolder).not.toHaveBeenCalled(); expect(options.openChrome).not.toHaveBeenCalled()
  })
  it('注册到另一安装或额外授权源时不误报正常', async () => {
    const { service, options } = setup(); options.registeredManifest.mockResolvedValue('C:/another/com.aden.collector.dev.json')
    expect((await service.status()).registrationReady).toBe(false)
    options.registeredManifest.mockResolvedValue(join(options.hostDirectory, 'com.aden.collector.dev.json'))
    files.set(join(options.hostDirectory, 'com.aden.collector.dev.json'), JSON.stringify({ name: 'com.aden.collector.dev', type: 'stdio', path: join(options.hostDirectory, 'AdenCollectorHost.exe'), allowed_origins: ['chrome-extension://evil/'] }))
    expect((await service.status()).registrationReady).toBe(false)
  })
  it('另一安装的 Host 内容相同且来源权限正确时标为兼容，不误报为本安装', async () => {
    const { service, options } = setup()
    const oldManifest = resolve('old-install', 'resources', 'collector', 'com.aden.collector.dev.json')
    const oldHost = resolve('old-install', 'resources', 'collector', 'AdenCollectorHost.exe')
    files.set(oldManifest, JSON.stringify({ name: 'com.aden.collector.dev', type: 'stdio', path: oldHost, allowed_origins: ['chrome-extension://mnejmmlalapfhnanlnckfdhmfpbahidm/'] }))
    files.set(oldHost, 'exe')
    options.registeredManifest.mockResolvedValue(oldManifest)
    expect(await service.status()).toMatchObject({ registrationReady: true, registrationKind: 'compatible', registeredHostPath: oldHost, problems: [] })
    files.set(oldHost, 'different exe')
    expect(await service.status()).toMatchObject({ registrationReady: false, registrationKind: 'unavailable' })
  })
  it('Chrome缺失、目录失败可分别回报并保留手动路径', async () => {
    const { service, options } = setup(); options.chromePath.mockResolvedValue(null); options.openFolder.mockResolvedValue('system failure')
    const result = await service.openInstall()
    expect(result).toMatchObject({ folderOpened: false, chromeOpened: false })
    expect(result.messages.join('')).toContain('chrome://extensions')
    expect(options.openChrome).not.toHaveBeenCalled()
  })
  it('Chrome启动失败不影响已打开目录，也不返回成功完成', async () => {
    const { service, options } = setup(); options.openChrome.mockRejectedValue(new Error('cannot launch'))
    const result = await service.openInstall()
    expect(result).toMatchObject({ folderOpened: true, chromeOpened: false })
    expect(result.messages.join('')).toContain('启动失败')
  })
})
