import type { ReactElement } from 'react';
import { useEffect } from 'react';
import { handleLoginRedirect } from '@/utils/auth';

const CallbackPage = (): ReactElement => {
  useEffect(() => {
    // 旧Casdoor回调不能兑换成若依会话；SSO接入待明确协议后单独适配。
    handleLoginRedirect();
  }, []);

  return <></>;
};

export default CallbackPage;
