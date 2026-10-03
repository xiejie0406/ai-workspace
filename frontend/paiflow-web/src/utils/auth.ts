import { logoutPlatformSession } from '@/services/platform-auth';
import { message } from 'antd';

export function clearPlatformTokens(): void {
  localStorage.removeItem('accessToken');
  localStorage.removeItem('refreshToken');
}

export function getPostLoginRedirect(): string {
  const redirect = sessionStorage.getItem('postLoginRedirect');
  sessionStorage.removeItem('postLoginRedirect');
  if (!redirect?.startsWith('/') || redirect.startsWith('//')) return '/home';
  try {
    // URL解析会归一化反斜线、控制字符及点路径，按浏览器最终目标核对。
    const target = new URL(redirect, window.location.origin);
    if (target.origin !== window.location.origin ||
      /^\/(login|callback)(\/|$)/.test(target.pathname)) return '/home';
    return target.pathname + target.search + target.hash;
  } catch {
    return '/home';
  }
}

export function handleLoginRedirect(): void {
  if (!/^\/(login|callback)(\/|$)/.test(window.location.pathname)) {
    sessionStorage.setItem('postLoginRedirect', window.location.pathname + window.location.search);
  }
  clearPlatformTokens();
  window.location.href = '/login';
}

export async function handleLogout(): Promise<void> {
  try {
    await logoutPlatformSession();
  } catch {
    // 网络失败时保留凭据以便重试，不能把本地清除当作服务端会话已退出。
    message.error('退出未完成，请检查连接后重试');
    return;
  }
  clearPlatformTokens();
  sessionStorage.removeItem('postLoginRedirect');
  window.location.href = '/login';
}
