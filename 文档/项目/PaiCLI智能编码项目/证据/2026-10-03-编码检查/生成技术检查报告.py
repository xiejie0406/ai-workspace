"""从本轮保存的执行结果生成可查看报告；业务验收和用户接受单独记录。"""
import html
import json
from pathlib import Path
from datetime import datetime
import xml.etree.ElementTree as ET

evidence=Path(__file__).resolve().parent
root=evidence.parents[4]
native=root/'ruoyi-backend/ruoyi-paicli'
source=json.loads((evidence/'源码完整性检查.json').read_text(encoding='utf-8-sig'))
final_path=evidence/'原生最终检查.json'
final=json.loads(final_path.read_text(encoding='utf-8-sig')) if final_path.exists() else None
final_section=''
if final:
    final_rows=[]
    for run in final['runs']:
        name=f'<a href="{html.escape(run["log"],quote=True)}">{html.escape(run["name"])}</a>'
        final_rows.append('<tr><td>'+name+'</td>'+''.join(f'<td>{html.escape(str(run[key]))}</td>' for key in ['tests','failures','errors','skipped','scope'])+'</tr>')
    package_items=''.join(f'<li>{html.escape(item)}</li>' for item in final['packageChecks'])
    package_link=html.escape(final['packageEvidence'],quote=True)
    final_section=f'''<section><h2>最终源码定向复验与可执行包</h2><p>{html.escape(final['snapshotDescription'])}。各次定向运行范围不同，不能相加成一次完整回归；沿用基线和原失败仍在下表及实施记录保留。</p><table><thead><tr><th>检查</th><th>测试数</th><th>失败</th><th>错误</th><th>跳过</th><th>可证明范围</th></tr></thead><tbody>{''.join(final_rows)}</tbody></table><ul>{package_items}</ul><p><a href="原生最终检查.json">最终原生检查、日志和摘要</a> · <a href="{package_link}">可执行包SHA-256及装配证据</a>；测试和运行使用JDK21，目标字节码Java17，实际Java17运行时尚未复验。</p></section>'''
rows=[]
for module in ['paicli-core','paicli-runner','paicli-cli','paicli-eval','paicli-api']:
    sums={key:0 for key in ['tests','failures','errors','skipped']}
    files=list((native/module/'target/surefire-reports').glob('TEST-*.xml'))
    for file in files:
        suite=ET.parse(file).getroot()
        for key in sums:sums[key]+=int(suite.attrib.get(key,0))
    status='尚无保存报告' if not files else '未通过' if sums['failures'] or sums['errors'] else '保存报告无失败'
    rows.append(f'<tr><td>{module}</td><td>{sums["tests"]}</td><td>{sums["failures"]}</td><td>{sums["errors"]}</td><td>{sums["skipped"]}</td><td>{status}</td></tr>')
