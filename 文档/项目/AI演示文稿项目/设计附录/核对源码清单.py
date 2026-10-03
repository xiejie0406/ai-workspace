"""只读参考项目；清单与引用写入本项目设计目录，不运行源应用。"""
from pathlib import Path
import ast
import hashlib
import json
import re
from datetime import datetime

dest = Path(__file__).resolve().parents[1]
workspace = dest.parents[2]
source = workspace / '参考项目' / 'ai-ppt-generator-main'
prefix = '../../../参考项目/ai-ppt-generator-main/'

def link(path):
    rel = path.relative_to(source).as_posix()
    return f'[{rel}]({prefix}{rel})'

catalog = []
for p in sorted(source.rglob('*')):
    if not p.is_file() or any(x in p.parts for x in ['node_modules', '.git', '__pycache__', 'target', 'dist', '.venv']):
        continue
    if p.name in ['.env'] or p.suffix.lower() in ['.png','.jpg','.jpeg','.webp','.ico','.pdf','.zip']:
        continue
    data = p.read_bytes()
    catalog.append({'path': p.relative_to(source).as_posix(), 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
(dest / '源文件清单.json').write_text(json.dumps({'date':'2026-10-02','scope':'只读源文件定位与哈希；不表示已逐文件运行或测试通过','files':catalog}, ensure_ascii=False, indent=2), encoding='utf-8')

out = ['# AI 演示文稿源码核对附录', '', '> 版本：0.1；日期：2026-10-02；用途：支持完整迁移设计的源码出处与边界核对。',
       '> 核对方式：本地只读文件、路由装饰器、配置、工具注册、领域/工作流/编辑关键实现和测试入口。未启动源项目、连接数据库/Redis、调用 Provider 或运行这些测试。', '',
       '本附录只维护调查来源；需求、目标实现和验收计划唯一位于[完整迁移设计](完整迁移设计.md)。[源文件清单](源文件清单.json)记录当前文件大小与 SHA256，供实施前发现参考基线变化，不包含密钥或源文件正文。', '',
       '## 1. 关键核对依据', '',
       '| 事实 | 可回查原文 |', '|---|---|']
facts = [
('Python 技术栈与锁文件；COS SDK未在基础依赖声明', 'backend/pyproject.toml'),
('Java21/Boot3.5.9/旧模型图、MyBatis Plus、JWT与POI栈', 'java_backend/pom.xml'),
('React19/Vite8/React Query/Zustand 与独立身份，前端未声明测试脚本', 'frontend/package.json'),
('原项目只注册提纲与deck两个ARQ任务；原Worker直读写数据库', 'backend/app/worker/settings.py'),
('最大两次尝试/3秒重试；不能把缺Provider配置当瞬时重试', 'backend/app/worker/retry.py'),
('单页Semaphore3、软取消只在开页前检查、ready结果直接写原库', 'backend/app/worker/deck_tasks.py'),
('Java Redis list+TTL claim与blockingPop非ARQ兼容队列', 'java_backend/src/main/java/com/aippt/shared/redis/JobQueue.java'),
('Java页并发用Java21虚拟线程，不能直接搬入Java17', 'java_backend/src/main/java/com/aippt/deck/DeckGenerationService.java'),
('材料指纹不含page_count，兼容legacy；遗漏content_density需补', 'backend/app/services/outline_inputs.py'),
('页面增删同步slides/outline/page_count；原纯排序只改slide.position/revision', 'backend/app/api/v1/deck/pages.py'),
('创建5–20/正式编辑1–40页；接口长文本最大200000字', 'backend/app/schemas/project.py'),
('提纲标题/目的/2–5要点/来源/角色/visual定义', 'backend/app/domain/outline.py'),
('四解析器，扫描PDF明确不做OCR', 'backend/app/ingest/pdf.py'),
('图源自动降级：生成→Unsplash→占位，无独立图库检索页面', 'backend/app/images/pipeline.py'),
('私有媒体原来不鉴权，仅凭随机key公开访问', 'backend/app/api/v1/media.py'),
('来源文件先save再parse/commit，失败对象补偿需补', 'backend/app/services/sources.py'),
('六替换工具与仅flex开启的三结构工具；只改内存提案副本', 'backend/app/llm/edit_tools.py'),
('工具调用最多4轮；结构改稿不走正文的structured_output路径', 'backend/app/llm/slide_edit.py'),
('单页生成结构/密度检查与最多一轮自修复', 'backend/app/workflows/slide.py'),
('改稿生成/过滤/检查/修复与阻断剩余结构error', 'backend/app/workflows/slide_edit.py'),
('原ai-edit/apply接受客户端patch；目标须登记proposalId/opId并复核', 'backend/app/api/v1/deck/ai_edit.py'),
('同页内容/样式/结构队列，冲突暂停、50步本地撤销重做', 'frontend/src/features/deck/useSlideSaveQueue.ts'),
('换图独立mutation，主题独立debounce，目标统一队列与flush需补', 'frontend/src/features/deck/api.ts'),
('主题微调防抖与换预设/重置，版本保护需补', 'frontend/src/features/deck/ThemePanel.tsx'),
('固定→flex解锁、换布局原来仅改slot_id，flex语义需复核', 'backend/app/api/v1/deck/layout.py'),
('原Graph/灵活树领域与前端比率、grow、offset常量', 'frontend/src/features/deck/flex/constants.ts'),
('字体FontTools度量、缺字体估算；Java也有FontBox度量实现', 'backend/app/domain/text_metrics.py'),
('error阻断/warning可继续；低清阈值144px；内容越画布升级error', 'backend/app/domain/export_check.py'),
('仅PPTX导出；导出前检查→渲染→回读→返回；失败不返回文件', 'backend/app/api/v1/deck/export.py'),
('原生文字/表/图/图片/形状；备注与emoji文本runs', 'backend/app/render/pptx.py'),
('回读校验原生对象、页数、关键文字、边界及整页图片', 'backend/app/render/verify.py'),
('Java同样存在POI原生渲染与回读，不能因为保留Python而忽略', 'java_backend/src/main/java/com/aippt/render/PptxVerify.java'),
('放映明确不含演讲者备注视图、聚光灯或分享链接', 'frontend/src/features/deck/PresentMode.tsx'),
('SSE快照TTL1h、Redis发布订阅，无历史重放', 'backend/app/services/events.py'),
('Python SSE15秒心跳，先快照后订阅存在间隙；目标补持久序号恢复', 'backend/app/api/sse.py'),
('Java SSE首帧终态会结束，Python初始终态未同等提前结束；不能假定完全一致', 'java_backend/src/main/java/com/aippt/shared/web/SseSupport.java'),
('固定语料回归与字体获取，未在本轮执行', 'backend/scripts/run_regression.py'),
('API测试使用原数据库/Redis依赖，不因README称不耗额度而直接运行', 'backend/tests/conftest.py'),
('原生产compose自行拉起PostgreSQL/API/Worker/Nginx，不能原样部署进共享平台', 'docker-compose.prod.yml'),
]
for fact, rel in facts:
    out.append(f'| {fact} | {link(source/rel)} |')

out += ['', '## 2. Python 全部已注册业务端点', '',
        '路由前缀全局为 `/api/v1`。下表由装饰器解析，路径中的 `_PREFIX` 已按deck前缀展开；功能是否在React呈现应结合主设计映射，不以端点存在证明联机。', '',
        '| 方法 | 路径 | 文件 / 函数 |', '|---|---|---|']
api_root = source / 'backend/app/api/v1'
py_routes=[]
for p in sorted(api_root.rglob('*.py')):
    tree=ast.parse(p.read_text(encoding='utf-8-sig'))
    router_prefix={}
    constants={}
    for node in tree.body:
        if isinstance(node,ast.Assign):
            for tar in node.targets:
                if isinstance(tar,ast.Name) and isinstance(node.value,ast.Constant): constants[tar.id]=node.value.value
            if isinstance(node.value,ast.Call) and isinstance(node.value.func,ast.Name) and node.value.func.id=='APIRouter':
                pref=''
                for kw in node.value.keywords:
                    if kw.arg=='prefix':
                        pref=kw.value.value if isinstance(kw.value,ast.Constant) else constants.get(getattr(kw.value,'id',''),'')
                for tar in node.targets:
                    if isinstance(tar,ast.Name):router_prefix[tar.id]=pref
    for node in tree.body:
        if not isinstance(node,(ast.FunctionDef,ast.AsyncFunctionDef)):continue
        for dec in node.decorator_list:
            if not isinstance(dec,ast.Call) or not isinstance(dec.func,ast.Attribute):continue
            method=dec.func.attr.upper()
            if method not in ['GET','POST','PUT','PATCH','DELETE']:continue
            rn=getattr(dec.func.value,'id','')
            if rn not in router_prefix:continue
            sub=dec.args[0].value if dec.args and isinstance(dec.args[0],ast.Constant) else ''
            route='/api/v1'+router_prefix[rn]+sub
            py_routes.append((method,route,p,node.name,dec.lineno))
for method,route,p,name,line in py_routes:
    out.append(f'| {method} | `{route}` | {link(p)} — `{name}`（第{line}行） |')

out += ['', '## 3. Java 控制器实际端点', '',
        '| 控制器 | 注册动作（方法 / 相对路径） |', '|---|---|']
java_root=source/'java_backend/src/main/java/com/aippt'
java_routes=0
for p in sorted(java_root.rglob('*Controller.java')):
    txt=p.read_text(encoding='utf-8-sig')
    actions=[]
    for m in re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\(([^)]*)\))?', txt):
        method=m.group(1).upper();args=m.group(2) or ''
        match=re.search(r'"([^"]*)"',args);sub=match.group(1) if match else '(根路径)'
        actions.append(f'`{method} {sub}`');java_routes+=1
    out.append(f'| {link(p)} | '+ '；'.join(actions)+' |')

