/* 源码语法树核对，不运行应用、不发送网络请求。 */
const fs = require('node:fs');
const path = require('node:path');
const workspace = path.resolve(process.argv[2]);
const tsPath = process.argv[3] || path.join(workspace, 'frontend/paiflow-web/node_modules/typescript');
const ts = require(tsPath);
const sourceRoot = path.join(workspace, '参考项目/PaiFlow-main/console/frontend/src');
const output = process.argv[4];
const files = [];
function walk(dir) { for (const e of fs.readdirSync(dir, { withFileTypes: true })) { const p = path.join(dir, e.name); if (e.isDirectory()) walk(p); else if (/\.tsx?$/.test(e.name)) files.push(p); } }
walk(sourceRoot);
const calls = [], serviceExports = [], transports = [], parseDiagnostics = [];
const methods = new Set(['get','post','put','delete','patch','head','options']);
const wrappers = new Set(['getFixedUrl','getWsFixedUrl','getFixedUrlWithToken']);
function slim(text) { return text.length > 350 ? text.slice(0, 350) + '…' : text; }
for (const file of files.sort()) {
  const rel = path.relative(sourceRoot, file).replaceAll('\\','/');
  const text = fs.readFileSync(file, 'utf8');
  const sf = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
  const vars = new Map(), imports = new Map();
  function collect(n) {
    if (ts.isVariableDeclaration(n) && ts.isIdentifier(n.name) && n.initializer) { const a = vars.get(n.name.text) || []; a.push(n); vars.set(n.name.text, a); }
    if (ts.isImportDeclaration(n) && n.importClause) {
      const module = n.moduleSpecifier.text;
      if(n.importClause.name) imports.set(n.importClause.name.text, { module, imported:'default' });
      const named = n.importClause.namedBindings;
      if(named && ts.isNamedImports(named)) for(const e of named.elements) imports.set(e.name.text, {module, imported:e.propertyName?.text || e.name.text});
      if(named && ts.isNamespaceImport(named)) imports.set(named.name.text,{module, imported:'*'});
    }
    ts.forEachChild(n, collect);
  }
  collect(sf);
  const loc = n => sf.getLineAndCharacterOfPosition(n.getStart(sf)).line + 1;
  function ancestorName(n) { for(let p=n.parent;p;p=p.parent) { if(ts.isFunctionDeclaration(p)&&p.name)return p.name.text; if(ts.isVariableDeclaration(p)&&ts.isIdentifier(p.name)&&(ts.isArrowFunction(p.initializer)||ts.isFunctionExpression(p.initializer)))return p.name.text; if(ts.isMethodDeclaration(p))return p.name.getText(sf); } return null; }
  function isAncestor(a,b) { for(let p=b;p;p=p.parent) if(p===a)return true;return false; }
  function route(n, visited = new Set()) {
    if(!n) return { value:null, status:'missing' };
    if(ts.isStringLiteralLike(n))return {value:n.text,status:'literal'};
    if(ts.isTemplateExpression(n)) return { value:n.head.text + n.templateSpans.map(s => '${'+s.expression.getText(sf)+'}'+s.literal.text).join(''), status:'template' };
    if(ts.isParenthesizedExpression(n))return route(n.expression, visited);
    if(ts.isBinaryExpression(n)&&n.operatorToken.kind===ts.SyntaxKind.PlusToken) { const l=route(n.left,new Set(visited)),r=route(n.right,new Set(visited));return {value:(l.value||'${'+n.left.getText(sf)+'}')+(r.value||'${'+n.right.getText(sf)+'}'),status:'concatenated'}; }
    if(ts.isCallExpression(n)&&wrappers.has(n.expression.getText(sf)))return {...route(n.arguments[0],visited),wrapper:n.expression.getText(sf)};
    if(ts.isIdentifier(n) && !visited.has(n.text)) {
      visited.add(n.text); const declarations = vars.get(n.text)||[];
      const close = declarations.filter(d=>isAncestor(d.parent?.parent,n));
      const candidates = close.length ? close : declarations;
      if(candidates.length===1) return {...route(candidates[0].initializer,visited),viaVariable:n.text};
      return {value:null,status:candidates.length?'ambiguous_identifier':'parameter_or_import',expression:n.text,candidateLines:candidates.map(loc)};
    }
    return {value:null,status:'dynamic_expression',expression:slim(n.getText(sf))};
  }
  function props(n) { if(!n)return []; if(ts.isObjectLiteralExpression(n))return n.properties.map(p=>ts.isSpreadAssignment(p)?{name:'...',expression:slim(p.expression.getText(sf)),kind:'spread'}:{name:p.name?.getText(sf),kind:ts.SyntaxKind[p.kind],valueKind:p.initializer?ts.SyntaxKind[p.initializer.kind]:'Shorthand',valueExpression:p.initializer&&!ts.isLiteralExpression(p.initializer)?slim(p.initializer.getText(sf)):undefined});return [{name:null,kind:ts.SyntaxKind[n.kind],expression:slim(n.getText(sf))}]; }
  function option(n,key) { return n && ts.isObjectLiteralExpression(n) ? n.properties.find(p=>ts.isPropertyAssignment(p)&&p.name.getText(sf).replaceAll(/["']/g,'')===key)?.initializer : null; }
  function add(n,kind,method,url,body,config) { calls.push({id:rel+':'+loc(n)+':'+kind,file:rel,line:loc(n),function:ancestorName(n),kind,method,url:route(url),bodyFieldsOrExpression:props(body),queryFieldsOrExpression:props(option(config,'params')),optionsKeys:props(config).map(p=>p.name),callee:slim(n.expression.getText(sf))}); }
  function visit(n) {
    if(rel.startsWith('services/') && ts.isFunctionDeclaration(n) && n.name && n.modifiers?.some(m=>m.kind===ts.SyntaxKind.ExportKeyword)) serviceExports.push({file:rel,name:n.name.text,line:loc(n),parameters:n.parameters.map(p=>({name:p.name.getText(sf),type:p.type?.getText(sf)||'inferred/any'})),returnType:n.type?.getText(sf)||'inferred'});
    if(rel.startsWith('services/') && ts.isVariableStatement(n) && n.modifiers?.some(m=>m.kind===ts.SyntaxKind.ExportKeyword)) for(const d of n.declarationList.declarations) if(ts.isIdentifier(d.name)&&(ts.isArrowFunction(d.initializer)||ts.isFunctionExpression(d.initializer))) serviceExports.push({file:rel,name:d.name.text,line:loc(d),parameters:d.initializer.parameters.map(p=>({name:p.name.getText(sf),type:p.type?.getText(sf)||'inferred/any'})),returnType:d.initializer.type?.getText(sf)||'inferred'});
    if(ts.isCallExpression(n)) {
      const callee=n.expression.getText(sf);
      const root=ts.isPropertyAccessExpression(n.expression)&&ts.isIdentifier(n.expression.expression)?n.expression.expression.text:callee.split('.')[0].trim();
      const imported=imports.get(root);
      if(ts.isPropertyAccessExpression(n.expression)) {
        const verb=n.expression.name.text.toLowerCase(),receiver=n.expression.expression.getText(sf);
        if(methods.has(verb)&&(receiver==='http'||receiver==='axios'||receiver==='request'||receiver==='api'||receiver.includes('axios.create('))) { const cfg=['get','delete','head','options'].includes(verb)?n.arguments[1]:n.arguments[2];add(n,receiver+'.'+verb,verb.toUpperCase(),n.arguments[0],['get','delete','head','options'].includes(verb)?null:n.arguments[1],cfg); }
        else if(n.expression.name.text==='request'&&['http','axios','api','request'].includes(receiver)) {const cfg=n.arguments[0];add(n,callee,option(cfg,'method')?.text?.toUpperCase()||'DYNAMIC',option(cfg,'url'),option(cfg,'data'),cfg);}
        else if(n.expression.name.text==='open' && /xhr|request/i.test(receiver)) add(n,'XMLHttpRequest.open',n.arguments[0]?.text?.toUpperCase()||'DYNAMIC',n.arguments[1],null,null);
        else if(ts.isIdentifier(n.expression.expression) && imported && /casdoor|oauth|sdk/i.test(imported.module+' '+root) && !['parseAccessToken'].includes(n.expression.name.text)) transports.push({file:rel,line:loc(n),function:ancestorName(n),kind:'external_sdk',callee,import:imported,status:'sdk_transport_requires_protocol_review'});
        else if(n.expression.name.text==='open'&&receiver==='window') transports.push({file:rel,line:loc(n),function:ancestorName(n),kind:'browser_navigation_or_download',url:route(n.arguments[0]),status:'runtime_destination_requires_allowlist_and_artifact_review'});
      } else if(callee==='fetch'||callee==='fetchEventSource') {const cfg=n.arguments[1];add(n,callee,option(cfg,'method')?.text?.toUpperCase()||'GET',n.arguments[0],option(cfg,'body'),cfg);}
      else if(['http','api','axios','request'].includes(callee)) {
        const cfg=n.arguments[0];
        if(cfg&&ts.isObjectLiteralExpression(cfg))add(n,callee,option(cfg,'method')?.text?.toUpperCase()||'DYNAMIC',option(cfg,'url'),option(cfg,'data'),cfg);
        else add(n,callee,'DYNAMIC',null,cfg,null);
      }
      else if(callee==='downloadFileWithHeaders')add(n,callee,'GET',n.arguments[0],null,null);
    }
    if(ts.isNewExpression(n)&&( ['WebSocket','EventSource'].includes(n.expression.getText(sf)) || ts.isPropertyAccessExpression(n.expression)&&n.expression.name.text==='MozWebSocket')) {
      const kind=ts.isPropertyAccessExpression(n.expression)&&n.expression.name.text==='MozWebSocket'?'MozWebSocket':n.expression.getText(sf);
      calls.push({id:rel+':'+loc(n)+':'+kind,file:rel,line:loc(n),function:ancestorName(n),kind,method:kind==='EventSource'?'GET':'WEBSOCKET',url:route(n.arguments?.[0]),bodyFieldsOrExpression:[],queryFieldsOrExpression:[]});
    }
    if((ts.isJsxOpeningElement(n)||ts.isJsxSelfClosingElement(n))&&['iframe','audio','video','source'].includes(n.tagName.getText(sf))) {for(const a of n.attributes.properties)if(ts.isJsxAttribute(a)&&a.name.getText(sf)==='src')transports.push({file:rel,line:loc(a),function:ancestorName(n),kind:'browser_media_or_preview',tag:n.tagName.getText(sf),url:route(a.initializer&&ts.isJsxExpression(a.initializer)?a.initializer.expression:a.initializer),status:'artifact_or_external_url_not_controller_api'});}
    ts.forEachChild(n,visit);
  }
  visit(sf);
  for(const d of sf.parseDiagnostics)parseDiagnostics.push({file:rel,line:sf.getLineAndCharacterOfPosition(d.start||0).line+1,code:d.code,message:ts.flattenDiagnosticMessageText(d.messageText,' ')});
}
for(const f of serviceExports)f.callIds=calls.filter(c=>c.file===f.file&&c.function===f.name).map(c=>c.id);
fs.writeFileSync(output,JSON.stringify({typescriptVersion:ts.version,typescriptFiles:files.length,serviceExports,calls,additionalBrowserTransports:transports,parseDiagnostics},null,2));
console.log(JSON.stringify({files:files.length,exports:serviceExports.length,calls:calls.length,additionalBrowserTransports:transports.length,parseDiagnostics:parseDiagnostics.length}));
