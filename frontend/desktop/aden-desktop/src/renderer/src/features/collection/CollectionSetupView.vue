<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { CollectionSetupStatus } from '../../../../shared/contracts/collection-setup'
const status = ref<CollectionSetupStatus | null>(null)
const checking = ref(false)
const copying = ref(false)
const error = ref('')
const message = ref('')
let generation = 0
async function check(): Promise<void> {
  const request = ++generation
  checking.value = true; error.value = ''; message.value = ''
  try {
    const result = await window.adenDesktop.collectionSetup.status()
    if (request === generation) status.value = result.value
  } catch (cause) {
    if (request === generation) { status.value = null; error.value = cause instanceof Error ? cause.message : '暂时无法检测插件目录，请重试。' }
  } finally { if (request === generation) checking.value = false }
}
async function copy(): Promise<void> {
  if (copying.value) return
  const request = ++generation
  copying.value = true; checking.value = false; error.value = ''; message.value = ''
  try {
    const result = await window.adenDesktop.collectionSetup.copyDirectory()
    if (request === generation) { status.value = result.value.status; message.value = '已复制' }
  } catch (cause) { if (request === generation) error.value = cause instanceof Error ? cause.message : '未能复制，请手工选择目录文字复制。' }
  finally { if (request === generation) copying.value = false }
}
onMounted(() => { void check() })
onBeforeUnmount(() => { ++generation })
</script>

<template>
  <main class="collection-setup">
    <header class="setup-heading"><div><p class="eyebrow">Aden · 设置</p><h1>设置</h1><p class="lede">查看本机插件目录，在 Chrome 中手动加载。</p></div><RouterLink class="setup-back" to="/">返回</RouterLink></header>
    <section class="setup-panel" aria-labelledby="plugin-settings-heading">
      <div class="setup-row"><div><span class="setup-icon" aria-hidden="true">▦</span><h2 id="plugin-settings-heading">浏览器插件</h2><p>Aden 商品采集 · {{ status?.extensionVersion ? '版本 ' + status.extensionVersion : '版本尚未读取' }}</p></div><span class="setup-local">本机目录</span></div>
      <label class="setup-directory-label">插件目录<textarea class="setup-path" aria-label="插件目录" readonly :value="status?.extensionPath ?? ''" rows="2" placeholder="正在检测本机目录…"></textarea></label>
      <div class="setup-copy-row"><button class="setup-primary" :disabled="copying || checking || !status?.extensionReady" @click="copy">{{ copying ? '正在复制…' : '复制插件目录' }}</button><button :disabled="copying" @click="check">{{ checking ? '重新检测…' : '重新检测' }}</button><span v-if="message" role="status" class="setup-message">{{ message }}</span></div>
      <p v-if="error" class="setup-error" role="alert">{{ error }}</p><p v-else-if="status && !status.extensionReady" class="setup-error" role="alert">{{ status.problems[0] ?? '插件文件不可用，请重新检测；可手工选择目录文字复制。' }}</p>
      <small class="setup-time">{{ status?.checkedAt ? '最近检测：' + new Date(status.checkedAt).toLocaleString('zh-CN') : checking ? '正在检测…' : '尚未完成检测' }}</small>
      <div class="setup-instructions"><h3>在 Chrome 加载插件</h3><ol><li><strong>打开扩展管理页</strong><span>在 Chrome 地址栏输入 <code>chrome://extensions</code>，开启“开发者模式”。</span></li><li><strong>加载已解压的扩展</strong><span>点击“加载已解压的扩展”。</span></li><li><strong>选择上方目录</strong><span>在目录选择框中使用复制的完整路径，确认后固定 Aden 商品采集插件。</span></li></ol><p>登录 Aden 后在插件中连接当前账号，再提取、预览并确认保存商品。</p></div>
    </section>
  </main>
</template>

<style scoped>
.collection-setup{max-width:1080px;width:100%;margin:0 auto;display:grid;gap:25px}.setup-heading,.setup-row{display:flex;align-items:center;justify-content:space-between;gap:20px}.setup-heading h1{font-size:29px}.setup-back{color:#47725a;font-size:13px}.setup-panel{background:#fff;border:1px solid #dce6df;border-radius:16px;padding:29px;min-width:0}.setup-row>div{position:relative;padding-left:52px}.setup-icon{position:absolute;left:0;top:0;display:grid;place-items:center;width:38px;height:38px;background:#edf5ef;color:#3c7050;border-radius:10px;font-size:23px}.setup-panel h2{font-size:20px;margin-bottom:7px}.setup-panel p{line-height:1.7;color:#6d8072;font-size:13px}.setup-local{font-size:12px;color:#748e7b;padding:6px 9px;background:#f1f6f1;border-radius:6px}.setup-directory-label{margin-top:25px;font-size:12px;color:#6b8173}.setup-path{min-height:75px;resize:none;line-height:1.8;background:#f7f9f6;border:1px solid #dfe6df;color:#354d3d;font-family:Consolas,"Microsoft YaHei",monospace;overflow-wrap:anywhere;user-select:text}.setup-copy-row{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-top:17px}.setup-panel button{min-height:41px;padding:9px 15px;border:1px solid #bed0c1;border-radius:8px;background:#fff;color:#3a6c4d;cursor:pointer}.setup-panel button.setup-primary{background:#2f7655;color:#fff;border-color:#2f7655}.setup-message{font-size:13px;color:#347748}.setup-error{color:#9b4339!important;margin:12px 0 0;font-size:13px}.setup-time{display:block;color:#8a988e;margin-top:15px;font-size:11px}.setup-instructions{margin-top:27px;border-top:1px solid #e5ebe6;padding-top:23px}.setup-instructions h3{font-size:14px;color:#4a6552}.setup-instructions ol{padding-left:23px;margin-bottom:0}.setup-instructions li{padding:7px 0;color:#6e8375;font-size:13px}.setup-instructions strong{color:#506b59;display:block;margin-bottom:6px}.setup-instructions span{display:block;line-height:1.7}.setup-instructions>p{font-size:12px;margin:15px 0 0;color:#85958a}
</style>