out += ['', '## 4. 前端页面、编辑器与工具注册来源', '',
        'React route提供 ProjectsPage、CreatePage、AuthPage和ProjectDetailPage，detail在提纲/正文之间切换。下列为全部页面和deck/outline组件、hooks、纯算法来源；目标Vue文件职责见主设计2.3节。', '']
front_paths=[]
for base in ['frontend/src/pages','frontend/src/features/deck','frontend/src/features/outline','frontend/src/render','frontend/src/hooks','frontend/src/api']:
    front_paths += sorted((source/base).rglob('*'))
for p in front_paths:
    if p.is_file():out.append(f'- {link(p)}')

out += ['', '## 5. 原测试、回归语料与共享目录', '',
        'Python发现42个 `test_*.py` 文件，另有conftest与包文件；Java发现20个Test.java。未发现React正式测试套件/测试脚本，名称含Block等不是测试。所有下列测试在本轮均未运行。', '', '### Python测试', '']
for p in sorted((source/'backend/tests').glob('test_*.py')):out.append(f'- {link(p)}')
out+=['','### Java测试','']
for p in sorted((source/'java_backend/src/test').rglob('*Test.java')):out.append(f'- {link(p)}')
out+=['','### 共享主题、布局、预设、样例及固定语料','']
for base in ['shared','backend/regression/corpus','backend/app/regression','backend/scripts']:
    for p in sorted((source/base).rglob('*')):
        if p.is_file():out.append(f'- {link(p)}')

