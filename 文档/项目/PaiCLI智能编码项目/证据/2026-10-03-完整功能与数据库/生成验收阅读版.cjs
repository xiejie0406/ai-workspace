// 阅读版由唯一验收正文生成，不在此脚本维护业务结果。
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { pathToFileURL } = require('node:url');
const base = __dirname;
const root = path.resolve(base, '../../../../..');
const MarkdownIt = require(path.join(root, 'frontend/admin-web/node_modules/markdown-it'));
const md = new MarkdownIt({ html: false, linkify: false });
const sourcePath = path.join(base, '完整业务验收记录.md');
const source = fs.readFileSync(sourcePath, 'utf8');
const sourceCapturedAt = new Date().toISOString();
const userAcceptance = (source.match(/^各模块用户接受：(待确认|接受|有条件接受|不接受)。/m) || [])[1];
if (!userAcceptance) throw new Error('唯一正文缺少各模块用户接受共同事实，不能代填接受状态');
const sourceUpdatedDate = (source.match(/更新时间：(\d{4}-\d{2}-\d{2})/) || [])[1] || '未记录';
const { parseResults, field } = require(path.join(root, '.runtime/paicli/full-acceptance-doc/execution-results.cjs'));
const execution = parseResults(source, base);
const carriedPageScenes = Number((source.match(/已有(\d+)项限定页面场景通过/) || [])[1] || 0);
const reportMode = process.argv.includes('--report') || process.argv.includes('--report-preview');
const reportPreview = process.argv.includes('--report-preview');
const moduleOption = process.argv.indexOf('--module');
const moduleNumber = moduleOption >= 0 ? Number(process.argv[moduleOption + 1]) : null;
const moduleMode = moduleOption >= 0;
if (moduleMode && (!Number.isInteger(moduleNumber) || moduleNumber < 2 || moduleNumber > 19)) throw new Error('模块编号须为正文第2～19章');
const statusByNumber = new Map(execution.cases.map(c => [c.number, c]));
const summaryPath = path.join(base, '验收文档检查.json');
const summary = JSON.parse(fs.readFileSync(summaryPath, 'utf8'));
const esc = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[c]));
const toc = [];
let hCount = 0;
const links = [];
const imageProof = new Map();
function actualProof(item) {
  if (!item.scenarios.length) return '<p class="current-position"><strong>当前完成位置：</strong>尚未登记本点实际业务执行；下列有顺序的步骤为待执行清单。</p>';
  const observations = item.scenarios.map(s => `<p><strong>${esc(s.name)}：</strong>${esc(field(s.text, '实际观察') || '尚无实际观察')}<br><strong>下一步条件：</strong>${esc(field(s.text, '恢复条件') || '按对应步骤继续核对必要结果与证据')}</p>`).join('');
  const images = item.scenarios.flatMap(s => s.screenshots.filter(x => x.local && x.exists).map(x => ({...x, scenario:s}))).filter((x,i,all) => all.findIndex(y=>y.href===x.href)===i);
  const figures = images.map(x => {
    const url = pathToFileURL(path.resolve(base, decodeURIComponent(x.href.split('#')[0]))).href;
    imageProof.set(url, true);
    const time = field(x.scenario.text,'时间');
    return `<figure class="actual-image"><a href="${esc(url)}"><img loading="lazy" src="${esc(url)}" alt="${esc(item.title)}：${esc(x.scenario.name)}的实际截图"></a><figcaption>对应“${esc(x.scenario.name)}”的当前页面证据；采集或沿用时间：${esc(time)}。${esc(field(x.scenario.text,'截图回读'))}。本图只证明画面中的状态，不能代替本点其余步骤。<a href="${esc(url)}">查看清晰原图</a></figcaption></figure>`;
  }).join('');
  return `<div class="current-position"><strong>当前完成位置与承接条件</strong>${observations}</div>${figures}`;
}
function render(text) {
  const tokens = md.parse(text, {});
  const linkedImages = new Map();
  for (let i=0;i<tokens.length;i++) {
    const t = tokens[i];
    if (t.type === 'heading_open') {
      const label = tokens[i+1]?.content || '';
      const id = `heading-${++hCount}`;
      t.attrSet('id',id);
      if(t.tag==='h2') toc.push({id,label});
    }
    for(const child of t.children || []) {
      if(child.type==='link_open') {
        const href=child.attrGet('href');
        if (moduleMode && href?.startsWith('#')) child.attrSet('href',pathToFileURL(sourcePath).href+href);
        if (!moduleMode && href?.startsWith('#') && decodeURIComponent(href) === '#paicli-全模块业务验收文档') child.attrSet('href','#heading-1');
        if(href && !/^(https?:|#|mailto:)/.test(href)) {
          const [rel,anchor] = href.split('#');
          const target=path.resolve(base,decodeURIComponent(rel));
          links.push({href,exists:fs.existsSync(target)});
          child.attrSet('href',pathToFileURL(target).href+(anchor?`#${anchor}`:''));
          const isAcceptanceImage = target.startsWith(path.join(base,'实际验收-20261004')+path.sep)
            || target.includes(path.sep+'FEAT-PAICLI-003-个人目录与简化使用'+path.sep+'证据'+path.sep);
          if (isAcceptanceImage && /\.(png|jpe?g|webp)$/i.test(target) && fs.existsSync(target)) {
            const label = t.children[t.children.indexOf(child)+1]?.content || '关联页面截图';
            linkedImages.set(href, {target,label});
          }
        }
      }
      if(child.type==='image') {
        const src = child.attrGet('src');
        if(src && !/^(https?:|data:)/i.test(src)) {
          const target = path.resolve(base,decodeURIComponent(src));
          links.push({href:src,exists:fs.existsSync(target)});
          child.attrSet('src',pathToFileURL(target).href);
          imageProof.set(pathToFileURL(target).href,true);
        }
      }
    }
  }
  const figures = [...linkedImages].filter(([, {target}]) => !imageProof.has(pathToFileURL(target).href)).map(([, {target,label}]) => {
    const url = pathToFileURL(target).href;
    imageProof.set(url,true);
    return `<figure class="actual-image"><a href="${esc(url)}"><img loading="lazy" src="${esc(url)}" alt="${esc(label)}"></a><figcaption>上文“${esc(label)}”关联的页面截图；按对应业务步骤的采集条件、实际结果和证明范围解读。<a href="${esc(url)}">查看清晰原图</a></figcaption></figure>`;
  }).join('');
  return md.renderer.render(tokens,md.options,{}) + figures;
}
const allChunks=source.split(/(?=^## )/m);
const chunks=moduleMode ? allChunks.filter(chunk => new RegExp(`^## ${moduleNumber}\\. `,'m').test(chunk)) : allChunks;
if (moduleMode && chunks.length !== 1) throw new Error('未找到唯一模块正文');
const selectedModule = moduleMode ? execution.modules.find(x => x.name === chunks[0].match(/^## (.*)/m)[1]) : null;
if (moduleMode && !selectedModule) throw new Error('模块业务结果未找到');
const visibleCounts = moduleMode ? selectedModule.counts : execution.counts;
const moduleName = moduleMode ? selectedModule.name.replace(/^\d+\. /,'') : null;
const visibleCaseNumbers = moduleMode ? [...chunks[0].matchAll(/^### (\d+)\. /gm)].map(x => Number(x[1])) : execution.cases.map(x => x.number);
const visibleScenarios = execution.cases.filter(x => visibleCaseNumbers.includes(x.number)).flatMap(x => x.scenarios).filter(x => x.declared !== '未执行').length;
let total=0;
const content=chunks.map(chunk => {
  const title=(chunk.match(/^## (.*)/m)||[])[1];
  if(!title) return `<div class="introduction">${render(chunk)}</div>`;
  const parts=chunk.split(/(?=^### \d+\. )/m);
  const head=render(parts.shift());
  const moduleResult = execution.modules.find(x => x.name === title);
  const individualReport = title ? path.join(base,'模块报告',title.replace(/^\d+\. /,''),'verification-report.html') : null;
  const individualLink = !moduleMode && moduleResult && fs.existsSync(individualReport)
    ? `<p><a href="${esc(pathToFileURL(individualReport).href)}">打开本模块独立验收报告</a></p>` : '';
  const moduleSummary = moduleResult ? `<p class="module-summary">${Object.entries(moduleResult.counts).map(([name,count])=>`${esc(name)} ${count}`).join(' · ')}；模块截图：${moduleResult.hasScreenshot ? '已登记，仍须逐点核对' : '尚未登记'}；完整结论：${moduleResult.complete ? '适用点证据条件满足，仍需人工复核' : '尚未全部完成'}</p>` : '';
  const cards=parts.map(part=> {
    const match=part.match(/^### (\d+)\. (.+)/m);
    if(!match) return render(part);
    total++;
    const name=match[2].replace(/[`*]/g,'');
    const body=part.replace(/^### .*\r?\n/,'');
    const actual = statusByNumber.get(Number(match[1]));
    const issues = [...actual.checks, ...actual.scenarios.flatMap(s => s.checks.map(x => `${s.name}：${x}`))];
    const warning = issues.length ? `<p class="result-warning">记录核对：${issues.map(esc).join('；')}。标记按证据条件汇总，不自动采信手填“通过”。</p>` : '';
    return `<details class="case" data-result="${esc(actual.result)}" data-search="${esc(part.toLowerCase().replace(/\s+/g,' '))}"><summary><span class="number">${esc(match[1])}</span><span>${esc(name)}</span><span class="label">${esc(actual.result)}</span></summary><div class="case-body">${warning}${actualProof(actual)}${render(body)}</div></details>`;
  }).join('');
  const annex=/完整追溯|已有实际记录/.test(title);
  return `<section class="module" data-module="${esc(title)}">${annex?`<details class="annex"><summary>${esc(title)} · 展开查看</summary><div>${head}${moduleSummary}${individualLink}${cards}</div></details>`:`${head}${moduleSummary}${individualLink}${cards}`}</section>`;
}).join('');
if(total!==visibleCaseNumbers.length || (!moduleMode && total!==summary['详细验收点数'])) throw new Error('正文与阅读版案例数不一致');
const acceptanceState = visibleCounts['受阻'] > 0 ? '验收受阻' : '验收准备';
const actualDescription = `${acceptanceState}。当前${moduleMode ? '本模块' : '完整'}业务点结果：通过${visibleCounts['通过']}，未通过${visibleCounts['未通过']}，受阻${visibleCounts['受阻']}，未执行${visibleCounts['未执行']}，不适用${visibleCounts['不适用']}；已登记${visibleScenarios}个前提核实或执行子场景。${moduleMode ? '本页由唯一正文对应模块生成，完整流程和未完成项全部保留；已有有效子场景按正文沿用，本次报告生成不计新执行业务。' : `另保留原${carriedPageScenes}项限定页面场景通过，按正文注明的范围沿用，不计本轮重新执行或整点通过。`}技术检查、业务结果及截图完整度分别记录；用户接受：${userAcceptance}。`;
const output=`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>PaiCLI 全模块业务验收文档</title><style>
:root{--bg:#f3f6fb;--paper:#fff;--text:#243247;--muted:#617089;--line:#dce4ef;--blue:#215fd3;--soft:#edf3ff}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.85 system-ui,"Microsoft YaHei",sans-serif}header{padding:35px max(24px,calc((100vw - 1380px)/2));background:#163254;color:#fff}header h1{margin:0 0 8px;font-size:30px}header p{margin:8px 0;max-width:1040px;color:#dce6f5}.stats{display:flex;flex-wrap:wrap;gap:10px;margin-top:18px}.stat{background:#ffffff12;border:1px solid #ffffff30;border-radius:9px;padding:7px 13px}.layout{max-width:1430px;margin:auto;display:grid;grid-template-columns:270px minmax(0,1fr);gap:25px;padding:24px}nav{position:sticky;top:18px;align-self:start;max-height:calc(100vh - 40px);overflow:auto;background:var(--paper);border:1px solid var(--line);border-radius:12px;padding:16px}nav a{display:block;padding:7px 5px;line-height:1.5;color:var(--muted);text-decoration:none;font-size:14px}nav a:hover{color:var(--blue);background:var(--soft)}main{min-width:0}.toolbar{position:sticky;top:0;z-index:2;background:var(--bg);padding:9px 0 14px;display:flex;gap:9px;align-items:center;flex-wrap:wrap}input{flex:1;min-width:200px;border:1px solid var(--line);border-radius:8px;padding:12px;font:inherit;color:inherit;background:var(--paper)}button{font:inherit;background:var(--paper);border:1px solid var(--line);border-radius:8px;padding:9px 12px;cursor:pointer;color:var(--text)}button:hover{border-color:var(--blue);color:var(--blue)}.module,.introduction{background:var(--paper);border:1px solid var(--line);border-radius:13px;padding:24px;margin-bottom:20px}.introduction>h1{font-size:26px}.module h2{font-size:23px;color:#15385c;margin:0 0 18px;scroll-margin-top:90px}h3{font-size:19px;margin-top:23px}p{margin:10px 0}li{margin:7px 0}a{color:var(--blue);overflow-wrap:anywhere}code{font:0.88em/1.6 ui-monospace,Consolas,monospace;overflow-wrap:anywhere;background:var(--soft);padding:2px 5px;border-radius:4px}pre{white-space:pre-wrap;background:var(--soft);padding:15px}blockquote{margin:10px 0;border-left:4px solid #84a6e6;padding:8px 16px;background:#f5f8fd;color:var(--muted)}.case{border:1px solid var(--line);border-radius:10px;margin:12px 0;background:var(--paper)}.case>summary{cursor:pointer;display:flex;align-items:center;gap:12px;padding:14px;font-weight:600;list-style:none}.case>summary:before{content:'▸';color:var(--blue)}.case[open]>summary:before{content:'▾'}.number{background:var(--soft);color:var(--blue);border-radius:6px;padding:1px 9px;flex:none}.label{font-size:12px;font-weight:400;margin-left:auto;white-space:nowrap;color:#705c18;background:#fbf5df;border-radius:5px;padding:1px 7px}.case-body{padding:0 23px 17px}.case-body ul{padding-left:22px}.case-body li>strong:first-child{color:#15385c}.annex>summary{cursor:pointer;font-size:20px;font-weight:600}.annex>div{padding-top:20px;overflow-x:auto}table{border-collapse:collapse;width:100%;font-size:14px;line-height:1.7;margin:18px 0;min-width:550px}th,td{padding:9px 11px;border:1px solid var(--line);text-align:left;vertical-align:top}th{background:var(--soft)}tr:nth-child(even){background:#fafcff}.module{overflow-x:auto}.hidden{display:none!important}.search-status{color:var(--muted);font-size:14px}footer{padding:24px;color:var(--muted);text-align:center;font-size:13px}.notice{font-size:14px;color:var(--muted)}@media(max-width:950px){.layout{grid-template-columns:1fr;padding:15px}nav{position:static;max-height:260px}.module,.introduction{padding:18px}header{padding:25px 20px}}@media(max-width:500px){header h1{font-size:24px}.label{display:none}.case>summary{padding:12px;font-size:15px}.case-body{padding:0 13px 15px}.toolbar{position:static}.layout{padding:10px}}@media print{header{background:white;color:black}header p{color:black}.layout{display:block}nav,.toolbar,.label{display:none}.case-body{display:block!important}.case,.module{break-inside:avoid}details:not([open])>*:not(summary){display:block!important}}
</style></head><body><header><h1>PaiCLI 全模块业务验收文档</h1><p>从个人编码入口到原生终端、工具、扩展和评测，逐项说明前提、操作、预期结果及取证方法。</p><p>这是验收准备文档。本轮产品业务执行为0；原受阻记录保留，正式评测继续暂停。</p><div class="stats"><span class="stat">${total} 个详细验收点</span><span class="stat">${summary['模块分组数']} 组能力与边界</span><span class="stat">32 类原能力 · 17 个工具</span><span class="stat">48 命令分派 · 130 配置入口</span><span class="stat">8 开发样本 · 28 正式蓝图</span></div></header><div class="layout"><nav aria-label="文档目录"><strong>按模块阅读</strong>${toc.map(x=>`<a href="#${x.id}">${esc(x.label)}</a>`).join('')}<a href="${pathToFileURL(sourcePath).href}">查看唯一 Markdown 正文</a></nav><main><div class="toolbar"><input id="search" type="search" placeholder="搜索验收点，例如：图片、恢复、MCP、重试" aria-label="搜索详细验收点"><button id="expand">展开全部</button><button id="collapse">收起全部</button><span class="search-status" id="status">共 ${total} 点</span></div><p class="notice">点击验收点标题查看操作。搜索匹配标题、步骤、预期及源码名称；追溯表和已有实际记录可单独展开。</p>${content}</main></div><footer>来源：完整业务验收记录.md · 更新日期2026-10-04 · 本页由正文生成，阅读检查不计作产品验收。</footer><script>
const cases=[...document.querySelectorAll('.case')];const input=document.querySelector('#search');const status=document.querySelector('#status');input.addEventListener('input',()=>{const q=input.value.trim().toLowerCase();let n=0;cases.forEach(el=>{const hit=!q||el.dataset.search.includes(q);el.classList.toggle('hidden',!hit);if(hit){n++;if(q)el.open=true;}});document.querySelectorAll('.module').forEach(el=>{const items=[...el.querySelectorAll('.case')];el.classList.toggle('hidden',items.length>0&&!items.some(x=>!x.classList.contains('hidden')));});status.textContent=q?'匹配 '+n+' / '+cases.length+' 点':'共 '+cases.length+' 点';});document.querySelector('#expand').addEventListener('click',()=>document.querySelectorAll('details').forEach(el=>el.open=true));document.querySelector('#collapse').addEventListener('click',()=>document.querySelectorAll('details').forEach(el=>el.open=false));
</script></body></html>`;
let rendered = output
  .replace('更新日期2026-10-04', `更新日期${esc(sourceUpdatedDate)}`)
  .replace('这是验收准备文档。本轮产品业务执行为0；原受阻记录保留，正式评测继续暂停。', esc(actualDescription))
  .replace('<h1>PaiCLI 全模块业务验收文档</h1>', `<h1>PaiCLI 全模块业务验收${reportMode ? '报告' : '文档'}</h1>`)
  .replace('<title>PaiCLI 全模块业务验收文档</title>', `<title>PaiCLI 全模块业务验收${reportMode ? '报告' : '文档'}</title>`)
  .replace('</style>', '.case[data-result="通过"] .label{color:#166534;background:#dcfce7}.case[data-result="未通过"] .label{color:#991b1b;background:#fee2e2}.case[data-result="受阻"] .label{color:#92400e;background:#fef3c7}.case[data-result="未执行"] .label{color:#475569;background:#e2e8f0}.result-warning{color:#92400e;background:#fffbeb;border-left:4px solid #d97706;padding:10px}.module-summary,.current-position{border:1px solid var(--line);background:var(--soft);padding:12px;margin-bottom:15px}.actual-image{margin:16px 0}.actual-image img,.module img{display:block;width:100%;height:auto;border:1px solid var(--line);border-radius:8px}.actual-image figcaption{font-size:13px;color:var(--muted);line-height:1.65}.case-body ol{padding-left:26px}</style>')
  .replace('<div class="stats">', `<div class="stats">${Object.entries(visibleCounts).map(([name,count]) => `<span class="stat">${esc(name)} ${count}</span>`).join('')}`);
if (moduleMode) rendered = rendered
  .replace(/PaiCLI 全模块业务验收(?:报告|文档)/g, `PaiCLI ${esc(moduleName)}验收报告`)
  .replace('从个人编码入口到原生终端、工具、扩展和评测，逐项说明前提、操作、预期结果及取证方法。', `本页完整覆盖唯一验收正文第${moduleNumber}章的${total}个业务点；按下面的实际结果与继续条件核对，不以子流程通过替代模块完整结论。`)
  .replace(/<span class="stat">\d+ 组能力与边界<\/span>/, '<span class="stat">1 个业务模块</span>')
  .replace(/<span class="stat">32 类原能力 · 17 个工具<\/span><span class="stat">48 命令分派 · 130 配置入口<\/span><span class="stat">8 开发样本 · 28 正式蓝图<\/span>/, '')
  .replace('<strong>按模块阅读</strong>', '<strong>本模块完整流程</strong>');
if (moduleMode) rendered = rendered.replace('<p class="notice">', '<p class="notice">报告页面与图片的视觉核对结果见唯一验收正文；生成成功不替代实际检查。</p><p class="notice">');
const destination = moduleMode ? path.join(base,'模块报告',moduleName,'verification-report.html') : reportPreview
  ? path.join(root,'.runtime/paicli/full-acceptance-doc/verification-report-preview.html')
  : path.join(base, reportMode ? 'verification-report.html' : '页面使用与业务验收逻辑.html');
if (moduleMode) fs.mkdirSync(path.dirname(destination),{recursive:true});
fs.writeFileSync(destination,rendered,'utf8');
const missing=links.filter(x=>!x.exists);
if (moduleMode) {
  summary['模块独立报告'] = {...(summary['模块独立报告'] || {}), [moduleName]: {
    path: path.relative(base,destination).replace(/\\/g,'/'), sourceChapter: moduleNumber,
    sourceSha256: crypto.createHash('sha256').update(source).digest('hex'),
    reportSha256: crypto.createHash('sha256').update(rendered).digest('hex'),
    pointNumbers: visibleCaseNumbers, counts: visibleCounts, executedScenarios: visibleScenarios,
    localReferences: links.length, missingLocalReferences: missing, uniqueImages: imageProof.size,
    generatedAt: new Date().toISOString(), visual: '未执行；生成和结构核对不代替浏览器渲染检查'
  }};
  fs.writeFileSync(summaryPath,JSON.stringify(summary,null,2)+'\n','utf8');
  console.log(JSON.stringify({module:moduleName,cases:total,links:links.length,missing,results:visibleCounts,output:destination}));
  if(missing.length) process.exitCode=1;
  return;
}
summary['阅读版点数']=total;
summary['阅读版相对链接数']=links.length;
summary['缺失相对链接']=missing;
summary['正文SHA256']=crypto.createHash('sha256').update(source).digest('hex');
summary[reportMode ? '业务报告正文SHA256' : '阅读版正文SHA256'] = summary['正文SHA256'];
summary[reportMode ? '业务报告SHA256' : '阅读版SHA256']=crypto.createHash('sha256').update(rendered).digest('hex');
summary['实际执行结果汇总'] = execution.counts;
summary['已登记执行子场景数'] = execution.executedScenarios;
summary['详细点实际结果'] = execution.cases.map(({text,...item}) => ({...item, scenarios:item.scenarios.map(({text,...s}) => s)}));
summary['模块实际结果'] = execution.modules;
summary['直接展示的不同实际截图数'] = imageProof.size;
summary['沿用原限定页面场景数'] = carriedPageScenes;
summary['逐步验收详细点数'] = (source.match(/^#### 逐步验收\s*$/gm) || []).length;
summary['逐步操作总数'] = source.split(/^#### 逐步验收\s*$/m).slice(1).reduce((sum,part) => sum + (part.split(/^#### 执行记录|^### |^## /m)[0].match(/^\d+\. /gm) || []).length, 0);
summary['模块完整流程数'] = (source.match(/^\*\*模块完整流程\*\*/gm) || []).length;
summary['生成时间'] = new Date().toISOString();
summary['本次快照静态核对'] = {
  frozenSourceSha256: summary['正文SHA256'], sourceCapturedAt, generatedAt: summary['生成时间'],
  points: total, businessModules: execution.modules.length,
  imageInstances: (rendered.match(/<img\b/g) || []).length, uniqueImages: imageProof.size,
  checkedLocalFiles: links.length, missingLocalFilesOrAnchors: missing,
  scope: '核对当前生成正文、图片与本地文件引用；引用数包含重复引用，本次未单独校验页内锚点',
  visual: '未执行，file协议受限；预览排队不表示已经看见渲染结果'
};
summary['范围'] = '完整流程验收准备、当前实际记录及适用旧证据沿用；不以技术检查代替业务结果';
summary['阅读版交互与视觉检查']={结果:'受阻',原因:'浏览器安全策略拒绝file://协议；未绕过策略。仅完成HTML生成、正文/案例数量、结构和相对文件链接检查，不声称实际浏览器布局或搜索交互通过。'};
if (!reportPreview) fs.writeFileSync(summaryPath,JSON.stringify(summary,null,2)+'\n','utf8');
console.log(JSON.stringify({cases:total,links:links.length,missing,results:execution.counts,executedScenarios:execution.executedScenarios,output:destination}));
if(missing.length) process.exitCode=1;
