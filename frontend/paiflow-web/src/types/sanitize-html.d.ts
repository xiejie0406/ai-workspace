/** 当前已锁定sanitize-html 2.17.0的本项目实际调用形状。 */
declare module 'sanitize-html' {
  interface Options {
    allowedTags?: string[];
    allowedAttributes?: Record<string, string[]>;
    allowedSchemes?: string[];
    allowProtocolRelative?: boolean;
  }
  function sanitizeHtml(dirty: string, options?: Options): string;
  namespace sanitizeHtml {
    const defaults: { allowedTags: string[]; allowedAttributes: Record<string, string[]> };
  }
  export default sanitizeHtml;
}
