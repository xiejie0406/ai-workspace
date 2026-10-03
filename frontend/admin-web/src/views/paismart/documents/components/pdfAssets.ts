// PDF.js 的中文 CMap、字体和解码资源由当前构建提供，不访问外部 CDN。
const assets = import.meta.glob<string>('/node_modules/pdfjs-dist/{cmaps,standard_fonts,wasm}/*', { query: '?url', import: 'default', eager: true })
export class LocalPdfBinaryDataFactory {
  async fetch({kind,filename}:{kind:string;filename:string}):Promise<Uint8Array> {
    const folder=({cMapUrl:'cmaps',standardFontDataUrl:'standard_fonts',wasmUrl:'wasm'} as Record<string,string>)[kind]
    if(!folder||!filename||/[\\/]/.test(filename))throw new Error('PDF 内置资源请求不在允许范围')
    const asset=assets[`/node_modules/pdfjs-dist/${folder}/${filename}`]
    if(!asset)throw new Error(`PDF 内置资源未打包：${filename}`)
    const response=await fetch(asset,{credentials:'omit'})
    if(!response.ok)throw new Error(`PDF 内置资源读取失败：${filename}`)
    return new Uint8Array(await response.arrayBuffer())
  }
}
