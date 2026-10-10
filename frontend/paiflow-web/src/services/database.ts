import http from '@/utils/http';
import type { AxiosResponse } from 'axios';
import {
  PageData,
  DatabaseItem,
  DbPageListParams,
  CreateDbParams,
  DbDetailParams,
  UpdateDbParams,
  DeleteDbParams,
  CopyDbParams,
  CreateTableParams,
  TableField,
  TableListParams,
  DeleteTableParams,
  FieldListParams,
  UpdateTableParams,
  QueryTableDataParams,
  OperateTableDataParams,
  CopyTableParams,
  ImportDataParams,
  ExportDataParams,
  DownloadTableTemplateParams,
  ImportFieldDataParams,
  TableDataResponse,
} from '@/types/database';

// 查询数据库
export async function pageList(
  params: DbPageListParams
): Promise<PageData<DatabaseItem>> {
  return await http.post('/db/page-list', params);
}
// 创建数据库
export async function create(params: CreateDbParams): Promise<void> {
  await http.post('/db/create', params);
}

// 查询数据库详情
export async function dbDetail(params: DbDetailParams): Promise<DatabaseItem> {
  return await http.get('/db/detail', { params });
}

// 编辑数据库
export async function update(params: UpdateDbParams): Promise<void> {
  await http.post('/db/update', params);
}
// 删除数据库
export async function deleteDb(params: DeleteDbParams): Promise<void> {
  await http.get('/db/delete', { params });
}
// 复制数据库
export async function copyDb(params: CopyDbParams): Promise<void> {
  await http.get('/db/copy', { params });
}

// 创建表
export async function createTable(params: CreateTableParams): Promise<void> {
  await http.post('/db/create-table', params);
}
// 获取表列表
export async function tableList(
  params: TableListParams
): Promise<DatabaseItem[]> {
  return await http.get('/db/table-list', { params });
}
// 删除表
export async function deleteTable(params: DeleteTableParams): Promise<void> {
  await http.get('/db/delete-table', { params });
}
// 获取表字段
export async function fieldList(
  params: FieldListParams
): Promise<{ records: TableField[] }> {
  return await http.post('/db/table-field-list', params);
}
// 更新表
export async function updateTable(params: UpdateTableParams): Promise<void> {
  await http.post('/db/update-table', params);
}
// 查询表数据
export async function queryTableData(
  params: QueryTableDataParams
): Promise<TableDataResponse> {
  return await http.post('/db/select-table-data', params);
}
// 操作表数据
export async function operateTableData(
  params: OperateTableDataParams
): Promise<void> {
  await http.post('/db/operate-table-data', params);
}
// 复制表
export async function copyTable(params: CopyTableParams): Promise<void> {
  await http.get('/db/copy-table', { params });
}
// 导入数据
export async function importData(params: ImportDataParams): Promise<unknown> {
  const formData = new FormData();
  formData.append('tbId', params.tbId.toString());
  formData.append('execDev', params.execDev.toString());
  formData.append('file', params.file);

  return await http.post('/db/import-table-data', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}
// 导出数据
export async function exportData(
  params: ExportDataParams
): Promise<AxiosResponse<Blob>> {
  // 这个接口返回blob文件，需要特殊处理
  return await checkedDownload(await http.post('/db/export-table-data', params, {
    responseType: 'blob',
  }));
}
// 错误信封也可能以HTTP 200和Blob返回，不能让JSON变成用户下载的表格。
async function checkedDownload(response: AxiosResponse<Blob>): Promise<AxiosResponse<Blob>> {
  if (!(response?.data instanceof Blob) || response.data.size === 0) {
    throw new Error('数据下载返回了空文件或无效响应');
  }
  const blob = response.data;
  const contentType = String(response.headers?.['content-type'] || blob.type).toLowerCase();
  const prefix = await blob.slice(0, 512).text();
  if (contentType.includes('json') || /^\s*[\[{]/.test(prefix)) {
    let value: unknown;
    if (blob.size > 2 * 1024 * 1024) throw new Error('数据下载返回了无效错误响应');
    try { value = JSON.parse(await blob.text()); } catch { throw new Error('数据下载返回了无效错误响应'); }
    const error = value && typeof value === 'object' ? value as Record<string, unknown> : {};
    const message = [error.message, error.msg, error.desc].find(item => typeof item === 'string' && item.length > 0);
    throw new Error(typeof message === 'string' ? message : '数据下载失败');
  }
  if (contentType.includes('text/html') || /^\s*(?:<!doctype html|<html)/i.test(prefix)) throw new Error('数据下载返回了页面，未取得表格');
  return response;
}
// 下载表字段模板
export async function downloadFieldTemplate(): Promise<AxiosResponse<Blob>> {
  return await checkedDownload(await http.get('/db/field-template', { responseType: 'blob' }));
}
// 下载数据模板文件
export async function downloadTableTemplate(
  params: DownloadTableTemplateParams
): Promise<AxiosResponse<Blob>> {
  // 这个接口返回blob文件，需要特殊处理
  const response = await http.get('/db/table-template', {
    params,
    responseType: 'blob',
  });
  return await checkedDownload(response);
}
// 获取库表
export async function allTableList(): Promise<
  Array<{ value: string; label: string; children: unknown[] }>
> {
  return await http.get('/db/db_table-list');
}
// 导入表数据
export async function importFieldData(
  params: ImportFieldDataParams
): Promise<unknown> {
  const formData = new FormData();
  formData.append('file', params.file);

  return await http.post('/db/import-field-list', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}
// 禁止字段枚举
export async function getDisableFields(): Promise<{ value: string }> {
  return await http.get('/db/reserved-fields');
}
