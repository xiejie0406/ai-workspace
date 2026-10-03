"""原React生产打包技术检查；独立新输出，保护既有dist，0服务/外部调用。"""
from pathlib import Path
from datetime import datetime,timezone
import hashlib,json,os,subprocess,sys
ROOT=Path(__file__).resolve().parents[6];FRONT=ROOT/'frontend/paiflow-web';EV=ROOT/'文档/项目/PaiFlow工作流项目/证据/本地源码检查'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,d):p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def sources():
    files=[p for d in ('src','public','tests') for p in (FRONT/d).rglob('*') if p.is_file()]
    files.extend(p for p in FRONT.iterdir() if p.is_file() and (p.name.startswith('.env') or p.name in {'package.json','package-lock.json','vite.config.js','tsconfig.json','tsconfig.node.json','postcss.config.js','tailwind.config.js','index.html'}))
    return sorted(set(files))
def main():
    prefix='调试接续原React生产构建';out=EV/'checks/调试接续原React生产构建产物';log=EV/(prefix+'.log');result=EV/(prefix+'结果.json');inputs=EV/(prefix+'输入.json');execution=EV/(prefix+'执行.json')
    for p in (out,log,result,inputs,execution):assert not p.exists(),f'不能覆盖原内容 {p}'
    gate=json.loads((EV/'checks/原React调试接续冻结输入.json').read_text('utf-8-sig'))
    for item in gate['files']:assert sha(ROOT/item['path'])==item['sha256'],f'冻结前端变化 {item["path"]}'
    before={p.relative_to(ROOT).as_posix():sha(p) for p in sources()};save(inputs,{'files':before,'sourceInputCount':len(before),'installedOriginalDependenciesOnly':True,'noDependencyInstall':True})
    command=[r'C:\Program Files\nodejs\npm.cmd','run','build','--','--outDir',str(out),'--emptyOutDir','false'];env=dict(os.environ);env['PATH']=r'C:\Program Files\nodejs;'+env.get('PATH','')
    start=datetime.now(timezone.utc).isoformat();save(execution,{'status':'running','startedAt':start,'command':command,'servicesStarted':False});print(json.dumps({'status':'running','sourceInputCount':len(before),'outputDir':str(out)},ensure_ascii=False),flush=True)
    with log.open('wb') as stream:proc=subprocess.run(command,cwd=FRONT,env=env,stdout=stream,stderr=subprocess.STDOUT)
    end=datetime.now(timezone.utc).isoformat();after={p.relative_to(ROOT).as_posix():sha(p) for p in sources()};changes=[k for k in sorted(set(before)|set(after)) if before.get(k)!=after.get(k)];outputs={p.relative_to(out).as_posix():sha(p) for p in out.rglob('*') if p.is_file()} if out.exists() else {};success=proc.returncode==0 and not changes and 'index.html' in outputs
    value={'status':'technical_checks_passed' if success else 'technical_checks_not_passed','exitCode':proc.returncode,'startedAt':start,'endedAt':end,'sourceInputCount':len(before),'sourceWindowChanges':changes,'outputDir':out.relative_to(ROOT).as_posix(),'outputFiles':outputs,'outputCount':len(outputs),'logSha256':sha(log),'frameworkRewrite':False,'packageLockChanged':False,'servicesStarted':False,'liveHttpOrBrowserVerified':False,'businessVerified':False,'migrationComplete':False}
    save(result,value);save(execution,{'status':'completed','exitCode':proc.returncode,'startedAt':start,'endedAt':end,'command':command,'sourceWindowChanges':changes});print(json.dumps({k:value[k] for k in ('status','exitCode','sourceWindowChanges','outputCount')},ensure_ascii=False));raise SystemExit(0 if success else 1)
if __name__=='__main__':main()
