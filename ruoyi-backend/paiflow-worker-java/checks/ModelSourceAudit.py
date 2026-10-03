"""只读取模型Java/React源码和model_category公开种子，不访问秘密配置或服务。"""
import hashlib, json, re
from pathlib import Path

root = Path(__file__).resolve().parents[3]
source = root / '参考项目/PaiFlow-main'
schema = source / 'docker/PaiFlow/mysql/schema.sql'
text = schema.read_text(encoding='utf-8')
block = re.search(r'INSERT INTO `model_category` VALUES (.*?);', text, re.S)
assert block, 'model_category seed absent'
pattern = re.compile(r"\((\d+),(\d+),'([^']*)','([^']*)',(\d+),'([^']*)','([^']*)',(-?\d+)\)")
rows = [dict(id=int(a), pid=int(b), key=c, name=d, isDelete=int(e), sortOrder=int(h))
        for a,b,c,d,e,f,g,h in pattern.findall(block.group(1))]
assert len(rows) == 102, 'unexpected seed tuple count'
active = {row['id']:row for row in rows if row['isDelete'] == 0}
cycles=set()
for node in active:
    sequence=[]; current=node
    while current in active:
        if current in sequence:
            cycle=sequence[sequence.index(current):]
            cycles.add(tuple(sorted(cycle))); break
        sequence.append(current); current=active[current]['pid']
orphans=[row for row in active.values() if row['pid'] != 0 and row['pid'] not in active]
files=[
 source/'console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/controller/model/ModelController.java',
 source/'console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/service/model/ModelService.java',
 source/'console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/service/model/ModelCategoryService.java',
 source/'console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/service/secret/SecretReferenceService.java',
 root/'frontend/paiflow-web/src/services/model.ts',
 root/'frontend/paiflow-web/src/pages/model-management/components/modal-component.tsx',
 root/'frontend/paiflow-web/src/pages/model-management/components/model-card.tsx',
]
report=dict(
 scope='源码和公开类别种子，只读、无网络、无配置解析',
 sources=[dict(path=str(file.relative_to(root)).replace('\\','/'),sha256=hashlib.sha256(file.read_bytes()).hexdigest()) for file in files],
 categorySeed=dict(path=str(schema.relative_to(root)).replace('\\','/'),line=2822,total=len(rows),active=len(active),deleted=len(rows)-len(active),
  roots=[row for row in active.values() if row['pid']==0],
  missingParentNodes=orphans,cycles=[list(cycle) for cycle in sorted(cycles)],
  modalDimensions={key:[row for row in active.values() if row['key']==key] for key in ['modelCategory','modelScenario','languageSupport','contextLengthTag']}),
 protocol=dict(apiKeyMasked={'editFalse':'沿用已存reference','editTrue':'核对和更换reference'},
  modal='env:reference，原组件不加密',republish='RSA包裹旧detail.apiKey且apiKeyMasked=false；已有id沿用旧引用可忽略此包裹值',
  validateBeforePersist={'request':{'model':'domain','messages':[{'role':'user','content':'Hello!'}],'stream':False},'originalResponsePredicate':'choices是数组且含usage；外调失败不落正式可用模型'},
  detailCustomLlmSource=0,detailOfficialLlmSource=1,unsupportedFineTuneLlmSource=2),
 prohibitedCarryOver=['任意Spring环境变量读取','向上八级.env.local发现','依调用方uid/spaceId直接授权','裸模型ID无space/membership详情读取',
 '编辑domain/url时按用户和节点prefix批量覆盖全部画布','GET/list隐式部署状态外调与写库','物理删除历史资源和原快照','未联机目录/微调空成功','Provider Hello响应日志原文'],
 reusablePureAlgorithms=['引用语法识别（解析不等于授权）','OpenAI chat/completions路径补全','Hello请求组装与有限响应结构检查','config/tag/json原字段映射',
 '分类ID去重、官方/自定义来源、同维度校验、单选多选关系','按sortOrder DESC/id DESC排序','encodeId/decodeId仅旧协议兼容标识映射'],
)
output=Path(__file__).with_name('model-source-audit.json')
output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(total=len(rows),active=len(active),deleted=len(rows)-len(active),missingParents=len(orphans),cycles=report['categorySeed']['cycles'],dimensions={key:len(value) for key,value in report['categorySeed']['modalDimensions'].items()},output=str(output)),ensure_ascii=False))
