import { useCallback, useState } from 'react';
import http from '@/utils/http';

interface LoginState {
  loading: boolean;
  error: string | null;
}

interface TokenStorage {
  getAccessToken: () => string | null;
  getRefreshToken: () => string | null;
  setTokens: (tokens: { accessToken: string; refreshToken?: string }) => void;
  clearTokens: () => void;
  isAccessTokenExpired: () => boolean;
  isRefreshTokenExpired: () => boolean;
}

// 简化的 token 存储管理
export const tokenStorage: TokenStorage = {
  getAccessToken: () => localStorage.getItem('accessToken'),
  getRefreshToken: () => localStorage.getItem('refreshToken'),

  setTokens: tokens => {
    localStorage.setItem('accessToken', tokens.accessToken);
    // 平台不签发refreshToken，清除旧会话遗留值。
    localStorage.removeItem('refreshToken');
  },

  clearTokens: () => {
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
  },

  isAccessTokenExpired: () => {
    const token = localStorage.getItem('accessToken');
    if (!token) return true;

    // 不以JWT的exp推断Redis会话；最终有效性由中心请求校验。
    return false;
  },

  isRefreshTokenExpired: () => {
    const token = localStorage.getItem('refreshToken');
    if (!token) return true;

    try {
      const payload = JSON.parse(window.atob(token.split('.')[1] || ''));
      return Date.now() >= payload.exp * 1000;
    } catch {
      return true;
    }
  },
};

const useLogin = (): {
  loading: boolean;
  error: string | null;
  refreshToken: () => Promise<boolean>;
  tokenStorage: TokenStorage;
} => {
  const [state, setState] = useState<LoginState>({
    loading: false,
    error: null,
  });

  const setLoading = (loading: boolean): void => {
    setState(prev => ({ ...prev, loading }));
  };

  const setError = (error: string | null): void => {
    setState(prev => ({ ...prev, error }));
  };

  // 刷新 token
  const refreshToken = useCallback(async (): Promise<boolean> => {
    if (!tokenStorage.getAccessToken()) return false;
    try {
      // 复用若依会话查询及自动续期，无独立刷新端点或第二种令牌。
      await http.get('/api/session');
      return true;
    } catch (error: unknown) {
      const code = (error as { code?: number })?.code;
      if (code === 401 || code === 101 || code === 80000 || code === 90000) {
        tokenStorage.clearTokens();
      }
      return false;
    }
  }, []);

  return {
    ...state,
    refreshToken,
    tokenStorage,
  };
};

export default useLogin;
