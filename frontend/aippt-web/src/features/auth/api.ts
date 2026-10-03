import { platformRequest } from '@/api/client'
export interface UserPublic { id: string; email: string; name: string }
export interface PlatformIdentity { user: { userId: number; userName: string; nickName?: string; email?: string }; roles: string[]; permissions: string[] }
export function fetchCurrentUser() { return platformRequest<PlatformIdentity>('/getInfo') }
