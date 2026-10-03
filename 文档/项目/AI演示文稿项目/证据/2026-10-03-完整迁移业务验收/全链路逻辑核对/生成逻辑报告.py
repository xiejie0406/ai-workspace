"""源码/历史制品核对、受控同源静态同步与中文报告生成；不导入业务模块，不运行测试或服务。"""
from pathlib import Path
import ast
import hashlib
import html
import json
import os
import re
import shutil
import sys
import tarfile
import zipfile
from datetime import datetime, timezone, timedelta
from html.parser import HTMLParser

ROOT = Path(r'D:\2025Ai\26-05-23\java-workspace')
HERE = Path(__file__).resolve().parent
OUT = ROOT / 'output/aippt-logic-delivery-20261003'
F = 'frontend/aippt-web/src/'
J = 'ruoyi-backend/ruoyi-aippt/src/main/java/com/ruoyi/aippt/'
W = 'python/aippt-worker/app/'
C = 'contracts/aippt/'
NOW = lambda: datetime.now(timezone(timedelta(hours=8))).isoformat()
H = lambda b: hashlib.sha256(b).hexdigest()
E = html.escape

def inventory(directory):
    return [{'path': p.relative_to(directory).as_posix(), 'bytes': p.stat().st_size, 'sha256': H(p.read_bytes())}
            for p in sorted(directory.rglob('*')) if p.is_file() and not p.is_symlink()]

def save_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')

def freeze():
    paths = list((ROOT/'frontend/aippt-web/src').rglob('*'))
    paths += [ROOT/'frontend/aippt-web'/name for name in ['package.json','package-lock.json','vite.config.ts','tsconfig.json','tsconfig.app.json','tsconfig.node.json','index.html']]
    paths += list((ROOT/'frontend/aippt-web/public').rglob('*')) + list((ROOT/'contracts/aippt/catalog').rglob('*'))
    paths += [ROOT/'contracts/aippt/catalog-manifest.json']
    files = [{'path':p.relative_to(ROOT).as_posix(),'bytes':p.stat().st_size,'sha256':H(p.read_bytes())} for p in sorted(set(paths)) if p.is_file()]
    save_json(OUT/'构建前输入身份.json', {'captured_at':NOW(), 'kind':'生产构建输入字节快照，非测试', 'files':files})
    print(f'构建输入已冻结：{len(files)}文件')

# 每条独立写出六段链路。引用符号在生成时按当前源码定位到真实行号。
POINTS = []
def point(number, chain, refs, fixed=False):
    POINTS.append({'number':number, 'chain':chain, 'refs':refs, 'fixed':fixed})

