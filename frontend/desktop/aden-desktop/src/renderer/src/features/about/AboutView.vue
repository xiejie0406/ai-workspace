<script setup lang="ts">
const runtime = window.adenDesktop.getRuntimeInfo()
</script>

<template>
  <div class="about-page">
    <header class="about-hero">
      <p class="eyebrow">关于 Aden</p>
      <h1>把商品信息保存下来，方便核对、整理和导出</h1>
      <p class="lede">Aden 是桌面工作台。你在 Chrome 自己找到商品并打开详情页，在插件分区提取、核对预览并确认保存；商品信息、已存图片和来源记录可在桌面回看。</p>
      <div class="about-actions"><RouterLink class="about-primary" to="/settings">查看采集步骤</RouterLink><RouterLink class="about-secondary" to="/collection">打开商品库</RouterLink></div>
    </header>

    <section class="about-card" aria-labelledby="about-steps-title">
      <p class="eyebrow">怎么使用</p><h2 id="about-steps-title">三步完成一次商品整理</h2>
      <ol class="about-steps"><li><strong>连接 Chrome 扩展</strong><span>按安装指引启用扩展，并确认 Aden 已登录。</span></li><li><strong>打开商品详情并采集</strong><span>自己搜索、选好规格，分区提取并核对内容后点击“确认保存”。</span></li><li><strong>核对、取舍、导出</strong><span>在商品库查看字段和图片、添加备注，选出需要的商品并导出。</span></li></ol>
    </section>

    <section class="about-card" aria-labelledby="about-files-title">
      <p class="eyebrow">你会拿到什么</p><h2 id="about-files-title">可回看的商品记录和文件</h2>
      <div class="about-file-grid"><article><strong>商品库</strong><p>查看标题、价格原文、规格、来源、采集时间、内容快照和已保存的图片；部分缺失会单独标注。</p></article><article><strong>Excel 表格</strong><p>导出选中的商品信息，便于筛选和继续整理。</p></article><article><strong>图片包</strong><p>选择包含图片的导出方式时，打包已经保存成功的图片；未保存的图片不会被写成成功。</p></article></div>
    </section>

    <section class="about-card" aria-labelledby="about-scope-title">
      <p class="eyebrow">当前能力</p><h2 id="about-scope-title">使用范围</h2>
      <ul class="about-scope"><li>商品采集由你在详情页主动发起；搜索和选择商品仍由你操作。</li><li>真实京东页面的适配及完整用户验收仍在进行，测试结果请以实际页面和安装版为准。</li><li>采购询价与微信客服尚未作为本次商品采集流程开放。</li></ul>
    </section>

    <details class="about-diagnostics"><summary>版本与技术诊断信息</summary><dl class="detail-list"><div><dt>桌面版本</dt><dd>{{ runtime.appVersion ?? '版本未读取' }}</dd></div><div><dt>运行时</dt><dd>Electron {{ runtime.versions.electron }} / Chromium {{ runtime.versions.chrome }}</dd></div><div><dt>平台</dt><dd>{{ runtime.platform }}</dd></div><div><dt>控制面</dt><dd>RuoYi；桌面端按当前账号获授范围访问。</dd></div></dl></details>
  </div>
</template>

<style scoped>
.about-page{display:grid;gap:22px;max-width:1120px}.about-hero,.about-card,.about-diagnostics{padding:28px 32px;border:1px solid #dce5de;border-radius:16px;background:#fff}.about-hero{padding:38px;background:linear-gradient(135deg,#fff,#eef6ef)}.about-hero h1{max-width:760px;color:#193f2d;line-height:1.3}.about-hero .lede{max-width:770px}.about-actions{display:flex;flex-wrap:wrap;gap:12px;margin-top:24px}.about-actions a{display:inline-flex;padding:11px 16px;border-radius:9px;font-weight:700}.about-primary{color:#fff;background:#2e7653}.about-secondary{border:1px solid #a9c8b4;color:#2b6b4a;background:#fff}.about-steps{display:grid;gap:10px;margin:15px 0 0;padding:0;list-style:none;counter-reset:steps}.about-steps li{display:grid;grid-template-columns:170px 1fr;gap:16px;padding:16px;border:1px solid #e1e9e2;border-radius:10px;background:#f9fbf9;counter-increment:steps}.about-steps li strong::before{content:counter(steps,decimal-leading-zero) ' ';color:#2e7954}.about-steps li span,.about-file-grid p,.about-scope li{color:#617467;line-height:1.7}.about-file-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.about-file-grid article{padding:17px;border:1px solid #e1e9e2;border-radius:10px}.about-file-grid p{margin:8px 0 0;font-size:13px}.about-scope{display:grid;gap:9px;margin-bottom:0;padding-left:20px}.about-diagnostics{padding:18px 28px}.about-diagnostics summary{cursor:pointer;color:#375944;font-weight:700}.about-diagnostics .detail-list{margin-bottom:0}@media(max-width:1100px){.about-file-grid{grid-template-columns:1fr}.about-steps li{grid-template-columns:1fr;gap:7px}}
</style>
