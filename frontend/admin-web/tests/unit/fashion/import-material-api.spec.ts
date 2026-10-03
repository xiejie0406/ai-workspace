import { beforeEach, describe, expect, it, vi } from 'vitest'

const { fashionRequestMock } = vi.hoisted(() => ({ fashionRequestMock: vi.fn() }))
vi.mock('@/api/fashion/client', () => ({ fashionRequest: fashionRequestMock, FASHION_API_PREFIX: '/fashion' }))

import { previewProductImport, productTemplateUrl, publishProductImport, getProductTemplate } from '@/api/fashion/importBatch'
import { confirmMaterials, previewMaterials, getProductImageContent, type ImageMapping } from '@/api/fashion/material'

describe('Fashion import and material APIs', () => {
  beforeEach(() => fashionRequestMock.mockReset())

  it('商品导入用 multipart 且发布携带乐观锁版本', async () => {
    fashionRequestMock.mockResolvedValue({ code: 200, data: {} })
    const file = new File(['x'], 'products.csv', { type: 'text/csv' })
    await previewProductImport({ file, sourceCode: 'MANUAL', asOf: '2026-09-13T00:00:00Z', clearFields: ['brand'] })
    const call = fashionRequestMock.mock.calls[0][0]
    expect(call.path).toBe('imports/products/preview')
    expect(call.body).toBeInstanceOf(FormData)
    expect(call.body.get('sourceCode')).toBe('MANUAL')
    expect(call.body.get('clearFields')).toBe('brand')
    await publishProductImport('91', 2)
    expect(fashionRequestMock).toHaveBeenLastCalledWith({
      path: 'imports/products/91/publish', method: 'post', body: { rowVersion: 2 }, timeoutMs: 120_000
    })
    expect(productTemplateUrl()).toMatch(/\/fashion\/imports\/products\/template$/)
  })

  it('图片仅发送文件和显式人工映射到 Java', async () => {
    fashionRequestMock.mockResolvedValue({ code: 200, data: {} })
    const file = new File(['image'], '000123__black.png', { type: 'image/png' })
    const mapping: ImageMapping = {
      filename: file.name, skuCode: '000123', usage: 'main', main: true, colorConfirmed: true,
      sourceType: 'jd_plugin', capturedAt: '2026-09-13T00:00:00Z', allowAi: true,
      allowProposal: true, allowEcommerce: false
    }
    await previewMaterials([file], 'MANUAL', '2026-09-13T00:00:00Z', [mapping])
    const call = fashionRequestMock.mock.calls[0][0]
    expect(call.path).toBe('materials/preview')
    expect(JSON.parse(call.body.get('mappings'))[0]).toMatchObject({ skuCode: '000123', colorConfirmed: true })
    await confirmMaterials('92', 1)
    expect(fashionRequestMock).toHaveBeenLastCalledWith({
      path: 'materials/92/confirm', method: 'post', body: { rowVersion: 1 }, timeoutMs: 120_000
    })
  })
  it('模板和预览使用同一选列范围，未选字段不发送到后台', async () => {
    fashionRequestMock.mockResolvedValue({ code: 200, data: {} })
    const selectedFields=['sourceCode','skuCode','name']
    await getProductTemplate(selectedFields)
    expect(fashionRequestMock).toHaveBeenLastCalledWith({path:'imports/products/template',params:{selectedFields:'sourceCode,skuCode,name'},responseType:'blob'})
    await previewProductImport({file:new File(['x'],'products.csv'),sourceCode:'MANUAL',asOf:'2026-10-01T00:00:00Z',selectedFields,mapping:{sourceCode:'来源',skuCode:'SKU',name:'名称'}})
    const body=fashionRequestMock.mock.calls[1][0].body as FormData
    expect(body.get('selectedFields')).toBe('sourceCode,skuCode,name')
    expect(JSON.parse(String(body.get('mapping')))).not.toHaveProperty('salePrice')
  })
  it('已审核图片从商品绑定的内容端点读取，不访问来源外链',async()=>{
    fashionRequestMock.mockResolvedValue(new Blob())
    await getProductImageContent('000123','img-1')
    expect(fashionRequestMock).toHaveBeenLastCalledWith({path:'products/000123/images/img-1/content',responseType:'blob'})
  })
})
