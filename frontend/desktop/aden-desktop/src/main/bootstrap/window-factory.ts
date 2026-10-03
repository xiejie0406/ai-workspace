import { app, BrowserWindow } from 'electron'
import { join } from 'node:path'
import type { RendererTrust } from '../config/trusted-origins'
import { installSessionSecurity, installWebContentsSecurity } from '../security/web-contents-policy'

export interface WindowFactoryOptions {
  readonly trust: RendererTrust
  readonly preloadPath: string
  readonly rendererPath: string
  readonly show: boolean
}

export function createMainWindow(options: WindowFactoryOptions): BrowserWindow {
  const window = new BrowserWindow({
    width: 1360,
    height: 860,
    minWidth: 1024,
    minHeight: 720,
    show: false,
    backgroundColor: '#f3f5f2',
    autoHideMenuBar: true,
    webPreferences: {
      preload: options.preloadPath,
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      allowRunningInsecureContent: false,
      webviewTag: false,
      spellcheck: false,
      additionalArguments: [`--aden-desktop-version=${app.getVersion()}`]
    }
  })
  installSessionSecurity(window.webContents.session, options.trust)
  installWebContentsSecurity(window, options.trust)
  if (options.show) window.once('ready-to-show', () => window.show())

  if (options.trust.development && options.trust.devOrigin) {
    void window.loadURL(options.trust.devOrigin)
  } else {
    void window.loadFile(options.rendererPath)
  }
  return window
}

export function defaultWindowPaths(baseDirectory: string): { preloadPath: string; rendererPath: string } {
  return {
    preloadPath: join(baseDirectory, '../preload/index.cjs'),
    rendererPath: join(baseDirectory, '../renderer/index.html')
  }
}
