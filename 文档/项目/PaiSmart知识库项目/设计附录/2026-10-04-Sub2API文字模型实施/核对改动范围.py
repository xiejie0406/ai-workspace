"""只读比对修改前原文与当前文件，记录非文字链路及本轮改动范围。"""
from pathlib import Path
import csv
import hashlib
import json
import yaml

evidence = Path(__file__).resolve().parent
root = next(p for p in evidence.parents if (p / 'ruoyi-backend/pom.xml').is_file())
sha = lambda data: hashlib.sha256(data).hexdigest()
baseline = list(csv.DictReader((evidence / '修改前源码摘要.csv').open(encoding='utf-8-sig')))
changed = [r['path'] for r in baseline if not (root / r['path']).is_file() or sha((root / r['path']).read_bytes()) != r['sha256']]
main = 'ruoyi-backend/ruoyi-paismart/src/main/'
new = [p.relative_to(root).as_posix() for p in (root / main).rglob('*') if p.is_file() and not (evidence / '修改前原文' / p.relative_to(root)).is_file()]
gateway = main + 'java/com/ruoyi/paismart/application/intelligence/ModelGateway.java'
old = (evidence / '修改前原文' / gateway).read_text(encoding='utf-8')
current = (root / gateway).read_text(encoding='utf-8')
def segment(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]
checks = {
    'defaultConfigValuesUnchanged': yaml.safe_load((root / (main + 'resources/application-paismart.yml')).read_text(encoding='utf-8')) == yaml.safe_load((evidence / '修改前原文' / (main + 'resources/application-paismart.yml')).read_text(encoding='utf-8')),
    'embeddingGatewayMethodUnchanged': segment(old, 'public Embedding embed(', 'public Map<String,Object> complete(') == segment(current, 'public Embedding embed(', 'public Map<String,Object> complete('),
    'rerankGatewayMethodUnchanged': segment(old, 'public Map<String,Object> rerank(', 'public ProviderService.Provider active(') == segment(current, 'public Map<String,Object> rerank(', 'public ProviderService.Provider active('),
    'callLedgerUnchanged': (root / (main + 'java/com/ruoyi/paismart/application/intelligence/ModelCallService.java')).read_bytes() == (evidence / '修改前原文' / (main + 'java/com/ruoyi/paismart/application/intelligence/ModelCallService.java')).read_bytes(),
    'streamContractUnchanged': (root / 'contracts/paismart/stream-v1.schema.json').read_bytes() == (evidence / '修改前原文/contracts/paismart/stream-v1.schema.json').read_bytes(),
    'jobEventContractUnchanged': (root / 'contracts/paismart/jobs-v1.asyncapi.yaml').read_bytes() == (evidence / '修改前原文/contracts/paismart/jobs-v1.asyncapi.yaml').read_bytes(),
}
result = {'scope': '源码及契约静态字节比对；不执行函数、测试或业务', 'changedExistingFiles': changed, 'newMainFiles': new, 'checks': checks}
(evidence / '改动范围核对.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False, indent=2))
if not all(checks.values()):
    raise SystemExit('非文字或旧账本内容变化，需要核查')
