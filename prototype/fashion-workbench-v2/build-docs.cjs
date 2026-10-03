/* 从唯一 Markdown 事实源生成阅读版与分发副本；请勿手工维护生成内容。 */
'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const repositoryRoot = path.resolve(__dirname, '../..');
const documentRoot = path.join(repositoryRoot, '文档');
const destination = path.join(__dirname, 'documents');
const sourceCopyDirectory = path.join(destination, 'sources');

const documents = [
  {
    id: 'prd',
    title: '产品需求 PRD v1.4',
    source: '项目/智能选品项目/产品/产品需求文档.md',
  },
  {
    id: 'research',
    title: '市场产品调研',
    source: '项目/智能选品项目/调研/2026-09-11-专项市场调研.md',
  },
  {
    id: 'implementation',
    title: '原型历史设计与实施规格',
    source: '项目/智能选品项目/原型/服装工作台原型设计与生产任务转交.md',
  },
  {
    id: 'earlier-research',
    title: '前期调研记录',
    source: '项目/智能选品项目/调研/市场与竞品调研.md',
  },
];

function toPosix(value) {
  return value.replaceAll('\\', '/');
}

function pathKey(value) {
  return path.resolve(value).toLowerCase();
}

const documentBySource = new Map(
  documents.map((document) => [
    pathKey(path.join(documentRoot, ...document.source.split('/'))),
    document,
  ]),
);

