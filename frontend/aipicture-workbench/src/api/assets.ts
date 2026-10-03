import type { ApiClient } from '@/api/client'

export type AssetKind =
  | 'original'
  | 'generated'
  | 'subject'
  | 'background'
  | 'mask'
  | 'marketing'
  | 'export'

export type Asset = {
  id: string
  kind: AssetKind
  source: 'upload' | 'generate' | 'tool'
  image_format: string
  width: number
  height: number
  size_bytes: number
  has_alpha: boolean
  created_at: string
  url: string
}

export const MAX_UPLOAD_BYTES = 20 * 1024 * 1024
export const ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/webp']

export type LibraryGroup = {
  session_id: string | null
  title: string
  updated_at: string
  cover: Asset
  assets: Asset[]
}

export const createAssetsApi = (api: ApiClient) => ({
  upload: (file: File) => api.upload<Asset>(file),
  list: (limit = 50) => api.get<Asset[]>(`/assets?pageNum=1&pageSize=${limit}`),
  library: (limit = 50) => api.get<LibraryGroup[]>(`/assets/library?limit=${limit}`),
  download: (id: string) => api.blob(`/assets/${encodeURIComponent(id)}/download`),
})
