import MarkdownIt from 'markdown-it'
import katex from 'katex'
import DOMPurify from 'dompurify'

const markdown = new MarkdownIt({ html: false, linkify: true, breaks: true, typographer: false })
markdown.validateLink = value => /^(https?:\/\/|mailto:|#|\/[^/])/i.test(value)
markdown.renderer.rules.image = (tokens, index) => markdown.utils.escapeHtml(`[图片未自动加载：${tokens[index]?.content || '图片'}]`)
markdown.inline.ruler.after('escape', 'math_inline', (state, silent) => {
  if (state.src[state.pos] !== '$' || state.src[state.pos + 1] === '$') return false
  const start = state.pos + 1
  let end = start
  while ((end = state.src.indexOf('$', end)) >= 0 && state.src[end - 1] === '\\') end++
  if (end < 0 || end === start || state.src.slice(start, end).includes('\n')) return false
  if (!silent) { const token = state.push('math_inline', 'math', 0); token.content = state.src.slice(start, end) }
  state.pos = end + 1; return true
})
markdown.block.ruler.before('fence', 'math_block', (state, startLine, endLine, silent) => {
  const first = state.src.slice(state.bMarks[startLine]! + state.tShift[startLine]!, state.eMarks[startLine]!).trim()
  if (!first.startsWith('$$')) return false
  if (silent) return true
  let line = startLine + 1, content = first.slice(2), closed = content.endsWith('$$')
  if (closed) content = content.slice(0, -2)
  while (!closed && line < endLine) {
    const next = state.src.slice(state.bMarks[line]! + state.tShift[line]!, state.eMarks[line]!).trim()
    line++
    if (next.endsWith('$$')) { content += `\n${next.slice(0, -2)}`; closed = true }
    else content += `\n${next}`
  }
  if (!closed) return false
  const token = state.push('math_block', 'math', 0); token.block = true; token.content = content; token.map = [startLine, line]
  state.line = line; return true
})
const mathHtml = (value: string, displayMode: boolean) => katex.renderToString(value, { displayMode, throwOnError: false, trust: false, strict: 'warn', output: 'html', maxExpand: 1000, maxSize: 10 })
markdown.renderer.rules.math_inline = (tokens, index) => mathHtml(tokens[index]!.content, false)
markdown.renderer.rules.math_block = (tokens, index) => `<div class="math-block">${mathHtml(tokens[index]!.content, true)}</div>`

/** 不信任模型输出；HTML先关闭，数学不授予trust，再清洗最终DOM。 */
export function renderSafeMarkdown(value: string): string {
  const sanitized = DOMPurify.sanitize(markdown.render(value), { FORBID_TAGS: ['img', 'iframe', 'object', 'embed', 'form', 'input', 'button', 'video', 'audio', 'script', 'style'], ADD_ATTR: ['target'] })
  const template = document.createElement('template'); template.innerHTML = sanitized
  for (const anchor of template.content.querySelectorAll('a')) {
    const href = anchor.getAttribute('href') || ''
    if (!markdown.validateLink(href)) { anchor.replaceWith(document.createTextNode(anchor.textContent || '')); continue }
    anchor.setAttribute('target', '_blank'); anchor.setAttribute('rel', 'noopener noreferrer')
  }
  return template.innerHTML
}
