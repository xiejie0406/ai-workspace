import http from '@/utils/http';

export type NotificationType = 'PERSONAL' | 'BROADCAST' | 'SYSTEM' | 'PROMOTION';
export interface Notification {
  id: number;
  type: NotificationType;
  title: string;
  body: string;
  templateCode: string | null;
  payload: string | null;
  creatorUid: string;
  createdAt: string;
  expireAt: string | null;
  meta: string | null;
  isRead: boolean;
  readAt: string | null;
  receivedAt: string | null;
}
export interface NotificationResponse {
  notifications: Notification[];
  notificationsByType: Record<NotificationType, Notification[]>;
  pageIndex: number;
  pageSize: number;
  totalCount: number;
  totalPages: number;
  unreadCount: number;
}
export interface NotificationQuery {
  type?: string;
  unreadOnly?: boolean;
  pageIndex?: number;
  pageSize?: number;
}

// Axios拦截器只交付code=0的实际data，拒绝和网络错误由原统一错误入口处理。
export function getAllMessage(params: NotificationQuery): Promise<NotificationResponse> {
  return http.get<NotificationResponse, NotificationResponse>('/notifications/list', { params });
}
export function getUnreadMessageCount(): Promise<number> {
  return http.get<number, number>('/notifications/unread-count');
}
export function changeMessageStatus(request: {
  notificationIds: number[];
  markAll: boolean;
}): Promise<boolean> {
  return http.post<boolean, boolean>('/notifications/mark-read', request);
}
export function deleteMessage(id: number): Promise<boolean> {
  return http.delete<boolean, boolean>(`/notifications/${id}`);
}
