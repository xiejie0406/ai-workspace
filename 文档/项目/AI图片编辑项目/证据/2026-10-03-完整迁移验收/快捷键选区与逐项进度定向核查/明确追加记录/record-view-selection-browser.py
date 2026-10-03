"""保存主执行者已完成的真实IAB技术场景；不执行浏览器、正式API或测试。"""
from pathlib import Path
from datetime import datetime, UTC
import hashlib
import json

ROOT = Path(__file__).resolve().parents[2]
folder = ROOT / '.runtime/aipicture/view-selection-browser'
out = folder / 'browser-record.json'
assert not out.exists()
def row(p):
    return {'path': str(p), 'bytes': p.stat().st_size,
            'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}

names = {
 'keyboard-before-body-focus-stays-143.jpg': '原库辅助截图，缩放数字未在图内展开，不单独作失败证明',
 'keyboard-before-visible-fit-key-ignored-100.jpg': '原库画布点击后按0仍100%，可见失败',
 'point-before-failure-retains-preview.jpg': '原库点选503后残留一个预览标记',
 'brush-before-failure-retains-preview.jpg': '原库笔刷503后残留预览线',
 'undo-before-delete-failure-moves-history.jpg': '原库DELETE失败仍移动历史游标',
 'undo-before-partial-delete-keeps-stale-mask.jpg': '原库DELETE成功而回放POST失败，仍显示旧选区',
 'keyboard-after-real-canvas-key-one-100.jpg': '749辅助截图，实际DOM缩放100，图内数字未展开，不单独作完整视觉证明',
 'keyboard-after-visible-100-and-input-isolation.jpg': '749画布1键显示100，输入框输入1保持原缩放',
 'keyboard-after-visible-fit-143.jpg': '749画布0键显示适合143%',
 'point-after-failure-clears-preview.jpg': '749点选失败立即清预览及提示原失败',
 'brush-after-failure-clears-preview.jpg': '749笔刷失败立即清预览',
 'brush-after-escape-keeps-preview-cleared.jpg': '749按Escape退出选区后仍无残留',
 'undo-after-delete-failure-keeps-history.jpg': '749DELETE失败回读正式选区且游标不前移',
 'undo-after-explicit-retry-clears-formal-mask.jpg': '749用户明确再撤销才成功清正式选区，无额外POST',
 'undo-after-partial-delete-reads-empty-mask.jpg': '749部分成功后回读空选区，不显示旧遮罩',
 'undo-after-partial-delete-explicit-retry-one-marker.jpg': '749用户明确重试回放一个标记，重做可用',
 'point-after-failure-pending-get-clears-preview-now.jpg': '749等待GET时已清预览，处理提示仍错误写识别；保留该失败',
 'point-after-pending-get-released-no-extra-post.jpg': '749释放GET后恢复，未多POST；仅机制基线',
 'point-final-pending-get-processing-caption.jpg': '011当前等待GET提示正在处理选区，失败可见且无预览',
 'point-final-pending-get-restored-once.jpg': '011当前释放GET后可再次点选，POST仍1、GET2',
}
assert {p.name for p in folder.glob('*.jpg')} == set(names)
images = [{**row(folder / name), 'observation': note,
           'saved_format': 'JPEG', 'disk_readback_by_root': True}
          for name, note in names.items()]
cases = [
 {'name': '真实画布快捷键与输入隔离', 'stage': '749未受影响沿用',
  'before': 'BODY焦点时1/0未生效；原100%按0仍100%',
  'after': '点击实际画布focus=图片编辑画布；1=100%、0=143%、加号=125%；输入框1只输入字符并保持125%',
  'limitation': '另一实例及contenteditable隔离来自组件检查，本次没有双实例真实IAB验收'},
 {'name': '点选失败立即清理', 'stage': '749未受影响沿用',
  'before': '503保留1标记', 'after': '失败提示和清标记，不等GET完成；正式选区空'},
 {'name': '笔刷失败和退出清理', 'stage': '749未受影响沿用',
  'before': '503残留线', 'after': '立即清线，Escape后仍清理'},
 {'name': '撤销DELETE失败保留历史', 'stage': '749未受影响沿用',
  'before': '正式标记1但undo=false/redo=true',
  'after': '正式标记1且undo=true/redo=false，POST1/DELETE1/GET3',
  'explicit_retry': '用户再撤销DELETE2，正式空、undo=false/redo=true；POST仍1'},
 {'name': '部分清除后回放POST失败校准', 'stage': '749未受影响沿用',
  'before': '正式空而UI旧遮罩/2标记/可清除仍留',
  'after': 'GET4正式空且UI清理，undo=true/redo=false/clear=false，POST3含两次原选择及一次失败回放',
  'explicit_retry': '用户再撤销POST4/DELETE2/GET5，正式1标记/redo=true'},
 {'name': '失败后GET等待的真实提示和恢复', 'stage': '011受影响新执行',
  'before_caption': '749已清预览，但仍提示正在识别选区，实际POST已失败',
  'after': '011显示正在处理选区，识别提示0/处理提示1；失败可见、预览无、busy=true；POST1/GET2',
  'explicit_release': '用户仅释放技术GET，点选入口恢复、正常提示，POST仍1/GET2',
  'caption_only_change': '749→011只有同字节长度的一处提示字符串，独立复核另存'},
]
harnesses = [row(ROOT / '.runtime/aipicture/browser-check' / name)
             for name in ['view-selection.html', 'view-selection-after.html', 'view-selection-caption-final.html']]
record = {
 'recorded_at': datetime.now(UTC).isoformat(), 'scope': __doc__,
 'actual_browser': {'browser': 'Codex内置浏览器', 'browser_id': '2', 'tab_id': '7',
                   'final_url': 'http://127.0.0.1:8416/view-selection-caption-final.html?scenario=selection-preview-failure'},
 'cases': cases, 'unique_cases': 6, 'current_caption_cases_newly_executed': 1,
 'unchanged_mechanism_cases_carried_from_749': 5,
 'before_bundle_sha256': 'aff4180ee55d608678a99d3f9fd543a8718887d5a038eb35d2538552fcfb9473',
 'functional_bundle_sha256': '74907cbd5e771cc7b6ac15766a35f74808f66aee648dec838dc92dfaf8b3d216',
 'current_bundle_sha256': '01104508f0792232d1701a5edf299848d58f3f7b7bd71f60c189b6f640c74e5f',
 'images': images, 'harnesses': harnesses,
 'actual_actions': '原生点击、按键、输入及可见技术开关；不以隐藏状态修改模拟用户动作',
 'technical_responses': '内存正式状态fixture及本地静态图片，不连接正式业务API；预热prepare不计选区POST',
 'technical_mask_is_not_quality_evidence': True,
 'formal_business_http_requests': 0, 'database_writes': 0,
 'model_or_provider_calls': 0, 'formal_acceptance_cases_passed': 0,
 'invalid_or_auxiliary_screenshots_retained': True,
 'html_report_render_or_filter_verified': False,
}
with out.open('x', encoding='utf-8', newline='\n') as f:
    json.dump(record, f, ensure_ascii=False, indent=2); f.write('\n')
print(json.dumps({'output': str(out), 'unique_cases': 6, 'new_current_caption': 1,
                  'carried_mechanism': 5, 'jpeg_readbacks': len(images)}, ensure_ascii=False))
