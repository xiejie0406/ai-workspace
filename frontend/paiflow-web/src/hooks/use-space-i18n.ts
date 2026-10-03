import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { getMemberRoleOptions } from '@/constants/config';

/** 沿用已有角色和值及国际化字典，供原成员弹窗使用。 */
export function useSpaceI18n(): {
  memberRoleOptions: { value: number; label: string }[];
} {
  const { i18n } = useTranslation();
  const memberRoleOptions = useMemo(
    () => getMemberRoleOptions(i18n.language),
    [i18n.language]
  );
  return { memberRoleOptions };
}
