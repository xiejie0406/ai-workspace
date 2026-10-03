/** 原Hub首页和市场DTO；只补类型，不把目录响应视为已发布事实。 */
export interface BotType {
  typeKey: number;
  typeName: string;
  icon: string | null;
  typeNameEn: string | null;
}
export interface BotInfo {
  botId: number;
  chatId: number | null;
  botName: string;
  botType: number;
  botCoverUrl: string;
  prompt: string | null;
  botDesc: string;
  isFavorite: boolean;
  creator: string | null;
  version: number;
}
export interface SearchBotParam {
  type?: number;
  search?: string;
  pageSize?: number;
  page?: number;
}
export interface BotListPage {
  pageData: BotInfo[];
  totalCount: number;
  pageSize: number;
  page: number;
  totalPages: number;
}
export interface BotMarketParam {
  searchValue?: string;
  botId?: number;
  marketBotId?: number;
  uid?: number;
  botType?: number;
  botStatus?: number[];
  version?: number;
  status?: number;
  pageIndex?: number;
  pageSize?: number;
  showType?: number;
  official?: number;
  excludeBot?: number[];
  sort?: string;
}
// 原POST /bot/page缺本地Controller，不从相邻目录接口猜其响应信封。
export type BotMarketPage = unknown;
