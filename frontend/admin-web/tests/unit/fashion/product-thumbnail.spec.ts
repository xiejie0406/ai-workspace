import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
const api = vi.hoisted(() => ({ request: vi.fn() }))
vi.mock('@/api/fashion/client', () => ({ fashionRequest: api.request }))
import ProductThumbnail from '@/views/fashion/components/ProductThumbnail.vue'

describe('审核商品缩略图', () => {
  let wrapper: VueWrapper, intersect: IntersectionObserverCallback
  const disconnect = vi.fn(), revoke = vi.fn(), create = vi.fn(() => 'blob:private-image')
  beforeEach(() => {
    api.request.mockReset(); disconnect.mockReset(); revoke.mockReset(); create.mockClear()
    vi.stubGlobal('IntersectionObserver', class { constructor(callback: IntersectionObserverCallback) { intersect = callback } observe() {} disconnect = disconnect })
    vi.stubGlobal('URL', { createObjectURL: create, revokeObjectURL: revoke })
  })
  afterEach(() => { wrapper?.unmount(); vi.unstubAllGlobals() })
  const visible = () => intersect([{ isIntersecting: true }] as IntersectionObserverEntry[], {} as IntersectionObserver)
  it('进入可见区域才访问现有认证接口，销毁时释放对象URL', async () => {
    api.request.mockResolvedValue(new Blob(['image'], { type: 'image/png' }))
    wrapper = mount(ProductThumbnail, { props: { productId: '42' } }); expect(api.request).not.toHaveBeenCalled()
    visible(); await flushPromises()
    expect(api.request).toHaveBeenCalledWith(expect.objectContaining({ path: 'products/42/main-image', responseType: 'blob', signal: expect.any(AbortSignal) }))
    expect(create).toHaveBeenCalledOnce(); wrapper.unmount(); expect(revoke).toHaveBeenCalledWith('blob:private-image')
  })
  it('无内部许可不发请求，服务端403明确显示无权限', async () => {
    wrapper = mount(ProductThumbnail, { props: { productId: '42', canView: false } }); visible(); await flushPromises()
    expect(wrapper.text()).toContain('无内部查看许可'); expect(api.request).not.toHaveBeenCalled()
    api.request.mockRejectedValueOnce({ status: 403 }); await wrapper.setProps({ canView: true }); await flushPromises()
    expect(wrapper.text()).toContain('无查看权限')
  })
  it('较早商品图片回包不能覆盖新商品，JSON错误不会作为图片', async () => {
    let finish!: (value: Blob) => void
    api.request.mockReturnValueOnce(new Promise<Blob>(resolve => { finish = resolve })).mockResolvedValueOnce(new Blob(['{"code":500}'], { type: 'application/json' }))
    wrapper = mount(ProductThumbnail, { props: { productId: 'old' } }); visible()
    await wrapper.setProps({ productId: 'new' }); await flushPromises()
    finish(new Blob(['old-image'], { type: 'image/png' })); await flushPromises()
    expect(create).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('图片内容不可用')
  })
})
