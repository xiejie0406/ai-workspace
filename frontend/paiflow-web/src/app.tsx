import { useCallback, useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { RouterProvider } from 'react-router-dom';
import router from '@/router';
import useUserStore, { UserState } from '@/store/user-store';
import { useEnterprise } from './hooks/use-enterprise';
import { useSpaceType } from './hooks/use-space-type';

export default function App(): ReactElement {
  const getUserInfo = useUserStore((state: UserState) => state.getUserInfo);
  const { getJoinedEnterpriseList, getEnterpriseSpaceCount, visitEnterprise } =
    useEnterprise();
  const { getLastVisitSpace, enterpriseId, switchToPersonal, isTeamSpace } =
    useSpaceType();
  const [initDone, setInitDone] = useState<boolean>(false);

  const initSpaceInfo = useCallback(async () => {
    try {
      const pathname = window.location.pathname.replace(/\/+$/, '');
      if (pathname === '/space' && isTeamSpace()) {
        switchToPersonal({ isJump: false });
        return;
      }

      if (!sessionStorage.getItem('lastVisitSpaceDone')) {
        await getLastVisitSpace();
        sessionStorage.setItem('lastVisitSpaceDone', 'true');
      }
    } finally {
      setInitDone(true);
    }
  }, [getLastVisitSpace, isTeamSpace, switchToPersonal]);

  useEffect(() => {
    const pathname = window.location.pathname.replace(/\/+$/, '');
    // 登录和回调页尚未建立平台会话，不发起业务身份/空间初始化请求。
    if (/^\/(login|callback)(\/|$)/.test(pathname)) return;
    getUserInfo();
    initSpaceInfo();
    getEnterpriseSpaceCount();
    getJoinedEnterpriseList();
  }, []);

  useEffect(() => {
    if (!initDone) return;
    getEnterpriseSpaceCount();
    visitEnterprise(enterpriseId);
  }, [enterpriseId, initDone]);

  return (
    <>
      <RouterProvider router={router} />
    </>
  );
}