point(1, [
    '平台授权外链进入原React；RequireAuth等待现有平台会话恢复，账号页仅引导平台登录。',
    'Admin-Token只从同源Cookie读取，getInfo提供用户、角色与权限；禁止Token放URL及独立注册/JWT。',
    '用户API复用@ss权限并通过owned校验本人；internal/aippt独立机器安全链不接受用户Token。',
    '身份恢复由原Zustand维护，Worker机器身份仅领取已由中心授权的任务，没有用户状态库。',
    '会话变化清原Query/Zustand及Blob；中心数据owner来自SecurityUtils，不采用请求传来的owner。',
    '无会话安全回跳平台登录；旧getInfo回执受Token检查。已补旧logout迟到回执及二进制JSON错误的会话保护。'
], [(F+'features/auth/store.ts','logout:'),(F+'features/auth/token.ts','platformLoginUrl'),(F+'features/auth/session.ts','startPlatformSession'),(J+'configuration/AipptWorkerSecurityConfiguration.java','aipptWorkerChain'),(J+'application/AipptService.java','public JSONObject owned')], True)
point(2, [
    '平台后台菜单提供同源H5外链；React仍用AppShell及全屏提纲/编辑器，不建立Vue业务入口。',
    '登录redirect仅接受同源批准React路由并过滤凭据、控制字符和编码穿越。',
    '平台getRouters/现有三种导航处理菜单；API前缀严格同源，身份沿平台getInfo。',
    'BrowserRouter basename=/aippt-web，原projects/create/detail路由保留；静态history fallback仅处理HTML。',
    'Vite管理端插件及Nginx候选指向React index；不存在的静态资源404，不吞API路径。',
    '原离开守卫等待相关保存，失败留页；未执行三种导航、真实登录或Nginx部署验证。'
], [(F+'App.tsx','BrowserRouter basename'),(F+'hooks/useSaveNavigationGuard.ts','useSaveNavigationGuard'),('frontend/admin-web/vite.config.js','aipptHistoryFallback'),('frontend/admin-web/src/views/login.vue','aippt'),('frontend/admin-web/scripts/aippt-nginx-location.conf.example','location')])
point(3, [
    '原CreatePage推导名称；ProjectsPage搜索、正式状态分页、继续编辑和删除。',
    '创建需project:add/source:add，最终生成还由服务端outline:generate裁决；参数来自原单栏表单。',
    'POST /projects与sources建立本人私有项目，GET /projects按owner/状态/名称查询及返回total。',
    '创建后材料解析和提纲任务分别入队；模型工作不在创建事务中执行。',
    '项目/材料由MySQL持有；后续失败DraftCreationError保留projectId及成功材料，提供继续入口。',
    '不删成功项目掩盖后续错误。列表只提交项目正式支持的四状态，deck的partial在工作台显示；未知删除回执重放原版本。'
], [(F+'pages/CreatePage.tsx','deriveTitle'),(F+'pages/ProjectsPage.tsx','useProjectsPage'),(F+'features/projects/api.ts','useCreateDraft'),(J+'application/AipptService.java','public JSONObject create'),(J+'infrastructure/persistence/AipptMapper.java','projectCount')], True)
point(4, [
    '原新建入口保持主题500字/文字20000字；材料参数弹层区分生成目标与已有正式页数。',
    '创建5–20，正式1–40；新目标改动5–20，未改页数不提交；名称200、受众100及枚举/严格数字由中心复核。',
    'project PATCH提交input_revision，中心项目锁、expected及unconfirmed/idle拒绝旧版本与在途修改。',
    'Java字段校验不借Python模型截断请求；页面编辑不套创建页数上限。',
    '同事务保存项目并更新input_revision；弹层保原baseline与delta，刷新不借用新revision覆盖草稿。',
    '409读最新值后明确重新应用或放弃；未知回执保原正文/文件/key。真实边界数据未提交。'
], [(F+'features/projects/ProjectMaterialsDialog.tsx','saveSettings'),(J+'domain/AipptRules.java','public static void project'),(J+'application/AipptService.java','public JSONObject update')])
point(5, [
    '主题/文本可从新建或材料弹层添加、查看章节及删除。',
    'source:add/query/remove与本人项目归属分别校验，正文API上限200000字。',
    'sources命令按input_revision登记source、parse_source任务和事件；删除用稳定sourceId。',
    'Worker PlainTextParser解析并stable_sections派生稳定UUID章节，保层级/定位及字数。',
    '中心completeOutput写source_section和source.sections、parser_version/warnings并更新input_revision。',
    '删除保审计但普通读取404，在途解析取消/迟到拒绝；章节面板按选择代数过滤旧回执。'
], [(F+'features/projects/ProjectMaterialsDialog.tsx','const view'),(J+'application/AipptService.java','public JSONObject addSource'),(J+'application/AipptService.java','case "parse_source"'),(W+'worker/parse.py','stable_sections'),(W+'ingest/plain.py','PlainTextParser')])
point(6, [
    '原多文件新建与补传逐文件显示解析状态和失败原因。',
    '单文档10MiB，source:add权限、项目版本与确认锁定；文件/正文归本项目资产。',
    'upload先私有暂存和上传幂等，再登记source与parse_source；机器读资产须有效租约。',
    'Worker注册TXT/Markdown/DOCX/PDF解析器，保heading/level/text/locator，统一稳定section ID。',
    '成功解析写中心正式小节；源文档由私有storage持有，React查询中心结果。',
    '部分失败保成功材料及项目，可删/换失败文件并补传；本轮未上传真实四类文档。'
], [(J+'controller/AipptController.java','uploadSource'),(J+'application/AssetService.java','uploadCommand'),(W+'worker/parse.py','parse_document'),(W+'ingest/registry.py','PARSERS'),(W+'ingest/docx.py','Docx'),(W+'ingest/pdf.py','Pdf')])
point(7, [
    '空文件、改名Office、超限及扫描PDF均从同一材料入口处理。',
    'Java校验后缀/魔数/Office结构、大小及解压数量/总量；Python再校验解析预算。',
    '失败上传不成为正式source；暂存journal与outbox保留所属补偿信息。',
    'PDF只提取文字，没有OCR；无可用文字时给警告，提纲生成不能把空材料当证据。',
    '扫描件警告保存在source，原文可保留；有有效文字的其他材料仍可用。',
    '失败对象按引用与所属范围清理，默认cleanup关闭；未运行扫描件/压缩炸弹样本。'
], [(J+'application/AssetService.java','public static String validate'),(J+'application/AssetService.java','zipStructure'),(W+'ingest/pdf.py','warnings'),(W+'worker/parse.py','parse_document'),(J+'application/AipptService.java','请先完成有效材料解析')])
point(8, [
    '提纲确认前可改材料和内容参数，确认后先取消确认；主题外观可继续调。',
    '指纹包含title/audience/tone/content_density及按sourceId排序的parser_version/sections；页数单独校验。',
    'confirm校验outline revision、input_revision、来源/布局、目标页数与当前指纹；材料变更走unconfirmed和idle。',
    '指纹由Java中心计算，Worker只消费不可变输入，不签发正式确认。',
    '提纲保存status=draft，确认写confirmed；新建正式页后1–40页再次确认保持原内容。',
    '旧指纹不自动获得新确认；OUTLINE_STALE/CONFIRMED与在途限制明确拒绝，主题不使材料过期。'
], [(J+'domain/AipptRules.java','public static String fingerprint'),(J+'application/AipptService.java','private JSONObject confirm'),(J+'application/AipptService.java','public JSONObject unconfirm'),(F+'features/projects/ProjectMaterialsDialog.tsx','unconfirm')])
point(9, [
    '原提纲页面显示队列/整理/规划及来源裁剪摘要，允许失败后再生成。',
    'outline:generate/query与有效来源、input_revision、Provider/Worker开关核对。',
    'generate_outline任务绑定输入指纹及expected_input_revision；中心拒绝旧任务或旧材料结果。',
    '原prepare_outline_input预算总12000/单节2000字，生成后按真实页数与允许来源ref校验。',
    'pages/warnings/source_budget同一正式outline JSON持久化，GET及任务output/result一致；摘要无正文/密钥。',
    '来源为空/伪ref/页数不符拒绝；提纲再生成未知回执重放原input_revision，未真实调用模型。'
], [(W+'workflows/outline.py','prepare_outline_input'),(W+'worker/handlers.py','async def generate_outline'),(J+'application/AipptService.java','outlineSourceMetadata'),(F+'features/outline/api.ts','generate-outline'),(F+'features/outline/OutlineWorkspace.tsx','outline.warnings')], True)
point(10, [
    '原PageCard编辑标题/目的/要点/稳定来源/版式/角色/visual，并增删拖排。',
    '5–20页、标题100、目的300、要点2–5、来源≤10、visual≤120，中心按目录和来源再验证。',
    'PATCH outline提交原revision，增删导致目标数变化时先项目保存再提纲保存；确认与保存权限各自检查。',
    'React规范化空行与原串行自动保存，Java校验UUID、字段白名单及目标引用，没有再生成算法代替手改。',
    '中心同事务保存pages及项目page_count；React Query回写正式提纲，未保存本地页保留。',
    '失败暂停自动保存，明确重试原正文；409先核对最新大纲。字段齐全不表示已真实保存。'
], [(F+'features/outline/OutlineWorkspace.tsx','function PageCard'),(F+'features/outline/OutlineWorkspace.tsx','const saveDraft'),(J+'application/AipptService.java','public JSONObject saveOutline'),(J+'domain/AipptRules.java','public static void outline')])
point(11, [
    '点生成PPT前先完成提纲与主题保存，再原子确认并建立页面任务。',
    'outline:confirm与deck:generate同时要求；正式提纲/项目版本、指纹及空闲条件均校验。',
    'confirm-and-generate通过command幂等缓存、项目锁和同事务确认/建任务；不拆成先确认再异步启动的正式状态。',
    '本轮原hook保留已完成的保存/主题步骤及已接受启动标识；未知回执重放原版本/key，不重走前置保存。',
    '响应后回读正式outline与deck；尚未确认时暂停编辑/主题/自动保存并提供重试原确认与生成。',
    '同请求重复不建第二批；请求已接受但GET失联时仅重新回读。离页保护等待确认，真实重复收费未核账。'
], [(F+'features/outline/useConfirmAndGenerate.ts','pendingInput'),(F+'features/outline/OutlineWorkspace.tsx','重试原确认与生成'),(J+'application/AipptService.java','public JSONObject confirmOutline'),(J+'application/AipptService.java','JSONObject cached=db.idempotent')], True)
point(12, [
    '生成进入原编辑器，每页就绪即时可见；Filmstrip、画布及进度读同一中心deck。',
    'deck:generate/query，生成期间人工写入受idle拒绝；项目总/逐页版本来自中心。',
    '每outline page创建独立generate_slide任务；dispatch gate串行领取，项目并发与globalConcurrency有界。',
    'Worker多个slot领取，使用原fixed/flex单页workflow；模型、图片不在中心事务中执行。',
    '中心completeOutput按active_task_id/expected_slide_revision与租约提交，bump deck_revision并发布事件。',
    '单页错误不抹成功页；过期任务回收有maxAttempts/deadline，未测真实并发时间或即时预览延迟。'
], [(J+'application/AipptService.java','private JSONObject generate'),(J+'application/TaskLeaseService.java','claimOnce'),(W+'worker/runner.py','async def slot'),(F+'features/deck/useDeckProgress.ts','useDeckProgress')])
point(13, [
    '提纲/整稿进度沿原SSE hook；连接中断时保已存进度并提示重连/通知降级。',
    '连接前和每次发送都回读平台会话owner/对应权限，撤权或身份变化终止；游标来自中心序号。',
    '持久aippt_event重放≤500、快照及resync_required；Redis只唤醒正式DB回读，15秒心跳。',
    'React每次连接先读快照，去重eventSeq，resync回读后再次核scope；指数退避≤15秒。',
    '纯delivery提示不推进业务状态或替换task；终态以正式outline/deck/task决定并停重连。',
    '401/403停止，旧scope不能发新目标旧ID请求；未执行真实长连接、Redis断线或权限撤回。'
], [(J+'controller/EventController.java','projectSessionValid'),(J+'application/EventService.java','public SseEmitter subscribe'),(F+'hooks/useEventStream.ts','resync_required'),(C+'events-v1.schema.json','eventSeq')])
point(14, [
    '失败卡以deck:generate显示单页重试；默认整稿续跑保已ready页。',
    '单页重试检查slide revision、确认提纲、空闲和Provider；批次生成检查deck_revision。',
    'retrySlide保存旧快照并登记目标页任务，generate续跑跳过就绪页；复用中心版本和幂等。',
    '重试与整稿都用同一generate_slide handler/专业workflow，没有第二算法。',
    '只目标页回写且active_task_id匹配；React更新deck计数和失败提示。',
    '本轮生成/重试未知回执先重放原请求，避免借新revision自撞409；失败原因与历史保留，未核真实收费次数。'
], [(F+'features/deck/Filmstrip.tsx','useRetrySlide'),(F+'features/deck/api.ts','function useDeckMutation'),(J+'application/AipptService.java','public JSONObject retrySlide'),(J+'application/AipptService.java','"ready".equals(slide.getString("status")))continue')], True)
point(15, [
    '取消沿原按钮；整份重做明确确认覆盖并保存原内容快照。',
    'deck:cancel/generate、deck_revision、overwrite_acknowledged；Worker身份还需attempt/leaseToken/fencingToken。',
    'queued置cancelled不派发，running仅标cancel_requested；重领/回收提升fencing token。',
    'Worker心跳保软取消语义；租约失效停止操作和迟到提交，中心恢复次数/总900秒截止有界。',
    '成功在途页可保留；无活跃页任务后中心settle，编辑器显示partial/ready，重做前snapshot不丢。',
    '旧租约/删除项目拒绝结果；本轮取消未知回执沿原版本恢复，没有崩溃/故障演练。'
], [(J+'application/AipptService.java','public JSONObject cancel'),(J+'application/TaskLeaseService.java','validateLease'),(W+'worker/runner.py','async def execute'),(J+'application/AipptService.java','snapshot(j,"regenerate")')], True)
point(16, [
    '整稿和单页从提纲角色/密度/visual及相邻标题构造相同输入。',
    'fixed/flex模式、内容密度、来源稳定ref与字体身份在任务快照中固定。',
    '中心按页派发generate_slide；Worker只返回slide/issues/geometry/assets，正式提交再验证。',
    '原prepare→generate→check→最多一轮repair，单页来源总6000/单节1500字；仅结构错误/偏瘦空话触发修复。',
    '确定性先定列宽再定行高，issues随候选回写；纯容量warning不盲目砍块。',
    '修复仍失败形成任务终态；字体estimate保持提示，未执行真实模型生成/修复。'
], [(W+'workflows/slide.py','MAX_REPAIR_ROUNDS'),(W+'workflows/slide.py','prepare_slide_input'),(W+'worker/handlers.py','async def generate_slide'),(J+'application/AipptService.java','case "generate_slide"')])
point(17, [
    '原Filmstrip插空白/复制/删除/排序，成功后选中新页或邻页。',
    'slide:edit、项目空闲与deck_revision；正式1–40页、最后一页不可删。',
    '中心pages同事务维护slide/outline页序/项目page_count；复制块/树ID重建、内容和locked保留。',
    'Java确定性空白页与ID映射，不调用Worker；图片通过同项目asset引用及引用计数保留。',
    '新命令先flush相关保存；整份deck回写保较新正式版本、主题及保存队列草稿，并更新Query/outline/project。',
    '本轮增删复制排序重放未知原请求而不重读CAS；真实1/40页操作未执行，历史旧Vue页测试不计当前React。'
], [(F+'features/deck/api.ts','function useDeckPageMutation'),(F+'features/deck/api.ts','useReorderSlides'),(J+'application/AipptService.java','public JSONObject pages'),(J+'application/AipptService.java','private void syncPages')], True)
point(18, [
    '原BlockView/EditableText及ChartDataEditor编辑七类文字/数据，换图单列第33项。',
    'slide:edit与ready状态，type/字段/数据尺寸/有限数由中心再次校验；不接受自由HTML。',
    '块PATCH提交同页revision，中心项目锁/页CAS/人工locked与审计。',
    '原mergeBlockCommit、要点清洗及内容快照驱动原页串行队列，无额外正式状态。',
    '成功回写新页revision与deck_revision；Query局部替换页，保在途DOM编辑。',
    '409/网络错误暂停队列并保草稿，刷新后的正式值仍以中心为准；中文输入法和所有块实际操作未执行。'
], [(F+'render/EditableText.tsx','EditableText'),(F+'render/BlockView.tsx','BlockView'),(F+'features/deck/useSlideSaveQueue.ts','const pump'),(J+'application/AipptService.java','case "content"'),(J+'domain/AipptRules.java','public static void block')])
point(19, [
    '原图表编辑器维护bar/column/line/pie的类别、系列、单位与预览。',
    '中心校验四类型、200类别/20系列、维度一致与有限数；pie可保多个系列，预览首组。',
    '同页块PATCH保存完整数据，内部预览key不进入API或正式IR。',
    'ChartView按系列位置生成dataKey，避免name/同名组覆盖；PPTX原CategoryChartData按原系列写工作簿。',
    'verify逐图核类型/方向/cache/类别/系列/值/公式及该图内嵌XLSX全量数据，pie可见缓存按首组。',
    '维数/非数/缺失损坏/外链工作簿/篡改拒绝；本轮只核当前字节与旧修复证据，未重跑四图样本。'
], [(F+'render/ChartView.tsx','dataKey'),(F+'features/deck/ChartDataEditor.tsx','ChartDataEditor'),(W+'render/chart.py','render_chart'),(W+'render/verify.py','_CHART_NS'),(J+'domain/AipptRules.java','case "chart"')])
point(20, [
    '原ElementToolbar改文字/容器样式并重置到主题。',
    'slide:edit，字号8–72、圆角0–48、边框0–6、padding0–48、字重300–900及合法颜色；图表元素样式仍拒绝。',
    'style PATCH与内容/树共用页队列和revision，null表示重置，不变更正式块类型。',
    'React blockStyle、Python block_style及中心style验证在各边界消费统一IR/主题约束。',
    'style JSON保存在正式blocks，质量/导出从同版本快照读取；reset继承主题。',
    '非法范围或图表样式拒绝，草稿/冲突仍沿原队列；未验证真实字体视觉及焦点。'
], [(F+'features/deck/ElementToolbar.tsx','ElementToolbar'),(F+'render/blockStyle.ts','resolve'),(J+'application/AipptService.java','case "style"'),(J+'domain/AipptRules.java','public static void style'),(W+'domain/block_style.py','BlockStyle')])
point(21, [
    '同页文字/样式/树/换图/AI采纳走原保存桥，Undo/Redo为每页50步内存历史。',
    '所有动作读队列当前formal revision，换页/会话后旧回执不能提交新页缓存。',
    '内容、style、flex-layout/state及跨组件mutate共一队列，后台每次页CAS增加revision。',
    '原snapshot/merge及history before/after维护撤销；执行闭包保存原版本，未知回执保留原请求。',
    '成功局部合并Query页面；每页独立队列，项目级flush还等待主题队列。',
    '失败暂停、flush拒绝并保留待作业；历史不跨刷新。未真实执行50步组合及多页同时保存。'
], [(F+'features/deck/useSlideSaveQueue.ts','MAX_HISTORY'),(F+'features/deck/useSlideSaveQueue.ts','job.execute'),(F+'features/deck/slideSaveBridge.ts','runSlideMutation'),(F+'features/deck/slideSaveBridge.ts','flushProjectSlides')])
point(22, [
    '页队列与材料/提纲参数原地显示保存失败/冲突，离开先等待或给明确提示。',
    '页revision、输入/主题版本分别CAS，client未知回执按会话/方法/路径及意图区分。',
    '409为明确失败，允许核对最新后重基；网络/5xx为未知结果，不能换key或新正文。',
    'requestPrepared本轮在构造含版本正文前检查未知请求；同一意图重放原body/key，不重复版本准备。',
    '队列/弹层保本地草稿、文件及baseline；原路由guard/beforeunload守住未提交内容。',
    '不同意图拒绝混入；未知状态保存在本次浏览器内存，关闭/刷新后的永久恢复并未承诺。未断网/双标签实测。'
], [(F+'api/client.ts','requestPrepared'),(F+'features/deck/useSlideSaveQueue.ts','pausedRef'),(F+'hooks/useSaveNavigationGuard.ts','beforeunload'),(F+'features/projects/ProjectMaterialsDialog.tsx','reapply')], True)
point(23, [
    '原ThemePanel选择ivory/obsidian/midnight/scholar并改颜色/字体对/字号/圆角/要点符号。',
    'theme:edit、theme_revision与白名单，确认后主题仍可改而不改变材料指纹。',
    '主题PATCH在项目锁内校验并bump theme_revision；页面质量缓存随后按新版本失效。',
    '原四theme JSON、React themeOverrides/ambient与Python resolve_theme共同消费唯一catalog。',
    '原主题队列320ms串行保存、人工新增意图继续等待；预览和导出读取theme_overrides。',
    '非法字体/颜色明确拒绝，reset回目录，保存未知正文冻结；未真实逐主题视觉/导出对比。'
], [(F+'features/deck/ThemePanel.tsx','registerThemeSaveHandlers'),(J+'application/AipptService.java','public JSONObject theme'),(J+'application/AipptService.java','validateTheme'),(W+'domain/theme.py','resolve_theme'),(C+'catalog/themes/ivory.json','font')])
point(24, [
    '原LayoutPanel列11固定布局兼容候选，支持固定切换及解锁flex。',
    'deck:query读取候选，slide:edit提交，块类型/槽位容量/必需槽位在中心校验。',
    'layout或unlock-flex按页revision处理；固定树必须为空，兼容分配保块ID/锁定。',
    'CatalogService.compatible确定槽位分配，FlexEditing.fromFixed保首帧rect_override。',
    'layout_id/mode/tree保存正式页；React首帧原矩形与后续自然树使用既有renderer。',
    '不兼容具体拒绝不丢内容；后续结构改动清override；未全量实际切换11布局。'
], [(F+'features/deck/LayoutPanel.tsx','LayoutPanel'),(J+'application/CatalogService.java','compatible'),(J+'domain/FlexEditing.java','fromFixed'),(J+'application/AipptService.java','case "unlock-flex"')])
point(25, [
    '原FlexEditLayer支持分隔/四边resize、同/跨容器移动、两三列和八类块增删、留白/出血。',
    '仅flex且slide:edit，树深度/节点/ID/块引用/比例/尺寸和至少一块由中心再次校验。',
    'flex-layout、flex-state及blocks经原同页队列与CAS；用户pointer取消只恢复本地几何。',
    '原flex目录、geometry/normalize、Python solve/width/fit/skin保留，Java只验证提交树和受影响动作。',
    'blocks/tree/layout_mode/id同一正式页；spacer/locked/style及rect_override语义按原算法保持。',
    '非法树/最后一块删除拒绝；未知提交保待作业，未运行跨三端几何或实际拖拽测试。'
], [(F+'features/deck/FlexEditLayer.tsx','FlexEditLayer'),(F+'features/deck/useFlexPointerSession.ts','useFlexPointerSession'),(J+'domain/FlexEditing.java','insertLeaf'),(W+'domain/flex_solve.py','solve'),(C+'schemas/layout-tree.schema.json','children')])
point(26, [
    '原RelayoutDock展示当前/候选/更多，选中候选应用或恢复原树。',
    'ai:edit生成、slide:edit采纳；只合法flex，候选绑定slide expected revision。',
    'relayout任务只写proposal；apply验证同项目/同页/类型/版本，不能把模型树直接写正式状态。',
    '原模型propose及build_relayout_candidates过滤同构与非法树，清rect_override而保内容。',
    'candidate_id/proposal_id进入正式apply，中心校验树/锁定并bump deck_revision；Undo恢复人工树。',
    '过期409，面板按project/slide/generation/token拒迟到回执；未真实联机比较候选。'
], [(F+'features/deck/RelayoutPanel.tsx','const applyTree'),(F+'features/deck/api.ts','resolveProposal'),(W+'worker/handlers.py','async def relayout'),(J+'application/AipptService.java','public JSONObject apply')])
point(27, [
    '质量/导出面板显示字体估算、溢出及页/槽位定位；原正文不会为适配而删除。',
    '字体任务快照必须含unavailable或SHA256，Worker核manifest、两字体大小/hash/解析结果。',
    'completeOutput用fontManifest将任务资源身份与实际回执再次核对，错版本拒绝。',
    '原text_metrics中英/emoji估算与字形度量、flex fit和slot/canvas检查保留；槽位溢出warning、画布越界error。',
    'unavailable强制任务内estimate，不借本机碰巧存在字体冒称精确；report.fonts_precise反馈面板。',
    '缺真实许可字体的3历史项仍跳过，未下载字体或视觉核对；verified资源身份也不证明每个字形精确。'
], [(W+'worker/fonts.py','bind_task_fonts'),(W+'domain/text_metrics.py','font'),(W+'domain/export_check.py','check_slot_bounds'),(J+'domain/AipptRules.java','fontManifest'),(F+'features/deck/ExportDialog.tsx','fonts_precise')])
point(28, [
    '原AiEditPanel最多带最近5轮历史，显示before/after、discarded及重新生成。',
    'ai:edit提案，instruction≤500；fixed仅六替换，flex另有add/delete/change_type，锁定块过滤。',
    'ai_edit任务接正式页快照，提案完成只登记proposal；apply为另一用户命令。',
    '原九工具与slide_edit workflow仅改副本，结构/ID/候选校验和最多一轮修复保持。',
    '中心校验candidate_slide/protect，给每项操作正式ID；前端逐项展示而不自动采纳。',
    '非法工具/块/图表文字替换或结构错误拒绝；失败历史保留，未真实Provider核九工具输出。'
], [(F+'features/deck/AiEditPanel.tsx','sendInstruction'),(W+'llm/edit_tools.py','build_edit_tools'),(W+'workflows/slide_edit.py','build_slide_edit_workflow'),(W+'worker/handlers.py','async def ai_edit'),(J+'application/AipptService.java','public JSONObject propose')])
point(29, [
    '手改/换图写locked；AI逐项采纳、撤回或舍弃，整页重做另有显式覆盖确认。',
    '提案不持正式写权限，用户apply需slide:edit、页CAS与proposal expected revision。',
    'apply核同项目/页/类型，ProposalOperations只改该操作涉及块/叶；用户原结构恢复走人工flex-state。',
    'Worker候选保护锁定，中心protect再次校验；fixed不能通过结构工具扩权。',
    '采纳/撤回保存正式slide、更新proposal基准、deck_revision及审计，其他未采纳内容保持。',
    '手改使候选过期409，AI不能覆盖locked；原Undo只保护变化块，不承诺跨刷新历史。未实际并发采纳。'
], [(J+'domain/ProposalOperations.java','public static void apply'),(J+'domain/AipptRules.java','public static void protect'),(J+'application/AipptService.java','public JSONObject apply'),(F+'features/deck/AiEditPanel.tsx','const applySide')])
point(30, [
    '项目卡片和材料弹层删除目标，保成功页/原文证据及正式补偿清单。',
    'remove权限+owner+输入版本，项目删除后所有普通读/新任务/下载拒绝。',
    'deleteProject标deleted并取消任务/outbox，不直接删行；同原key可确认删除回执，新key仍404。',
    '引用计数覆盖source/slide/snapshot/export/proposal；cleanup只清所属UUID对象，外部IO在事务外。',
    'AssetCompensationJournal在put前刷盘，失败/崩溃留stage_cleanup；引用中的共享页图片不能误删。',
    '清理失败退避保pending，cleanup默认关闭；本轮项目删除未知回执补齐，未删除真实对象或故障演练。'
], [(J+'application/AipptService.java','public JSONObject deleteProject'),(J+'application/AipptService.java','public void reconcileAssets'),(J+'application/AssetCleanupService.java','public void sweep'),(J+'infrastructure/storage/AssetCompensationJournal.java','started'),(F+'features/projects/api.ts','useDeleteProject')], True)
point(31, [
    '原数据导入为显式prepare-import及中心CLI，原React列表/材料/编辑读同一正式结构。',
    '源快照hash、显式旧owner→sys_user映射、完整对象根/manifest/version及apply门禁；不导入秘密或旧JWT。',
    '中心Schema已建16表不等于导入授权；CLI默认计划，不启动Spring或连接数据库。',
    '准备器转稳定ref/资产映射并逐字段核账，中心再核布局/块/时间/签名；1–40正式页不套生成5–20。',
    '逐项目事务+批次checkpoint，staging对象先journal再正式提交；保原ISO时间、备注、锁定、树、旧指纹。',
    '旧confirmed转draft需重确认；重放不覆盖新写入，rollback遇新增内容拒绝。真实旧快照/对象/owner未导入，属于本轮未验证范围。'
], [(W+'worker/migration.py','def prepare_import'),(J+'migration/AipptLegacyImportCli.java','public static Plan prepare'),(J+'migration/AipptLegacyImportCli.java','rollback'),(C+'schemas/import-manifest.schema.json','schema_version')])
point(32, [
    '生成页可自动配图，原图片块保alt、source及credit；失败后继续出页。',
    '中心任务image_enabled与Worker本机image_enabled同时显式开启，Provider凭据不能充当授权。',
    'Worker上传必须当前task/attempt/fencing租约，中心资产manifest校验归属/大小/mime/hash。',
    '原生成→Unsplash→占位顺序，支持兼容生图/百炼；SafeAsyncClient与PublicTransport拒内网/重定向并限制字节/DNS连接。',
    '返回中心私有图片URL及来源/署名；preview鉴权Blob，导出从受权快照读资产。',
    '坏图/超时降级，租约失效必须终止；未调用外部图源、付费模型或真实降级演练。'
], [(W+'images/pipeline.py','class ImagePipeline'),(W+'worker/handlers.py','async def _resolve_images'),(W+'worker/security.py','class SafeAsyncClient'),(W+'worker/transport.py','PublicTransport'),(J+'application/AipptService.java','private void validateManifest')])
point(33, [
    '原ImagePanel换PNG/JPEG/WebP，按原图块几何裁剪显示。',
    'slide:edit+image:upload、image块、页revision及8MiB/40MP解码限额，上传原File保留。',
    'image PUT暂存上传幂等，再edit命令指向正式资产；冲突立即释放未引用stage。',
    'Java ImageIO+WebP依赖核真实字节；Python原validate_image与PPTX图像构造保持。',
    '换图写url/source/credit/locked，串行页队列回读新revision；Blob仅展示不进正式IR。',
    '伪图片/损坏/超限/旧revision拒绝且保旧图；未上传真实图片或视觉核裁剪。'
], [(F+'features/deck/ImagePanel.tsx','ImagePanel'),(F+'features/deck/api.ts','useReplaceSlideImage'),(J+'controller/AipptController.java','public AjaxResult image'),(J+'application/AssetService.java','public static String validate'),(F+'render/usePrivateImage.ts','usePrivateImage')])
point(34, [
    '材料/图片/导出统一经中心私有接口读取，客户端不访问任意存储key或直接外链。',
    'assetId+本人归属+对应材料/导出权限；短ticket仍需会话、有限期且role权限，不能转匿名公开读。',
    'GET assets、download-ticket和export/download用no-store/nosniff、正规Content-Disposition及hash核对。',
    'Local UUID路径/符号链接/公开profile边界；COS HTTPS/Private ACL、受控aippt对象key。',
    '中心asset metadata权威，存储只持字节；React usePrivateImage持会话Blob，换会话释放旧私有图。',
    '他人资源/过期ticket/路径穿越拒绝；旧文件JSON错误回执本轮再加Token核验，未连接真实COS/对象目录。'
], [(J+'application/AssetService.java','validateTicket'),(J+'controller/AipptController.java','private ResponseEntity<byte[]> binary'),(J+'infrastructure/storage/LocalAipptStorage.java','private Path resolve'),(J+'infrastructure/storage/CosAipptStorage.java','CannedAccessControlList.Private'),(F+'api/client.ts','export async function requestBinary')], True)
point(35, [
    '原ExportDialog先等待页/主题保存，显示可定位错误/警告与字体估算。',
    'export:check读取质量；导出另需create/download，error阻断、warning须显式确认。',
    'qualityKey绑定deck/theme/input revision+catalog+font hash；quality任务同版本去重，旧结果不写当前缓存。',
    'Worker原run_export_check核结构/容量/边界/丢图/低清/重复/无来源数字/偏瘦空话及字体。',
    'issues/can_export/version_key写中心quality，React转换export_allowed/fonts_precise并定位页。',
    '报告未就绪/过期不能导出；警告不等于事实核验，未执行完整真实样本/质量修复。'
], [(F+'features/deck/ExportDialog.tsx','resolveCheckState'),(F+'features/deck/api.ts','useDeckQuality'),(J+'application/AipptService.java','private String qualityKey'),(W+'domain/export_check.py','run_export_check')])
point(36, [
    '原导出一次即下载入口生成原生PPTX，中文文件名通过正规响应头解析。',
    '全部页ready、deck/theme CAS、当前quality允许及警告确认，下载另核本人export归属。',
    '中心登记不可变snapshot/export/任务；Worker上传产物后中心validateManifest/verify/页数/hash核对。',
    '原八块、文本runs、表格、四图表内嵌XLSX、图片/形状与备注构造，verify按源Deck回读。',
    '回读成功才export.ready/asset_id，React先确认任务/制品状态再Binary→Blob→saveBlob；失败没有部分文件。',
    '严格数值工作簿修复字节与旧证据保留；无真实授权下载/Office编辑或字体视觉验收，旧WPS第16页失败仍保留。'
], [(W+'render/pptx.py','render_deck_to_pptx'),(W+'render/verify.py','def verify_pptx'),(W+'worker/handlers.py','async def export'),(J+'application/AipptService.java','case "export"'),(F+'features/deck/api.ts','useExportDeck')])
point(37, [
    '导出面板显示待确认导出的deck/theme版本及页/检查/shape/截断诊断。',
    '启动时正式版本和质量Key核对；下载按export快照和原hash，不把后续编辑伪装成旧文件内容。',
    '中心export保存snapshot_id/版本；complete要求passed、实际/预期页数匹配及正式资产hash。',
    'verify核画布、原生对象、内容/备注/边界和图表cache/XLSX，失败以有界verification_failure回传。',
    '失败task/export保存诊断，未产生ready下载资产；前端已接受任务保原id以确认未知下载结果。',
    '明确失败后可新尝试，失联不重复制品；旧快照制品保留版本。未注入坏PPTX/真实下载。'
], [(J+'application/AipptService.java','public JSONObject getExport'),(J+'application/TaskLeaseService.java','verificationFailure'),(F+'features/deck/api.ts','commandVersion'),(F+'features/deck/ExportDialog.tsx','function FailureNote'),(W+'render/verify.py','def verify_pptx')])
point(38, [
    '原画布/Filmstrip缩略图、ZoomControls保持原比例与160%等缩放入口。',
    'deck:query读取原IR，选页/项目变化独立；所有SlidePage保持保存注册。',
    '前端仅懒渲染未进入视口预览，后台页集合不删、不缩小正式页数。',
    'useSlideVisibility首次访问后持续保DOM，原SlideView/flexLayout共渲染器，几何算法未重写。',
    '离屏草稿、主题/页prop更新仍到已访问页；原缩略图和画布保持相同内容。',
    '项目切换及迟到observer拒绝旧缓存；未做当前40页真实性能、200%文字缩放和多视口操作，不能声称达p95阈值。'
], [(F+'features/deck/useSlideVisibility.ts','useSlideVisibility'),(F+'features/deck/SlidePage.tsx','registerSlideSaveHandlers'),(F+'features/deck/ZoomControls.tsx','ZoomControls'),(F+'render/SlideView.tsx','SlideView')])
point(39, [
    '原PresentMode从选中页放映，支持点击/箭头/空格/Page/HomeEnd/F/Esc及退出。',
    '只呈现ready页，无正式写副作用；呈现当前页缓存，持久化仍由原页保存队列完成，未承诺进入放映等待全部保存。',
    '放映消费原deck缓存，不发第二控制接口或修改正式position。',
    '原PresentMode边界/全屏失败处理，EditorWorkspace等待fullscreenchange后恢复原入口焦点。',
    '退出恢复原选页/项目及有效DOM，按project/token核焦点归属。',
    '拒全屏可继续普通放映，旧会话/已移除入口不强行聚焦；未实际按键或打开浏览器放映。'
], [(F+'features/deck/PresentMode.tsx','PresentMode'),(F+'features/deck/EditorWorkspace.tsx','const restoreFocus'),(F+'features/deck/EditorHeaderActions.tsx','onPresent')])
point(40, [
    '原ThemePanel内SamplePanel查看四主题/11布局及质量/专用导出，不冒充用户生成页。',
    'design:query读样例，导出另create/download；普通项目命令不能修改sample。',
    '中心登记私有样例并核catalog版本、完整页序及UUID→目录原页ID映射；专用导出再次校验。',
    '样例用同quality/export专业算法，候选不是第二catalog；前端版本从唯一manifest导入。',
    '质量issues映射正确目录页，主题切换只外观；原生导出依旧门禁/回读。',
    '版本/页关系异常明确拒绝保旧样例，未知导出保原command；未真实执行样例任务。'
], [(F+'features/design/SamplePanel.tsx','SamplePanel'),(F+'features/design/api.ts','sampleCatalogVersion'),(J+'application/AipptService.java','public JSONObject sampleExport'),(J+'application/AipptService.java','sampleSlideIds'),(C+'catalog-manifest.json','catalogVersion')])
point(41, [
    '停用、任务失败与通知降级从原业务提示和受权health查看，不启动第二后台。',
    '业务/Worker/Provider/image/cleanup及Redis环境各有开关；内部路径关闭时默认拒绝。',
    '共享dynamicDataSource/RedisConnectionFactory沿用；outbox事务登记、事务外有界发布，通知不持正式状态。',
    'Worker attempt/deadline/lease/fencing保护结果，目录/字体身份不符失败；Redis通知失败回退DB轮询。',
    '关闭Provider不影响已授权已有页编辑/质量/导出（仍需Worker）；关闭整个模块则接口不可用。',
    '16表已建仅证明结构；本轮不启用服务/权限/模型、不做故障演练。源制品/旧失败/恢复条件保留，真实运行仍未验证。'
], [(J+'application/RedisDeliveryService.java','RedisConnectionFactory'),(J+'application/AipptHealthService.java','snapshot'),(J+'application/TaskLeaseService.java','recoverExpired'),(W+'worker/runner.py','async def execute'),('ruoyi-backend/ruoyi-admin/src/main/resources/application-aippt.yml','enabled:')])

