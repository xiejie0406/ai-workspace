"""已完成同步后的五份库及两次静态GET；只读核指纹，不代表业务通过。"""
from pathlib import Path
from datetime import datetime,UTC
from urllib.request import urlopen
import hashlib,json,sys
ROOT=Path(__file__).resolve().parents[2]
digest,build_exit=sys.argv[1:]
phase='final'
assert build_exit=='0'
out=ROOT/f'.runtime/aipicture/view-selection-react-sync-caption-{phase}.json'
assert not out.exists()
hash_bytes=lambda b:hashlib.sha256(b).hexdigest()
paths=['frontend/aipicture-workbench/dist/aipicture-workbench.js','frontend/admin-web/public/aipicture/aipicture-workbench.js','frontend/admin-web/dist/aipicture/aipicture-workbench.js','.runtime/aipicture/browser-check/aipicture-workbench.js','.runtime/aipicture/admin-dist/aipicture/aipicture-workbench.js']
rows=[]
for relative in paths:
 p=ROOT/relative
 row={'path':relative,'bytes':p.stat().st_size,'sha256':hash_bytes(p.read_bytes())}
 assert row['sha256']==digest
 rows.append(row)
gets=[]
for url in ['http://127.0.0.1:14385/aipicture/aipicture-workbench.js','http://127.0.0.1:8416/aipicture-workbench.js']:
 with urlopen(url+'?v='+digest[:8],timeout=20) as r:
  b=r.read();row={'url':url+'?v='+digest[:8],'status':r.status,'bytes':len(b),'sha256':hash_bytes(b)}
 assert row['status']==200 and row['sha256']==digest
 gets.append(row)
record={'created_at':datetime.now(UTC).isoformat(),'phase':phase,'scope':__doc__,'bundles':rows,'actual_static_http_gets':gets,'admin_production_build_native_exit':0,'formal_business_writes':0,'model_or_provider_calls':0,'business_acceptance':False,'note':'最终文案库01104508同步；23检查沿用749同机制基线，当前文案只重验受影响GUI'}
with out.open('x',encoding='utf-8') as f:json.dump(record,f,ensure_ascii=False,indent=2);f.write('\n')
print(json.dumps({'phase':phase,'copies':len(rows),'actual_static_gets':len(gets),'sha256':digest,'business_acceptance':False}))
