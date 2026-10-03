/** 测试仅使用这些 Node 标准库；运行实现由当前 Node 提供。 */
declare module 'node:stream/web' { export const ReadableStream:typeof globalThis.ReadableStream }
declare module 'node:util' { export const TextEncoder:typeof globalThis.TextEncoder; export const TextDecoder:typeof globalThis.TextDecoder }
declare module 'node:fs' { export function readFileSync(path:string|URL,encoding:'utf8'):string }