def source_link(path, symbol):
    p = ROOT/path
    content = p.read_text(encoding='utf-8-sig')
    matching = [(i, line) for i, line in enumerate(content.splitlines(),1) if symbol in line]
    if not matching:
        raise RuntimeError(f'引用符号未定位：{path} :: {symbol}')
    line = matching[0][0]
    return {'path':path,'line':line,'symbol':symbol,'sha256':H(p.read_bytes()),'bytes':p.stat().st_size}

def history_identity():
    data = {}
    java = json.loads((ROOT/'output/aippt-outline-source-warnings-20261003/source-freeze/metadata.json').read_text(encoding='utf-8-sig'))
    changes=[]
    for row in java['source_files']:
        p=ROOT/row['path']
        if not p.exists() or H(p.read_bytes()) != row['sha256']: changes.append(row['path'])
    data['Java180_baseline']={'metadata_files':len(java['source_files']),'changed_since_freeze':changes,'tests_rerun':False,'meaning':'历史180项只保原版本；本轮Java生产源码未修改，README事实更新单列。'}
    frozen=json.loads((ROOT/'output/aippt-react-chart-edit-20261003/source-freeze.json').read_text(encoding='utf-8-sig'))
    changes=[]
    for row in frozen['source_files']:
        p=ROOT/'frontend/aippt-web'/row['path']
        if not p.exists() or H(p.read_bytes()) != row['sha256']:changes.append(row['path'])
    data['React119_baseline']={'metadata_files':len(frozen['source_files']),'changed_since_freeze':changes,'tests_rerun':False,'meaning':'119只保15:05旧版本，不冒充本轮改动后的当前套件；本轮只类型编译/静态打包。'}
    wheel=ROOT/'python/aippt-worker/var/chart-verifier-fixes-20261003/dist/ruoyi_aippt_worker-0.1.0-py3-none-any.whl'
    sdist=wheel.with_name('ruoyi_aippt_worker-0.1.0.tar.gz')
    mismatches=[]; count=0
    with zipfile.ZipFile(wheel) as z, tarfile.open(sdist) as t:
        entries={n.split('/',1)[1]:n for n in t.getnames() if '/' in n}
        for p in sorted((ROOT/'python/aippt-worker/app').rglob('*.py')):
            key=p.relative_to(ROOT/'python/aippt-worker').as_posix(); b=p.read_bytes(); count+=1
            member=entries.get(key)
            if key not in z.namelist() or z.read(key)!=b or not member or t.extractfile(member).read()!=b:mismatches.append(key)
    data['Python576_baseline']={'app_files':count,'package_mismatches':mismatches,'wheel_sha256':H(wheel.read_bytes()),'sdist_sha256':H(sdist.read_bytes()),'tests_rerun':False,'meaning':'保历史576/3字体跳过，当前app字节与当时两包比较；未执行任何业务算法。'}
    artifacts=[('Java中心','output/aippt-outline-source-warnings-20261003/production-package/artifacts/ruoyi-aippt-3.9.2.jar'),('Java装配候选','output/aippt-outline-source-warnings-20261003/production-package/artifacts/ruoyi-admin.jar'),('旧React静态ZIP','output/aippt-react-chart-production-20261003/aippt-react-static.zip'),('旧管理端候选ZIP','output/aippt-react-chart-production-20261003/admin-with-aippt-react-static.zip')]
    data['preserved_artifacts']=[{'name':name,'path':path,'bytes':(ROOT/path).stat().st_size,'sha256':H((ROOT/path).read_bytes())} for name,path in artifacts]
    center=ROOT/artifacts[0][1]; admin=ROOT/artifacts[1][1]
    with zipfile.ZipFile(admin) as z:
        center_entries=[name for name in z.namelist() if name.endswith('/ruoyi-aippt-3.9.2.jar')]
        data['Java_embedded_center_match']=len(center_entries)==1 and z.read(center_entries[0])==center.read_bytes()
    return data

