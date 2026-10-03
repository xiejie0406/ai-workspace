import http, { baseURL } from '@/utils/http';
import type { AxiosRequestConfig } from 'axios';

// 仍通过原请求实例，只标记若依平台自身的200响应格式。
export interface PlatformRequestConfig extends AxiosRequestConfig {
  platformResponse: true;
}

export function getPlatformBaseURL(): string {
  const configured = window.__APP_CONFIG__?.PLATFORM_BASE_URL;
  if (configured !== undefined) return configured;
  if (!baseURL.replace(/\/$/, '').endsWith('/paiflow/legacy')) {
    throw new Error('中心地址未采用/paiflow/legacy，请配置PLATFORM_BASE_URL');
  }
  return baseURL.replace(/\/$/, '').slice(0, -'/paiflow/legacy'.length) || '/';
}

function platformConfig(): PlatformRequestConfig {
  return { baseURL: getPlatformBaseURL(), platformResponse: true };
}

export interface CaptchaImage {
  captchaEnabled?: boolean;
  img?: string;
  uuid?: string;
}

export function getCaptchaImage(): Promise<CaptchaImage> {
  return http.get<unknown, CaptchaImage>('/captchaImage', platformConfig());
}

export function logoutPlatformSession(): Promise<unknown> {
  // 若依LogoutSuccessHandler负责删除Redis会话及记录退出日志。
  return http.post('/logout', undefined, platformConfig());
}
