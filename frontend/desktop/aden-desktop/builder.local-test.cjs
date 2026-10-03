module.exports = {
  appId: 'com.aden.desktop.localtest',
  productName: 'Aden Local Test',
  directories: { output: 'dist/local-test' },
  files: ['out/**/*', 'package.json', 'local-test.json'],
  extraResources: [
    { from: '../aden-collector-extension', to: 'collector/extension', filter: ['manifest.json', '*.js', 'panel.html', 'panel.css', '!package.json'] },
    { from: 'scripts/collector-pipe.ps1', to: 'collector/collector-pipe.ps1' },
    { from: 'test-results/collector-native/dist/AdenCollectorHost.exe', to: 'collector/AdenCollectorHost.exe' },
    { from: '../../../python/aden-runner/scripts/register-native-host.ps1', to: 'collector/register-native-host.ps1' },
    { from: '../../../python/aden-runner/scripts/unregister-native-host.ps1', to: 'collector/unregister-native-host.ps1' }
  ],
  asar: false,
  win: { target: ['nsis'] },
  nsis: {
    include: 'scripts/collector-installer.nsh',
    oneClick: false,
    perMachine: false,
    allowToChangeInstallationDirectory: false,
    createDesktopShortcut: true,
    artifactName: 'Aden-Local-Test-${version}-Setup.${ext}'
  }
}