def deliver():
    frozen=json.loads((OUT/'构建前输入身份.json').read_text(encoding='utf-8'))
    changed=[r['path'] for r in frozen['files'] if H((ROOT/r['path']).read_bytes())!=r['sha256']]
    if changed:raise RuntimeError('构建后输入已变化：'+str(changed))
    source=OUT/'react-static-delivery-final'
    target=ROOT/'frontend/admin-web/public/aippt-web'
    backup=OUT/'同源旧原件-排序回退修复前'
    if target.resolve()!=Path(r'D:\2025Ai\26-05-23\java-workspace\frontend\admin-web\public\aippt-web') or source.resolve()!=OUT/'react-static-delivery-final':raise RuntimeError('同步绝对目标不符')
    for directory in [source,target]:
        if directory.is_symlink() or any(p.is_symlink() for p in directory.rglob('*')):raise RuntimeError('同步范围含链接')
    files=inventory(source); before=inventory(target)
    if not files or not (source/'index.html').exists():raise RuntimeError('缺React候选')
    if backup.exists():raise RuntimeError('旧原件备份已存在，拒绝覆盖')
    shutil.copytree(target,backup)
    if inventory(backup)!=before or inventory(target)!=before:raise RuntimeError('同步前原文变化')
    # 只更新本项目固定public目录，旧dist/admin-dist/Java/Worker均不覆盖。
    for row in sorted(files,key=lambda r:r['path']=='index.html'):
        dst=target/row['path']; dst.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(source/row['path'],dst)
    names={r['path'] for r in files}
    for row in before:
        if row['path'] not in names:
            dst=target/row['path']
            if dst.resolve().is_relative_to(target.resolve()) and H(dst.read_bytes())==row['sha256']:dst.unlink()
            else:raise RuntimeError('旧资源在同步中变化，保留并停止')
    after=inventory(target)
    if after!=files:raise RuntimeError('同步后字节不等')
    archive=OUT/'aippt-react-logic-static-final.zip'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
        for row in files:z.write(source/row['path'],row['path'])
    with zipfile.ZipFile(archive) as z:
        for row in files:
            if H(z.read(row['path']))!=row['sha256']:raise RuntimeError('ZIP与原件不等')
    save_json(OUT/'交付制品身份.json', {'checked_at':NOW(),'source':source.relative_to(ROOT).as_posix(),'target':target.relative_to(ROOT).as_posix(),'backup':backup.relative_to(ROOT).as_posix(),'files':files,'before':before,'source_still_frozen':True,'zip':archive.relative_to(ROOT).as_posix(),'zip_bytes':archive.stat().st_size,'zip_sha256':H(archive.read_bytes()),'published':False,'shared_dist_written':False,'tests_run':False})
    print(f'本项目同源原件及ZIP一致：{len(files)}文件；未发布')

