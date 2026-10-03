import { useApis } from '@/host/HostContext'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { ACCEPTED_TYPES, MAX_UPLOAD_BYTES } from '@/api/assets'

const ASSETS_KEY = ['assets']

/** 前置校验类型与体积，避免明显不合规的文件白跑一次网络请求。 */
export function checkFile(file: File): string | null {

  if (!ACCEPTED_TYPES.includes(file.type)) return '仅支持 JPG、PNG 与 WebP'
  if (file.size > MAX_UPLOAD_BYTES) return '文件超过 20 MB 上限'
  return null
}

export function useAssets(limit?: number) {
  const { assets: assetsApi } = useApis()
  return useQuery({ queryKey: [...ASSETS_KEY, limit ?? 50], queryFn: () => assetsApi.list(limit) })
}

export function useAssetLibrary(limit?: number) {
  const { assets: assetsApi } = useApis()
  return useQuery({ queryKey: [...ASSETS_KEY, 'library', limit ?? 50], queryFn: () => assetsApi.library(limit) })
}

export function useUploadAsset() {
  const { assets: assetsApi } = useApis()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => assetsApi.upload(file),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ASSETS_KEY }),
  })
}
