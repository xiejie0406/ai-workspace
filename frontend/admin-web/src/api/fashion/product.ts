import { fashionRequest, type FashionQuery } from './client'
import type { FashionProduct, ProductStatus, RuoYiResult } from './types'

export interface ProductQuery extends FashionQuery {
  attributeKey?: string
  attributeValue?: string
  sourceCode?: string
  categoryCode?: string
  status?: ProductStatus | ''
  keyword?: string
  page: number
  pageSize: number
}

export interface ProductPage {
  items: FashionProduct[]
  total: number
  page: number
  pageSize: number
}

export interface ProductCreateBody {
  attributes?: Record<string, string>
  sourceCode: string
  skuCode: string
  styleCode: string
  name: string
  categoryCode: string
  colorCode: string
  colorName: string
  sizeCode: string
  sizeSystem: string
  unit: string
  brand?: string
  material?: string
  season: string
  jdItemId?: string
  jdUrl?: string
}

export interface ProductPatch {
  id: string
  rowVersion: number
  changes: Record<string, string | boolean | null | string[] | Record<string, string>>
}

export function listProducts(params: ProductQuery, signal?: AbortSignal) {
  return fashionRequest<RuoYiResult<ProductPage>>({ path: 'products', params, signal })
}

export function getProduct(id: string) {
  return fashionRequest<RuoYiResult<FashionProduct>>({ path: `products/${id}` })
}

export function createProduct(body: ProductCreateBody) {
  return fashionRequest<RuoYiResult<FashionProduct>, ProductCreateBody>({
    path: 'products', method: 'post', body
  })
}

export function updateProducts(products: ProductPatch[]) {
  return fashionRequest<RuoYiResult<FashionProduct[]>, { products: ProductPatch[] }>({
    path: 'products/batch', method: 'put', body: { products }
  })
}

export function updateProductStatus(id: string, status: ProductStatus, rowVersion: number) {
  return fashionRequest<RuoYiResult<FashionProduct>, { status: ProductStatus; rowVersion: number }>({
    path: `products/${id}/status`, method: 'put', body: { status, rowVersion }
  })
}