scenes=[('编码工作台','workbench.png','沿用基线：合法模型、会话状态与事件游标。'),('发送后的排队反馈','workbench-sent.png','沿用基线：请求携版本和幂等键；排队后禁重复发送。'),('后台任务','tasks.png','沿用基线：待核对保留，未显示为重新执行或已撤销。'),('长期记忆','knowledge-memory.png','本次受影响项复验：已核实与待核实及作用范围，中文来源。'),('供应商与完整模型','connections-models.png','本次受影响项复验：完整模型ID、能力和密钥未配置状态；未触发真实模型刷新。'),('审计与评测','audit.png','沿用基线：空评测没有虚构成绩。'),('1024像素布局','workbench-1024.png','沿用基线：无整页横向溢出。'),('异步知识结果与缺少元数据','knowledge-query-graph.png','本次新增检查：先等待PENDING回执再展示结果，缺少行号和哈希明确未上报。'),('Windows隔离限制','connections-sandbox-windows.png','本次新增检查：无核验提交按钮及写请求；保留命令执行不可用。')]
scenes.extend([('历史导入只读','workbench-historical.png','本次新增检查：历史会话无当前Runner，发送、文本/图片与上下文写操作禁用，未发送POST。'),('从历史新建当前会话','workbench-historical-new.png','本次新增检查：新建弹窗采用当前在线Runner和启用模型，不恢复旧任务或批准。')])
scenes.extend([('STDIO 托管引用登记','connections-mcp-stdio.png','本次新增检查：环境凭据仅接受当前工作区引用，不填写原值；只打开与取消。'),('HTTP 托管引用编辑','connections-mcp-http.png','本次新增检查：HTTPS地址、请求头引用与普通协议头正确展示；19个GET请求，无配置写入或连接。')])
cards=''.join(f'<article><h3>{index}. {name}</h3><p>{explanation}</p><a href="{filename}"><img loading="lazy" src="{filename}" alt="{name}实际fixture截图"></a></article>' for index,(name,filename,explanation) in enumerate(scenes,1))
report=f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>PaiCLI 编码技术检查报告</title>
<style>body{{font:16px/1.65 system-ui,"Microsoft YaHei",sans-serif;color:#233047;background:#f4f6fa;margin:0}}main{{max-width:1180px;margin:auto;padding:28px}}h1{{font-size:28px}}h2{{margin-top:32px}}a{{color:#2366b4}}section,article{{background:white;border:1px solid #dce3ec;border-radius:8px;padding:20px;margin:16px 0}}.notice{{background:#fff7da;border-color:#e5cb71}}table{{border-collapse:collapse;width:100%}}th,td{{padding:10px;border-bottom:1px solid #dce3ec;text-align:left}}img{{display:block;max-width:100%;border:1px solid #dce3ec}}small{{color:#68758b}}code{{overflow-wrap:anywhere}}</style>
<style>th:nth-child(n+2):nth-child(-n+5){{white-space:nowrap;min-width:48px}}</style>
<main><h1>PaiCLI 编码技术检查报告</h1><p>生成时间：{datetime.now().isoformat(timespec='seconds')}；版本范围：本工作区未提交实现。实际进展与命令以<a href="../../开发记录/2026-10-03-完整迁移实施记录.md">唯一实施记录</a>为准。</p>
<section class="notice"><strong>本报告区分技术证据、业务结果和用户接受。</strong><p>页面采用受控接口fixture，真实MySQL、Runner、供应商、微信、MCP及正式评测尚未联机验收；业务通过数为0，用户接受未记录。技术测试及截图不能证明已经上线或可以发布。</p></section>
<section><h2>源文件与实现检查</h2><p>参考基线 {source['sourceBaselineCount']} 个文件，迁移映射 {source['migrationEntryCount']} 条；源文件未改：{str(source['sourceUnchanged']).lower()}；目标缺失或摘要不符：{len(source['failures'])}。目标适配文件数量仅作差异线索，不作为能力通过数。</p><p><a href="源码完整性检查.json">只读摘要核对结果</a> · <a href="页面技术检查清单.md">操作前保存的页面技术清单</a> · <a href="页面技术检查.json">五页技术结果</a> · <a href="工作台定向复验.json">工作台定向复验</a> · <a href="记忆与模型定向检查.json">记忆与模型页签检查</a></p></section>
{final_section}
<section><h2>原工作区保存的Java技术报告</h2><p>下表汇总原工作区各模块保存的Surefire XML，包含不同时间的完整回归和定向复验，不包括独立最终快照报告，并非一次完整执行。运行范围、原失败、修复后复验和未执行项由实施记录解释，不能以这些计数代替完整业务通过数。若仍有失败或尚无报告，保留该事实。</p><table><thead><tr><th>模块</th><th>保存测试数</th><th>失败</th><th>错误</th><th>跳过</th><th>报告状态</th></tr></thead><tbody>{''.join(rows)}</tbody></table></section>
<section><h2>页面实际检查</h2><p>本报告展示的13张截图保存后均已回读。共享全局样式、中文组件与权限指令实际装配；外层生产若依Layout和菜单授权不在此fixture的验收范围。初次全局样式修复基线保留于本目录子目录，当前图为受影响项复验后的证据。</p><p>历史页首次观察到部分写按钮未禁用，该失败事实保留；复验覆盖了首次截图，原失败图缺失，不能声称该原图已保存。当前历史两图仅证明修复后表现，详情见页面清单。</p></section>{cards}
<section><h2>真实环境待准备</h2><p>按<a href="../../完整迁移设计.md">完整迁移设计第5节</a>准备真实业务清单、现有数据库迁移审核、受控密钥引用、设备绑定、有效命令隔离、模型/MCP/微信和正式评测环境，再执行真实联机验收。当前不把这些未执行项改记通过。</p></section></main></html>'''
(evidence/'verification-report.html').write_text(report,encoding='utf-8')
print(json.dumps({'report':str(evidence/'verification-report.html'),'javaModules':len(rows),'screenshots':len(scenes),'businessPassed':0},ensure_ascii=False))