function splitTarget(rawTarget) {
  const wrapped = rawTarget.startsWith('<') && rawTarget.endsWith('>');
  const value = wrapped ? rawTarget.slice(1, -1) : rawTarget;
  const tailIndex = value.search(/[?#]/);
  return {
    wrapped,
    pathname: tailIndex === -1 ? value : value.slice(0, tailIndex),
    tail: tailIndex === -1 ? '' : value.slice(tailIndex),
  };
}

function rewriteLocalTarget(rawTarget, sourceFile, outputFile, generatedExtension) {
  const parts = splitTarget(rawTarget);
  if (
    !parts.pathname ||
    /^(?:[a-z][a-z\d+.-]*:|#|\/|\\|[a-z]:[\\/])/i.test(parts.pathname)
  ) {
    return rawTarget;
  }

  let decodedPathname = parts.pathname;
  try {
    decodedPathname = decodeURIComponent(parts.pathname);
  } catch {
    // 保留格式异常的原始目标，避免猜测其语义。
  }

  const canonicalTarget = path.resolve(path.dirname(sourceFile), decodedPathname);
  const generatedDocument = documentBySource.get(pathKey(canonicalTarget));
  const outputTarget = generatedDocument
    ? path.join(
        generatedExtension === '.html' ? destination : sourceCopyDirectory,
        generatedDocument.id + generatedExtension,
      )
    : canonicalTarget;

  let rewritten = toPosix(path.relative(path.dirname(outputFile), outputTarget));
  if (!rewritten) rewritten = './';
  const value = rewritten + parts.tail;
  return parts.wrapped ? '<' + value + '>' : value;
}

const markdownLinkPattern =
  /(!?\[[^\]\r\n]*\]\()(<[^>\r\n]+>|[^)\s]+)((?:\s+["'][^"'\r\n]*["'])?\))/g;

function rebaseMarkdownLinks(source, sourceFile, outputFile) {
  return source.replace(markdownLinkPattern, (full, prefix, target, suffix) => {
    return prefix + rewriteLocalTarget(target, sourceFile, outputFile, '.md') + suffix;
  });
}

const htmlLinkPattern = /((?:href|src)\s*=\s*["'])([^"']+)(["'])/gi;

function rebaseHtmlLinks(source, sourceFile, outputFile) {
  return source.replace(htmlLinkPattern, (full, prefix, target, suffix) => {
    return prefix + rewriteLocalTarget(target, sourceFile, outputFile, '.html') + suffix;
  });
}

const style = `body{margin:0;color:#293c31;background:#f4f5ef;font:15px/1.9 "Microsoft YaHei",system-ui,sans-serif}nav{position:sticky;top:0;background:#fafcf6f5;border-bottom:1px solid #dce3d5;padding:17px 5%;display:flex;gap:24px;flex-wrap:wrap;backdrop-filter:blur(8px)}a{color:#365e43;text-decoration:none}a:hover{text-decoration:underline}nav a[aria-current]{font-weight:bold}main{max-width:1100px;padding:42px 55px;margin:30px auto;background:white;border:1px solid #dfe5d8;border-radius:14px}h1{font-size:30px;line-height:1.5}h2{font-size:23px;margin-top:42px;border-bottom:1px solid #e2e8dc;padding-bottom:12px}h3{font-size:18px;margin-top:28px}h4{font-size:16px}p{margin:14px 0}blockquote{border-left:3px solid #6b8a58;margin:20px 0;padding:8px 20px;background:#f4f7ef;color:#6b765f}table{border-collapse:collapse;width:100%;font-size:12px;margin:20px 0;display:block;overflow:auto}th,td{border:1px solid #dfe5d9;padding:10px 13px;min-width:100px;vertical-align:top}th{background:#eff4e9}tr:nth-child(even){background:#fafcf7}code{font-size:.9em;background:#f0f3ed;padding:2px 5px;border-radius:4px}pre{overflow:auto;padding:18px;background:#f1f4ec;border-radius:8px}pre code{padding:0}footer{font-size:11px;color:#86917d;margin-top:35px;border-top:1px solid #e1e7db;padding-top:20px;overflow-wrap:anywhere}button{border:1px solid #bacaae;color:#3a5f3a;border-radius:6px;background:white;padding:5px 14px;cursor:pointer}@media(max-width:700px){main{margin:12px;padding:24px 20px}nav{gap:12px;font-size:12px}h1{font-size:25px}}@media print{nav{display:none}body{background:white}main{padding:0;margin:0;max-width:none;border:0}h2,h3{break-after:avoid}tr{break-inside:avoid}table{display:table;font-size:10px}a{color:inherit}footer{display:none}}`;

(async () => {
  const { marked } = await import(
    'file:///C:/Users/admin/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/marked/lib/marked.esm.js'
  );

  fs.mkdirSync(sourceCopyDirectory, { recursive: true });
  const manifest = [];

  for (const document of documents) {
    const sourceFile = path.join(documentRoot, ...document.source.split('/'));
    const htmlFile = path.join(destination, document.id + '.html');
    const sourceCopyFile = path.join(sourceCopyDirectory, document.id + '.md');
    const source = fs.readFileSync(sourceFile, 'utf8');
    const sourceSha256 = crypto.createHash('sha256').update(source).digest('hex');

    const sourceCopy = rebaseMarkdownLinks(source, sourceFile, sourceCopyFile);
    let body = marked.parse(source);
    body = rebaseHtmlLinks(body, sourceFile, htmlFile);

    const navigation = documents
      .slice(0, 3)
      .map(
        ({ id, title }) =>
          `<a href="${id}.html" ${document.id === id ? 'aria-current="page"' : ''}>${title}</a>`,
      )
      .join('');
    const page = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>织选 · ${document.title}</title><style>${style}</style></head><body><nav><a href="../index.html">织选工作台 ↗</a>${navigation}<button onclick="print()">打印 / 保存 PDF</button></nav><main>${body}<footer>阅读版由唯一 Markdown 正文自动生成 · 2026-09-12 · <a href="sources/${document.id}.md">下载 Markdown 副本</a><br>SHA-256 ${sourceSha256}<br>状态与生产依赖以正文及交付验证记录为准。</footer></main></body></html>`;

    fs.writeFileSync(htmlFile, page, 'utf8');
    fs.writeFileSync(sourceCopyFile, sourceCopy, 'utf8');
    manifest.push({
      id: document.id,
      source: document.source,
      sha256: sourceSha256,
      copySha256: crypto.createHash('sha256').update(sourceCopy).digest('hex'),
    });
  }

  fs.writeFileSync(
    path.join(destination, 'source-manifest.json'),
    JSON.stringify(manifest, null, 2) + '\n',
    'utf8',
  );
  console.log('已从 4 份智能选品项目 Markdown 事实源生成阅读版、可用副本与哈希清单');
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
