"""将已完成的静态核对与私有构建事实汇入中文报告，不执行业务或测试。"""
from pathlib import Path
import html
import json

evidence = Path(__file__).resolve().parent
artifacts = json.loads((evidence / '私有制品核对.json').read_text(encoding='utf-8'))
scope = json.loads((evidence / '改动范围核对.json').read_text(encoding='utf-8'))
backend = (evidence / '后端私有构建交付.log').read_text(encoding='utf-8', errors='replace')
frontend = (evidence / '前端私有构建交付.log').read_text(encoding='utf-8', errors='replace')
if 'BUILD SUCCESS' not in backend or 'built in' not in frontend or not artifacts['moduleEmbeddedMatch'] or artifacts['sourceChangedSinceSnapshot']:
    raise SystemExit('最终构建或源码一致性证据未完整，不生成完成报告')
rows = [
 ('配置保存与激活', '原模型供应商页面 → 原provider:edit权限 → ProviderService白名单、版本及LLM事务 → 原ps_provider', '只接受LLM/Sub2API显式协议与预算。初次保存禁用；验证资料及激活门禁只由服务端计算。修改或密钥变化失效，显式激活复核。'),
 ('文字、流式与工具适配', 'ModelGateway → Sub2ApiTextAdapter → ResponsesCodec / Chat适配 → ControlledHttp', 'Responses存储关闭、无上游会话状态；正文/工具/加密reasoning有界。关联ID、工具JSON/schema及合法终态校验后归一化；失败不换协议、模型或供应商。'),
 ('问答、摘要与引用核验', 'ChatService创建request_json冻结 → 多轮规划 / generate_summary / CitationService → 原账本', '同问答后续回合继承原冻结配置。旧排队缺快照且活动模型已为Sub2API，在外呼前拒绝。WS客户端保留字段拒绝，平台会话独立由服务端传入。'),
 ('文档增强与冲突辅助', '新JobService策略标记 → 原TEXT_PROVIDER checkpoint → DocumentIntelligenceService / GovernanceService', '原开关启用且实际需要文字处理时首次冻结；续行使用原快照，旧作业不静默迁移。原文失败回退和正式冲突决策保持。'),
 ('评测生成与裁判', 'GovernanceService创建评测config.provider → 原任务恢复 → 两文字调用入口', '沿原评测快照冻结密钥版本，各次外呼复核；未启动任何评测、上传数据或创建真实任务。'),
 ('用量及未知结果', 'ModelCallService.begin → 原Quota预占 → 协议解码 → 完成 / STOPPED / UNCERTAIN', '沿原账本。完整结果重放；可靠供应商用量失败可结算STOPPED；未知结果保留待核对，缺用量不伪写实际0。原账本源码未改。'),
 ('合成验证与中断恢复', '原provider:test权限+Idempotency-Key → 服务端claim CAS → 三个固定子operation', '最多三次、每次最多128输出token。六分钟内同Key回读；到期仅COMPLETED或尚无子调用可沿原Key继续，已完成只重放。未知调用先核账；核账后显式新预算才开新Key。旧执行权不得覆盖新结果。'),
 ('非文字能力及兼容', '原Embedding / Rerank / OCR / 图片 / 索引边界及默认配置', 'Embedding及Rerank Gateway方法、原调用账本、WS业务schema、Kafka事件schema和默认配置值与修改前一致。未改索引维度、重建或向量化；协议SSE共用运输变化仍需后续运行回归。'),
]
body_rows = ''.join(f'<tr><td>{i}. {html.escape(title)}</td><td>{html.escape(chain)}</td><td>{html.escape(finding)}</td><td><span class="badge">静态逻辑复核</span><br>未运行</td></tr>' for i,(title,chain,finding) in enumerate(rows,1))
code_files = scope['changedExistingFiles'] + scope['newMainFiles']
links = ''.join(f'<li><a href="../../../../../{html.escape(path, quote=True)}">{html.escape(path)}</a></li>' for path in code_files)
shared = ''.join(f'<li>{html.escape(path)}：前后摘要{("一致" if value["unchanged"] else "已变化（须区分并行任务）")}</li>' for path,value in artifacts['sharedArtifacts'].items())
document = f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>PaiSmart Sub2API文字模型离线实施核对报告</title>
<style>body{{margin:0;background:#f2f5f9;color:#172c42;font:16px/1.75 "Microsoft YaHei",sans-serif}}main{{max-width:1240px;margin:auto;padding:36px 30px}}h1{{font-size:28px;margin:0 0 12px}}h2{{font-size:21px;margin-top:30px}}p{{margin:12px 0}}.intro,section{{background:white;border:1px solid #dce4ef;border-radius:10px;padding:22px;margin:20px 0}}.badge{{display:inline-block;background:#e4f0fa;color:#225982;border-radius:4px;padding:2px 8px;font-size:13px}}.scope{{background:#fff3d8;border-left:4px solid #ba841b;padding:14px}}.table{{overflow:auto}}table{{border-collapse:collapse;width:100%;min-width:900px}}td,th{{border:1px solid #dae3ee;padding:12px;text-align:left;vertical-align:top}}th{{background:#edf3fa}}td:first-child{{width:150px}}td:last-child{{width:125px}}a{{color:#12639a;overflow-wrap:anywhere}}code{{overflow-wrap:anywhere}}li{{margin:6px 0}}@media(max-width:700px){{main{{padding:16px}}h1{{font-size:23px}}}}</style>
<main><h1>PaiSmart Sub2API文字模型离线实施核对报告</h1><p>2026-10-04 · 来源：已批准实现文档及本轮离线授权 · 执行：主智能体与三个子智能体</p>
<div class="scope">本报告记录源码逻辑、类型、静态契约与私有编译结果。真实API、页面业务、并发运行、数据库写入、运行切换、用户接受及发布均未执行。未编写或运行单元、组件、集成测试。</div>
<section><h2>1. 本轮结果与前提</h2><p>完成仅文字模型的两协议配置、适配、工具回灌、冻结版本、验证门禁和恢复实现。仅确认原MySQL/Redis在运行；MinIO、Kafka、ES、完整模型配置及实际实例兼容尚未就绪。用户明确允许离线实施，未据此启用任何外呼或业务开关。</p><p>实现与实施事实分别沿<a href="../../Sub2API文字模型接入实现文档.md">原实现文档</a>、<a href="../../完整迁移设计.md">原项目唯一实施记录第6.6节</a>维护；本附录只保存本轮检查证据。</p></section>
<section><h2>2. 中文业务链路核对</h2><p>下列结论来自源码及调用链，不能替代实际协议、费用、权限或业务运行结果。</p><div class="table"><table><thead><tr><th>业务点</th><th>核查链路</th><th>实现与边界</th><th>结果范围</th></tr></thead><tbody>{body_rows}</tbody></table></div></section>
<section><h2>3. 实际技术检查</h2><ul><li>最终<a href="前端类型检查交付.log">typecheck:paismart</a>通过。首轮幂等键类型推断错误及修复日志保留，不计入业务通过。</li><li><a href="静态契约交付核对.log">静态契约核对</a>通过：108 HTTP操作、1041本地引用、97前端路径变体；OpenAPI规范及既有WS/Kafka样例仅作schema静态匹配。</li><li><a href="后端私有构建交付.log">后端23模块admin装配私有构建</a>通过，离线Maven、JDK21、source/target17；Java未复制或编译test源码，全部测试显式跳过。</li><li><a href="前端私有构建交付.log">前端私有生产构建</a>通过，输出到独立final目录；未安装依赖或启动服务。原输出目录及分块/第三方资源构建警告保留。</li><li><a href="私有制品核对.json">私有制品核对</a>：当前相关源码与快照一致，普通模块与admin内嵌PaiSmart模块SHA256一致，新增两适配类及默认关闭配置存在。</li><li><a href="改动范围核对.json">非文字与旧账本静态比对</a>通过，未用其声明真实回归通过。</li></ul><p>首轮构建遗漏共享装配的静态契约资源已补齐并重新装配；首次与交付日志均保留。所有产物为本轮私有技术检查制品，未安装或切换到运行环境。</p><ul>{shared}</ul><p>制品：<code>{html.escape(artifacts['admin']['path'])}</code>；前端：<code>{html.escape(artifacts['frontend']['index'])}</code>。</p></section>
<section><h2>4. 自审发现与修正</h2><p>已修复JSON回读整数类型造成版本绑定误冲突、验证结果并发覆盖、跨管理员旧键误触发收费、非文字停用版本变化、WS平台会话与保留字段冲突，以及验证登记后进程中断无法恢复。修复后做定向源码复核和对应类型/编译检查；没有执行协议、并发或故障演练。</p><p>协议实例、模型ID、分组、Bearer认证、工具及用量行为仍需后续明确环境和费用范围后验证；静态实现及编译成功不证明API可用。</p></section>
<section><h2>5. 修改范围及恢复</h2><p>修改前90份当前工作区原文及摘要已保存在<a href="修改前源码摘要.csv">原文摘要</a>对应的修改前原文目录。恢复应逐文件比对本次差异，保留后来与其他项目修改，不从Git HEAD或整份备份覆盖当前内容。本轮无Git暂存、提交、推送、分支或发布。</p><details><summary>查看本轮已有文件变化与新增适配源码</summary><ul>{links}</ul></details></section>
</main></html>'''
(evidence / '实施核对报告.html').write_text(document, encoding='utf-8')
print('已生成中文静态实施及私有构建报告；未执行业务验收。')