def report():
    design=(ROOT/'文档/项目/AI演示文稿项目/完整迁移设计.md').read_text(encoding='utf-8-sig')
    names={int(n):title.strip() for n,title in re.findall(r'^\| (\d+)\. ([^|]+)\|',design,re.M)}
    if len(names)!=41 or len(POINTS)!=41:raise RuntimeError('业务点数量/设计来源不符')
    labels=['用户入口','参数、身份与权限','中心API与数据归属','任务与专业算法','结果保存与展示','错误、取消、重试与恢复']
    source_rows={}; rows=[]; md=[]; cards=[]
    for item in POINTS:
        refs=[source_link(*ref) for ref in item['refs']]
        for r in refs:source_rows[r['path']]=r
        status='实现后已静态核对' if item['fixed'] else '逻辑成立（源码范围）'
        rows.append({**item,'name':names[item['number']],'logic_conclusion':status,'runtime_conclusion':'本轮未执行；按用户要求保留未验证','source_refs':refs})
        parts=''.join(f'<dt>{E(label)}</dt><dd>{E(text)}</dd>' for label,text in zip(labels,item['chain']))
        links=' · '.join(f'<a href="{E(os.path.relpath(ROOT/r["path"],HERE.parent).replace(os.sep,"/"))}#L{r["line"]}">{E(Path(r["path"]).name)} 第{r["line"]}行</a>' for r in refs)
        cards.append(f'<article id="point-{item["number"]}" data-state="{"fixed" if item["fixed"] else "logic"}"><h2>{item["number"]}. {E(names[item["number"]])}</h2><p class="badge">{E(status)}</p><dl>{parts}</dl><p class="sources">代码与契约：{links}</p><p class="limit">真实业务结果：本轮未执行。外部Provider、Office、许可字体、旧数据/对象核账等相关条件未验证；不是业务通过或用户接受。</p></article>')
        md.append(f'## {item["number"]}. {names[item["number"]]}\n\n逻辑结论：{status}。本轮真实业务未执行。\n\n'+ '\n\n'.join(f'**{label}：** {text}' for label,text in zip(labels,item['chain']))+'\n\n代码/契约：\n\n'+'\n'.join(f'- [{r["path"]}：{r["line"]}]({os.path.relpath(ROOT/r["path"],HERE).replace(os.sep,"/")}#L{r["line"]})；定位 `{r["symbol"]}`。' for r in refs))
    # Python只读语法编译，不导入业务、不创建pyc，不调用算法。
    parsed=[]
    for p in sorted((ROOT/'python/aippt-worker/app').rglob('*.py')):
        ast.parse(p.read_text(encoding='utf-8-sig'),filename=str(p)); parsed.append(p.relative_to(ROOT).as_posix())
    history=history_identity()
    save_json(HERE/'历史版本与制品只读核对.json',history)
    save_json(HERE/'源码与契约对应.json',{'checked_at':NOW(),'design':'0.4','method':'逐段人工源码审读；脚本仅生成引用行号、SHA及报告，不执行业务测试','real_business_run':False,'points':rows,'cited_files':sorted(source_rows.values(),key=lambda r:r['path']),'python_syntax_only_files':parsed})
    fixes=[
        ('版本命令未知回执重试','原流程重新读新revision，原请求已落库时正文变化，自撞客户端409；旧结构回执与排序错误回退可能覆盖后续编辑。','现有client增加requestPrepared，同意图先重放原body/key；生成/取消/单页重试、页面结构/排序、提纲生成/确认和项目删除接入。结构回写保较新deck/页、主题及保存队列中的草稿；排序错误只回退页序，保留当前页内容及版本。'),
        ('确认并生成中间步骤','启动回执或最后GET失联后，重试可能重存提纲、主题或重启任务。','保步骤进度/已接受启动标识；未知期间暂停编辑及自动保存，原按钮区增加明确重试入口；启动已接受则只回读。'),
        ('迟到会话回执','旧logout finally及HTTP200文件JSON错误读取后可能清新账号Cookie。','logout只清原会话/已清空会话，二进制JSON解码后再次核原Token；204明确回执清未知槽位。'),
        ('项目状态展示','生成提纲即outline_ready却显示已确认；筛选提交仅Deck支持的partial，项目永无此状态。','标签改已有大纲，列表沿正式项目四状态；编辑器保partial计数与失败页，不改变中心项目状态模型。'),
        ('建表事实说明','旧README/记录仍称目标版本未知/未建表。','以已执行Schema CLI、MySQL8.0.46及16表记录更新现行说明，旧历史保留，不重执行DDL。')]
    fix_table=''.join(f'<tr><td>{E(name)}</td><td>{E(before)}</td><td>{E(after)}</td></tr>' for name,before,after in fixes)
    saved=OUT/'交付制品身份.json'; delivery=json.loads(saved.read_text(encoding='utf-8')) if saved.exists() else {}
    limits=[
        '本轮没有编写/执行单元、组件、集成、浏览器业务测试；没有启动Java/Worker/Vite服务、写真实样本或调用模型/微信/支付/Office，也没有故障演练。',
        '必要React类型编译、私有Vite打包、源码/包字节及文档链接核对已执行。Python仅ast语法解析；Java/Worker未因本轮重跑历史套件。',
        '原ry-vue已建16张受管表、版本MySQL8.0.46及applied/checksum来自已保存建表证据。本轮不重复连库或执行DDL；该事实不能证明真实事务/HTTP联机通过。',
        '本轮前端改变后的行为只得出静态逻辑结论，真实断网、重试、跨会话及页面操作未验证。119是15:05原React历史版，不能算本轮当前全量通过。',
        'Java180/Python576及3字体跳过按冻结字节限定沿用；旧Vue业务/浏览器证据不算原React完成，旧Office第16页新值未保存、另两图未执行仍保留。',
        '真实运行通道、Worker机器身份/私有存储、角色菜单、实际Provider与额度、许可字体、Office视觉和旧数据owner/对象核账均未验证；依本轮要求不立即索取账号或启用费用。',
        '管理他人数据需manage且显式管理入口；当前API严格本人私有。没有擅自扩权、授予权限、导入旧数据、Git动作、发布或上线就绪结论。',
        '未知回执正文/草稿保护沿原浏览器内存机制；关闭/刷新后的长期持久恢复、真实性能p95及字形精确性不据本报告承诺。'
    ]
    page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>AI演示文稿全链路逻辑验收报告</title><style>
    *{box-sizing:border-box}body{margin:0;background:#f1f4f7;color:#192c39;font:15px/1.75 system-ui,"Microsoft YaHei",sans-serif}main{max-width:1180px;margin:auto;padding:32px 22px}h1{font-size:28px;line-height:1.4}h2{font-size:20px;margin:0 0 10px}.intro,article,.summary{background:white;border:1px solid #d4dde4;border-radius:14px;padding:24px;margin-bottom:22px}article{scroll-margin-top:16px}.badge{display:inline-block;margin:0 0 16px;padding:4px 12px;background:#e6f2ed;color:#205e49;border-radius:8px}article[data-state="fixed"] .badge{background:#fff0d4;color:#7a5410}dl{display:grid;grid-template-columns:180px minmax(0,1fr);margin:0}dt,dd{padding:10px 0;border-top:1px solid #e8edef;margin:0}dt{font-weight:600}dd{padding-left:15px}.sources,.limit{font-size:13px;overflow-wrap:anywhere}.limit{padding:10px;background:#f5f7fa;border-radius:8px;color:#596977}a{color:#245e9a;overflow-wrap:anywhere}table{width:100%;border-collapse:collapse;font-size:14px}td,th{border:1px solid #dce4eb;text-align:left;vertical-align:top;padding:12px}.table-wrap{overflow:auto}.tools{display:flex;gap:12px;flex-wrap:wrap;margin:15px 0}input,select{padding:10px;border:1px solid #b8c8d5;border-radius:8px;font:inherit}input{flex:1;min-width:180px}nav{display:flex;flex-wrap:wrap;gap:8px;font-size:13px}nav a{padding:4px 8px;background:#edf3f8;border-radius:5px}.metric{font-size:18px;color:#24566a}code{overflow-wrap:anywhere}li{margin-bottom:7px}@media(max-width:650px){main{padding:16px 10px}.intro,article,.summary{padding:16px}dl{display:block}dt{padding-bottom:0}dd{border:0;padding:3px 0 12px}h1{font-size:23px}h2{font-size:18px}}@media print{body{background:white}main{max-width:none;padding:0}.tools{display:none}article{break-inside:avoid}a{color:#172f43}}
    </style><main>'''
    page+=f'<section class="intro"><h1>AI 演示文稿全链路逻辑验收报告</h1><p>日期：2026-10-03；设计0.4，原React路线；生成时点：{E(NOW())}。</p><p class="metric">41个业务点逐段审读 · 真实业务本轮未执行 · 用户接受未发生 · 未发布</p><p>本报告将用户入口、参数与身份权限、中心API和数据归属、任务/算法、结果保存展示及错误恢复分别还原。结论是源码逻辑范围，真实运行仍未验证；不以文件存在、接口数量、编译或历史测试数替代业务通过。</p><p>已发现确定缺陷先保修改前原文，再在现有hooks/API内局部完成。原页面/专业算法未重写，没有新增第二计划或长期状态。41个名称沿唯一设计第5节；实际状态仍由完整迁移实施记录维护。</p><p><a href="../../完整迁移设计.md">唯一设计0.4</a> · <a href="../../开发记录/完整迁移实施记录.md">唯一实施记录</a> · <a href="全链路逻辑核对/源码链路对应说明.md">源码链路对应说明</a> · <a href="全链路逻辑核对/历史版本与制品只读核对.json">历史版本核对</a></p></section>'
    page+=f'<section class="summary"><h2>确定缺陷及已完成修复</h2><div class="table-wrap"><table><thead><tr><th>范围</th><th>原问题</th><th>现有范围内修复</th></tr></thead><tbody>{fix_table}</tbody></table></div><p>上述运行行为未做真实操作或新测试，只完成静态审读和类型编译。修改前原文与现版本SHA见配套JSON，不把修复推断写成运行通过。</p></section>'
    page+='<section class="summary"><h2>技术交付与实际限制</h2><ul>'+''.join('<li>'+E(v)+'</li>' for v in limits)+'</ul>'
    if delivery:page+=f'<p>新React候选：{len(delivery["files"])}文件；静态ZIP {delivery["zip_bytes"]}字节，SHA256 <code>{delivery["zip_sha256"]}</code>。本项目public同源原件与ZIP逐字节一致；旧dist、共享admin dist和历史包保留，未部署。</p>'
    page+='</section><section class="summary"><h2>逐项查看</h2><div class="tools"><input id="search" aria-label="搜索业务点与链路" placeholder="搜索业务名称、权限、接口或错误"><select id="filter" aria-label="筛选逻辑结论"><option value="">全部逻辑结论</option><option value="fixed">本轮有修复并静态核对</option><option value="logic">沿现有源码逻辑成立</option></select></div><nav>'+''.join(f'<a href="#point-{n}">{n}. {E(names[n])}</a>' for n in range(1,42))+'</nav></section>'
    page+=''.join(cards)+'''<p>来源与结论仅适用于本报告配套源码哈希；后续修改需按实际影响定向再核。报告不新增数据/权限/费用/发布授权。</p></main><script>const q=document.getElementById('search'),f=document.getElementById('filter');function update(){const term=q.value.trim().toLowerCase();document.querySelectorAll('article').forEach(a=>a.hidden=!!((f.value&&a.dataset.state!==f.value)||(term&&!a.textContent.toLowerCase().includes(term))))}q.addEventListener('input',update);f.addEventListener('change',update);</script></html>'''
    target=HERE.parent/'全链路逻辑验收报告.html';target.write_text(page,encoding='utf-8')
    (HERE/'源码链路对应说明.md').write_text('# AI演示文稿源码链路对应说明\n\n日期：2026-10-03；范围：设计0.4原React的41个业务点。逐段逻辑审读，真实业务未执行、用户接受未发生、未发布。名称从唯一设计第5节读取，实际状态维护在唯一实施记录，本文件只作本次源码证据。配套JSON保存真实行号与源码SHA。\n\n'+'\n\n'.join(md),encoding='utf-8')
    class Reader(HTMLParser):
        def __init__(self):super().__init__();self.articles=0;self.links=[]
        def handle_starttag(self,tag,attrs):
            attrs=dict(attrs)
            if tag=='article':self.articles+=1
            if tag=='a' and attrs.get('href'):self.links.append(attrs['href'])
    reader=Reader();reader.feed(page)
    broken=[]
    for link in reader.links:
        path=link.split('#')[0]
        if path and not (target.parent/path).exists():broken.append(link)
    if reader.articles!=41 or broken:raise RuntimeError('报告结构/链接不符：'+str(broken))
    save_json(HERE/'报告结构与链接核对.json',{'checked_at':NOW(),'articles':reader.articles,'chain_sections':sum(len(p['chain']) for p in POINTS),'local_links':len(reader.links),'missing_links':broken,'cited_source_files':len(source_rows),'html_sha256':H(target.read_bytes()),'scope':'静态文档结构/引用核对，不是浏览器测试或业务验收'})
    print(f'中文逻辑报告已生成：41点/246段/{len(source_rows)}个源码与契约文件；本轮无测试、无业务运行')

if __name__=='__main__':
    if sys.argv[1:]==['freeze']:freeze()
    elif sys.argv[1:]==['deliver']:deliver()
    elif sys.argv[1:]==['report']:report()
    else:raise SystemExit('仅支持freeze/deliver/report，均无网络或业务执行')
