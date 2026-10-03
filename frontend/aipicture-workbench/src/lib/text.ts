/** 与唯一工具 schema / Java codePointCount 一致，不按 UTF-16 单元截断输入。 */
export function codePointLength(value: string): number {
  return Array.from(value).length
}
