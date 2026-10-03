import { describe,expect,it } from 'vitest'
import { renderSafeMarkdown } from '@/views/paismart/composables/safeMarkdown'
function html(value:string){const node=document.createElement('div');node.innerHTML=renderSafeMarkdown(value);return node}
describe('安全 Markdown 与数学',()=>{
  it('保留表格、行内数学与展示公式',()=>{
    const node=html('| 项目 | 值 |\n| --- | --- |\n| 额度 | 10 |\n\n行内 $x^2+1$\n\n$$\n\\frac{1}{2}\n$$')
    expect(node.querySelectorAll('table')).toHaveLength(1);expect(node.querySelectorAll('.katex')).toHaveLength(2);expect(node.querySelector('.math-block')).not.toBeNull()
  })
  it('模型HTML、脚本链接、外部图像和公式HTML扩展不得生成活动内容',()=>{
    const node=html('<script>alert(1)</script>\n[坏链接](javascript:alert(1))\n![远程图](https://outside.example/track.png)\n$\\href{javascript:alert(1)}{x}$\n$\\htmlStyle{background:url(https://outside.example)}{x}$')
    expect(node.querySelector('script,iframe,img,object,embed')).toBeNull();expect([...node.querySelectorAll('a')].some(a=>a.href.startsWith('javascript:'))).toBe(false);expect(node.textContent).toContain('图片未自动加载')
  })
  it('正常链接具有独立窗口隔离属性',()=>{
    const node=html('[官方资料](https://example.org/doc)');const anchor=node.querySelector('a')!
    expect(anchor.getAttribute('target')).toBe('_blank');expect(anchor.getAttribute('rel')).toBe('noopener noreferrer')
  })
})
