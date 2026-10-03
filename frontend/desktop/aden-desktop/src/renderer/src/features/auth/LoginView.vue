<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '../../stores/session'

const session = useSessionStore()
const router = useRouter()
const visiblePassword = ref(false)
const loginDefaults = typeof __ADEN_TEST_LOGIN__ === 'undefined' ? null : __ADEN_TEST_LOGIN__
const form = reactive({
  username: loginDefaults?.username ?? '',
  password: loginDefaults?.password ?? '',
  code: ''
})
onMounted(() => { void session.loadCaptcha() })

async function submit(): Promise<void> {
  if (session.phase === 'loading') return
  try {
    await session.login({ username: form.username, password: form.password,
      ...(session.captcha?.enabled ? { code: form.code, uuid: session.captcha.uuid ?? undefined } : {}) })
    await router.replace('/home')
  } catch {
    form.code = ''
  }
}
</script>

<template>
  <main class="login-page"><section class="login-layout" aria-label="登录 Aden">
    <div class="login-introduction"><div class="login-brand"><span class="brand-mark">A</span><strong>Aden</strong></div><p class="login-overline">商品资料，井然有序</p><h1>把每次采集<br />变成可用的资料</h1><p>在浏览器确认商品内容，回到桌面核对、整理和导出。</p><div class="login-illustration" aria-hidden="true"><span class="illustration-label">商品资料</span><div class="illustration-row"><i></i><span><b>商品与所选规格</b><small>来源、参数和采集时间</small></span><em>已保存</em></div><div class="illustration-line"></div><div class="illustration-line short"></div><div class="illustration-images"><i></i><i></i><i></i></div><div class="illustration-footer">核对内容 <span>整理图片</span> 导出资料</div></div><small class="login-intro-footer">由你确认内容，保留每一次采集来源。</small></div>
    <div class="login-card"><p class="eyebrow">欢迎回来</p><h2>登录 Aden</h2><p class="lede">使用你的账号进入商品工作台。</p><form @submit.prevent="submit">
      <label>用户名<input v-model.trim="form.username" name="username" autocomplete="username" maxlength="64" placeholder="请输入用户名" required /></label>
      <label>密码<span class="password-field"><input v-model="form.password" name="password" :type="visiblePassword ? 'text' : 'password'" autocomplete="current-password" maxlength="256" placeholder="请输入密码" required /><button type="button" :aria-pressed="visiblePassword" :aria-label="visiblePassword ? '隐藏密码' : '显示密码'" @click="visiblePassword = !visiblePassword">{{ visiblePassword ? '隐藏' : '显示' }}</button></span></label>
      <label v-if="session.captcha?.enabled" class="captcha-row">验证码<span><input v-model.trim="form.code" name="captcha" autocomplete="off" maxlength="16" placeholder="请输入验证码" required /><button type="button" class="captcha-refresh" aria-label="换一张验证码" @click="session.loadCaptcha"><img :src="session.captcha.jpegDataUrl ?? ''" alt="登录验证码" /></button></span></label>
      <p v-if="session.error" class="error-banner" role="alert">{{ session.error }}</p><button class="primary-button login-submit" type="submit" :disabled="session.phase === 'loading'">{{ session.phase === 'loading' ? '正在登录…' : '登录' }}</button>
    </form><p class="login-form-footer">登录后直接进入工作台。如无法登录，请联系管理员。</p><RouterLink class="login-settings" to="/settings">插件目录与设置</RouterLink></div>
  </section></main>
</template>

<style scoped>
.login-layout{display:grid;grid-template-columns:1.05fr 1fr;width:min(1000px,100%);min-height:600px;border:1px solid #dce6df;border-radius:24px;overflow:hidden;background:#fff;box-shadow:0 25px 70px #193e2e17}.login-introduction{display:flex;flex-direction:column;padding:44px;background:linear-gradient(155deg,#183e2c,#2f6649);color:#eaf4ed}.login-brand{display:flex;gap:12px;align-items:center;font-size:25px}.login-overline{margin:50px 0 13px;color:#bbd8c6;font-size:13px;letter-spacing:.15em}.login-introduction h1{font-size:34px;line-height:1.45;letter-spacing:0;color:#fff;margin-bottom:15px}.login-introduction>p:not(.login-overline){color:#c5ddce;line-height:1.8;font-size:14px;max-width:310px}.login-intro-footer{margin-top:auto;padding-top:25px;color:#c0d6c7;font-size:12px}.login-card{width:auto;padding:72px 48px 45px;border:0;border-radius:0;box-shadow:none;display:flex;flex-direction:column;justify-content:center}.login-card h2{font-size:29px;color:#1b3e2c;margin:0 0 10px}.login-card .lede{font-size:14px}.login-card form{gap:19px;margin:31px 0 17px}.login-card label{font-size:13px;color:#445b4c}.login-card input{min-height:46px}.password-field{position:relative;display:block}.password-field input{padding-right:60px}.password-field button{position:absolute;right:9px;top:9px;padding:5px 7px;border:0;background:transparent;color:#50775e;font-size:12px;cursor:pointer}.login-submit{min-height:47px;font-weight:700}.login-form-footer{color:#849187;line-height:1.8;font-size:12px}.login-settings{align-self:flex-start;color:#47785a;font-size:12px;padding:5px 0}.captcha-refresh{padding:0;border:1px solid #cfd9d2;border-radius:9px;background:#fff;cursor:pointer}.captcha-refresh img{height:44px}.login-illustration{margin-top:22px;border:1px solid #ffffff38;border-radius:14px;padding:20px;background:#fff;color:#354c3e;transform:rotate(-2deg);box-shadow:0 8px 24px #11291d25}.illustration-label{font-size:11px;color:#7d9585;letter-spacing:.1em}.illustration-row{display:flex;gap:11px;align-items:center;margin:13px 0}.illustration-row>i{width:40px;height:43px;border-radius:8px;background:#e6eee6}.illustration-row b,.illustration-row small{display:block;font-size:12px}.illustration-row small{margin-top:6px;font-size:10px;color:#88988c}.illustration-row em{margin-left:auto;font-style:normal;font-size:10px;background:#e4f1e6;border-radius:5px;padding:5px;color:#497454}.illustration-line{height:5px;border-radius:5px;background:#e8eeea;margin:10px 0;width:92%}.illustration-line.short{width:62%}.illustration-images{display:flex;gap:7px;margin-top:16px}.illustration-images i{height:43px;flex:1;border-radius:6px;background:linear-gradient(130deg,#dde9df,#f2f6f1)}.illustration-footer{display:flex;justify-content:space-between;margin-top:18px;font-size:10px;color:#6f8677}@media(max-width:1100px){.login-layout{width:900px;min-height:560px}.login-introduction{padding:36px}.login-card{padding:50px 40px}.login-overline{margin-top:36px}.login-introduction h1{font-size:31px}}
</style>
