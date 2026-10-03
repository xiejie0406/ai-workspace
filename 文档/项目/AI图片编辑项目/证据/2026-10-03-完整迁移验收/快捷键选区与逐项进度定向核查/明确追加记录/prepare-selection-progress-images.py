"""只建实际截图来源与中文保存位置清单，图片由明确collector一次收集。"""
from pathlib import Path
import hashlib, json
ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / '文档/项目/AI图片编辑项目/证据/2026-10-03-完整迁移验收/快捷键选区与逐项进度定向核查/实际截图'
names = [
 ('view-selection-browser', 'keyboard-before-body-focus-stays-143.jpg', '快捷键-修前辅助缩放未展开'),
 ('view-selection-browser', 'keyboard-before-visible-fit-key-ignored-100.jpg', '快捷键-修前适合键未生效'),
 ('view-selection-browser', 'point-before-failure-retains-preview.jpg', '点选-修前失败残留标记'),
 ('view-selection-browser', 'brush-before-failure-retains-preview.jpg', '笔刷-修前失败残留线'),
 ('view-selection-browser', 'undo-before-delete-failure-moves-history.jpg', '撤销-修前清除失败仍移动游标'),
 ('view-selection-browser', 'undo-before-partial-delete-keeps-stale-mask.jpg', '回放-修前部分清除仍留旧选区'),
 ('view-selection-browser', 'keyboard-after-real-canvas-key-one-100.jpg', '快捷键-修后辅助缩放未展开'),
 ('view-selection-browser', 'keyboard-after-visible-100-and-input-isolation.jpg', '快捷键-修后原像素与输入隔离'),
 ('view-selection-browser', 'keyboard-after-visible-fit-143.jpg', '快捷键-修后适合显示143'),
 ('view-selection-browser', 'point-after-failure-clears-preview.jpg', '点选-修后失败立即清预览'),
 ('view-selection-browser', 'brush-after-failure-clears-preview.jpg', '笔刷-修后失败立即清预览'),
 ('view-selection-browser', 'brush-after-escape-keeps-preview-cleared.jpg', '笔刷-修后退出保持清理'),
 ('view-selection-browser', 'undo-after-delete-failure-keeps-history.jpg', '撤销-修后清除失败保留历史'),
 ('view-selection-browser', 'undo-after-explicit-retry-clears-formal-mask.jpg', '撤销-修后明确重试才清除'),
 ('view-selection-browser', 'undo-after-partial-delete-reads-empty-mask.jpg', '回放-修后部分清除回读空选区'),
 ('view-selection-browser', 'undo-after-partial-delete-explicit-retry-one-marker.jpg', '回放-修后明确重试单标记'),
 ('view-selection-browser', 'point-after-failure-pending-get-clears-preview-now.jpg', '点选-中间版等待回读仍误写识别'),
 ('view-selection-browser', 'point-after-pending-get-released-no-extra-post.jpg', '点选-中间版释放回读未多提交'),
 ('view-selection-browser', 'point-final-pending-get-processing-caption.jpg', '点选-最终版等待回读显示处理中'),
 ('view-selection-browser', 'point-final-pending-get-restored-once.jpg', '点选-最终版恢复且未多提交'),
 ('report-read-browser', 'report-before-stale-overwrite.jpg', '评测-修前旧回复覆盖新报告'),
 ('report-read-browser', 'report-before-failure-loading.jpg', '评测-修前辅助未看到弹窗'),
 ('report-read-browser', 'report-before-C-visible-stuck-loading.jpg', '评测-修前失败一直读取'),
 ('report-read-browser', 'report-after-late-A-keeps-B.jpg', '评测-首次技术开关关闭弹窗不计通过'),
 ('report-read-browser', 'report-after-neutral-A-keeps-B.jpg', '评测-修后迟到旧回复不覆盖'),
 ('report-read-browser', 'report-after-C-failure-error-and-retry.jpg', '评测-修后失败可见及重新读取'),
 ('report-read-browser', 'report-after-C-explicit-retry-restored.jpg', '评测-修后明确重读成功可下载'),
]
images = []
for number, (folder, name, meaning) in enumerate(names, 1):
 p = ROOT / '.runtime/aipicture' / folder / name
 images.append({'number': number, 'meaning': meaning, 'source': str(p.relative_to(ROOT)),
                'path': str((DEST / (f'{number:02d}-' + meaning + '.jpg')).relative_to(ROOT)),
                'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest(),
                'actual_format': 'JPEG', 'read_back_by_root': True})
out = ROOT / '.runtime/aipicture/selection-progress-images.json'
with out.open('x', encoding='utf-8') as f:
 json.dump({'scope': __doc__, 'images': images, 'copied_by_this_script': 0}, f, ensure_ascii=False, indent=2);f.write('\n')
print(json.dumps({'images': len(images), 'output': str(out), 'copied_now': 0}, ensure_ascii=False))
