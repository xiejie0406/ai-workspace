import { afterEach, describe, expect, it, vi } from 'vitest'
import { prepareImageFile } from '../image'

const source = (type = 'image/png', size = 20) => new File([new Uint8Array(size)],'截图.png',{ type,lastModified:100 })
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('图片提交前边界', () => {
  it('不支持格式和过大来源在解码前拒绝', async () => {
    const decode = vi.fn(); vi.stubGlobal('createImageBitmap',decode)
    await expect(prepareImageFile(source('image/svg+xml'))).rejects.toThrow('PNG / JPEG')
    const large = source(); Object.defineProperty(large,'size',{ value:50 * 1024 * 1024 + 1 })
    await expect(prepareImageFile(large)).rejects.toThrow('50MB'); expect(decode).not.toHaveBeenCalled()
  })
  it('小图保留原文件和尺寸，完成后释放解码位图', async () => {
    const bitmap = { width:1200,height:800,close:vi.fn() }, file = source()
    vi.stubGlobal('createImageBitmap',vi.fn().mockResolvedValue(bitmap))
    expect(await prepareImageFile(file)).toBe(file); expect(bitmap.close).toHaveBeenCalledOnce()
  })
  it('像素炸弹拒绝且释放位图', async () => {
    const bitmap = { width:10_000,height:10_000,close:vi.fn() }; vi.stubGlobal('createImageBitmap',vi.fn().mockResolvedValue(bitmap))
    await expect(prepareImageFile(source())).rejects.toThrow('4000 万像素'); expect(bitmap.close).toHaveBeenCalledOnce()
  })
  it('大 PNG 缩至 2000 长边并保留透明格式，不转 JPEG', async () => {
    const bitmap = { width:4000,height:2000,close:vi.fn() }, file = source('image/png',5 * 1024 * 1024 + 1)
    vi.stubGlobal('createImageBitmap',vi.fn().mockResolvedValue(bitmap))
    const clearRect = vi.fn(), drawImage = vi.fn(), canvas = { width:0,height:0,getContext:() => ({ clearRect,drawImage }),toBlob:vi.fn((callback:BlobCallback,type:string) => callback(new Blob(['compressed'],{ type }))) }
    vi.spyOn(document,'createElement').mockReturnValue(canvas as unknown as HTMLCanvasElement)
    const result = await prepareImageFile(file)
    expect(canvas.width).toBe(2000); expect(canvas.height).toBe(1000); expect(result.type).toBe('image/png'); expect(result.lastModified).toBe(100)
    expect(canvas.toBlob).toHaveBeenCalledWith(expect.any(Function),'image/png',undefined); expect(drawImage).toHaveBeenCalledWith(bitmap,0,0,2000,1000); expect(bitmap.close).toHaveBeenCalledOnce()
  })
  it('压缩始终超限时有限重试并保持未提交', async () => {
    const bitmap = { width:4000,height:2000,close:vi.fn() }; vi.stubGlobal('createImageBitmap',vi.fn().mockResolvedValue(bitmap))
    const canvas = { width:0,height:0,getContext:() => ({ clearRect:vi.fn(),drawImage:vi.fn() }),toBlob:vi.fn((callback:BlobCallback) => { const blob = new Blob(['large']); Object.defineProperty(blob,'size',{ value:6 * 1024 * 1024 }); callback(blob) }) }
    vi.spyOn(document,'createElement').mockReturnValue(canvas as unknown as HTMLCanvasElement)
    await expect(prepareImageFile(source('image/png',5 * 1024 * 1024 + 1))).rejects.toThrow('仍超过 5MB')
    expect(canvas.toBlob).toHaveBeenCalledTimes(6); expect(bitmap.close).toHaveBeenCalledOnce()
  })
})