out += ['', '## 6. 设计产物检查记录', '',
        '- 仅创建本项目主设计、源码定位附录、清单和图稿；未修改共享索引、根AGENTS、其他项目或业务源码。原工作树已有大量其他任务修改，保留原样。',
        '- 六幅SVG通过离线Sharp渲染为PNG，已逐张回读文字、表单、菜单、画布、状态及按钮位置，另有[总览图](图稿/界面图稿总览.png)。HTML图册采用本地SVG和内联切换脚本，无外部脚本/字体。',
        '- 内置浏览器拒绝file协议，未绕过限制；使用离线图形渲染检查，不声称已经在浏览器运行图册交互。',
        '- 相对文件引用、HTML本地资源、六图XML/PNG尺寸、526个源文件哈希和41个连续验收点的检查均通过，明细见[设计产物检查记录](设计附录/设计产物检查.json)。',
        '- 本附录末尾的检查只证明文档引用、源码定位和图稿可读性，不证明迁移后的业务结果；实施后须按主设计逐项重新取得真实证据。', '']
(dest/'源码核对附录.md').write_text('\n'.join(out),encoding='utf-8')
print(json.dumps({'source_files':len(catalog),'python_routes':len(py_routes),'java_routes':java_routes,'python_tests':42,'java_tests':20,'catalog_counts':{x:len(list((source/'shared'/x).glob('*.json'))) for x in ['themes','layouts','flex-presets']}},ensure_ascii=False))
