"""仅在r15全部最终冻结后收集明确证据；不修改报告、产品或运行，不覆盖既有目录。"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import UTC, datetime
import difflib
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PRIVATE = ROOT / '.runtime/aipicture'
EVIDENCE = ROOT / '文档/项目/AI图片编辑项目/证据/2026-10-03-完整迁移验收'
DESTINATION = EVIDENCE / '快捷键选区与逐项进度定向核查'
IMAGE_EXTENSIONS = {'.jpg', '.jpeg', '.png', '.webp'}
ALLOWED_EXTENSIONS = {'.java', '.py', '.ts', '.tsx', '.vue', '.mjs', '.js', '.css',
                      '.html', '.md', '.json', '.xml', '.log', '.txt', '.ps1', '.jpg', '.jpeg'}
MAX_COPY_BYTES = 3 * 1024 * 1024


def resolve(value: str | Path) -> Path:
    path = Path(value)
    path = (path if path.is_absolute() else ROOT / path).resolve()
    if not path.is_relative_to(ROOT):
        raise ValueError(f'证据来源超出工作区：{value}')
    return path


def read_json(path: Path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def digest_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fingerprint(path: Path):
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {'path': path.relative_to(ROOT).as_posix(), 'bytes': path.stat().st_size, 'sha256': digest}


def validate_copy_source(path: Path, snapshot: bool = False):
    if not path.is_file():
        raise ValueError(f'缺少明确证据文件：{path}')
    if path.name.lower() in {'.env', 'service.secret', 'runtime.env',
                             'java.log', 'python-serve.log', 'python-worker.log'}:
        raise ValueError(f'禁止复制配置、密钥或本次会变化的实际stdout：{path}')
    if path.suffix.lower() not in ALLOWED_EXTENSIONS or path.stat().st_size > MAX_COPY_BYTES:
        raise ValueError(f'此文件仅可保留不可变指纹引用，不复制到人读目录：{path}')
    if path.suffix.lower() == '.js' and not snapshot:
        raise ValueError(f'运行库不另复制；仅允许明确冻结快照中的js：{path}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--final-ready', action='store_true', required=True,
                        help='root已确认全部冻结/GUI最终，才允许实际收集')
    parser.add_argument('--images-manifest', default='.runtime/aipicture/selection-progress-images.json')
    parser.add_argument('--browser-record', action='append', required=True,
                        help='root最终view-selection浏览器JSON；多个记录可重复该参数')
    parser.add_argument('--react-admin-build-log', required=True,
                        help='root首次749同步对应admin build日志，保留阶段来源')
    parser.add_argument('--caption-release', required=True)
    parser.add_argument('--caption-snapshot', required=True,
                        help='root确认的r15 caption最终冻结目录')
    parser.add_argument('--caption-review', required=True)
    parser.add_argument('--final-sync', required=True)
    parser.add_argument('--final-admin-build-log', required=True)
    parser.add_argument('--extra-record', action='append', default=[],
                        help='root明确追加的最终JSON/log/XML，不扩目录递归')
    args = parser.parse_args()
    if not args.final_ready:
        raise ValueError('尚未确认全部最终冻结')
    if DESTINATION.exists():
        raise FileExistsError('唯一收集目录已经存在；不覆盖、不续写失败阶段')

    entries: dict[Path, dict] = {}
    targets: dict[Path, Path] = {}
    skipped_snapshot_images = []
    snapshot_counts = []

    def add(source, relative_target, group, expected=None, snapshot=False, generated=None):
        path = resolve(source)
        if generated is None:
            validate_copy_source(path, snapshot=snapshot)
            metadata = fingerprint(path)
        else:
            if path.exists():
                raise FileExistsError(f'不覆盖已经生成的差异来源：{path}')
            metadata = {'path': path.relative_to(ROOT).as_posix(), 'bytes': len(generated),
                        'sha256': digest_bytes(generated)}
        if expected is not None:
            if metadata['sha256'] != expected.get('sha256'):
                raise ValueError(f'冻结sha不一致：{path}')
            if 'bytes' in expected and metadata['bytes'] != expected['bytes']:
                raise ValueError(f'冻结长度不一致：{path}')
        target = (DESTINATION / relative_target).resolve()
        if not target.is_relative_to(DESTINATION):
            raise ValueError('目标路径超出唯一证据子目录')
        if path in entries:
            entries[path]['also_requested_in_groups'].append(group)
            return entries[path]
        if target in targets:
            raise ValueError(f'不同来源目标冲突：{target}')
        entry = {'source_path': path, 'target_path': target, 'source': metadata,
                 'group': group, 'is_image': path.suffix.lower() in IMAGE_EXTENSIONS,
                 'also_requested_in_groups': [], 'generated_bytes': generated}
        entries[path] = entry
        targets[target] = path
        return entry

    def core(group, names):
        for name in names:
            source = PRIVATE / name
            add(source, Path(group) / name, group)

    core('Java逐项进度', [
        'r15-batch-item-lifecycle-release.json', 'r15-batch-item-package-release.json',
        'r15-batch-item-lifecycle-before.log', 'r15-batch-item-lifecycle-after.log',
        'r15-batch-item-package.log', 'r15-batch-item-independent-review.json',
        'r15-batch-item-lifecycle-before/sources.json',
        'r15-batch-item-lifecycle-before/RunService.java',
        'r15-batch-item-lifecycle-before/BatchItemLifecycleTest.java',
        'r15-batch-item-lifecycle-before/TEST-com.ruoyi.aipicture.BatchItemLifecycleTest.xml',
        'r15-batch-item-lifecycle-final/BatchItemLifecycleTest.java',
        'r15-batch-item-lifecycle-final/TEST-com.ruoyi.aipicture.BatchItemLifecycleTest.xml',
        'r15-batch-item-lifecycle-final/TEST-com.ruoyi.aipicture.BatchRecoveryTest.xml',
        'r15-batch-item-package-final/RunService.delta.txt',
        'r15-batch-item-package-final/sources/com/ruoyi/aipicture/service/RunService.java',
    ])
    java_release = read_json(PRIVATE / 'r15-batch-item-lifecycle-release.json')
    add(java_release['source']['path'], 'Java逐项进度/当前源码/RunService.java',
        'Java逐项进度', java_release['source'])
    add(java_release['testSource']['path'], 'Java逐项进度/当前源码/BatchItemLifecycleTest.java',
        'Java逐项进度', java_release['testSource'])

    core('选区点选优先', [
        'selection-priority-release.json', 'selection-priority-before.log',
        'selection-priority-after.log', 'selection-priority-before.xml',
        'selection-priority-after.xml', 'r15-selection-priority-independent-review.json',
        'before-selection-priority-20261003/before.json',
        'before-selection-priority-20261003/engine.py',
        'selection-priority-final/aipicture_runtime/engine.py',
        'selection-priority-final/tests/test_selection_priority.py',
    ])
    selection = read_json(PRIVATE / 'selection-priority-release.json')
    engine_current = resolve(selection['files'][0]['path'])
    engine_before = PRIVATE / 'before-selection-priority-20261003/engine.py'
    add(engine_current, '选区点选优先/当前源码/engine.py', '选区点选优先', selection['files'][0])
    engine_delta = '\n'.join(difflib.unified_diff(
        engine_before.read_text(encoding='utf-8').splitlines(),
        engine_current.read_text(encoding='utf-8').splitlines(),
        fromfile='protected-before/engine.py', tofile='frozen-current/engine.py', lineterm='')) + '\n'
    delta_entry = add(PRIVATE / 'selection-priority-engine.delta.txt',
                      '选区点选优先/engine.delta.txt', '选区点选优先',
                      generated=engine_delta.encode('utf-8'))
    delta_entry['derived_from'] = [fingerprint(engine_before), fingerprint(engine_current)]

    core('制品与运行', [
        'selection-progress-final-artifacts.json', 'compose-selection-progress-artifacts.py',
        'selection-progress-artifact-assembly.log', 'selection-progress-python-build.log',
        'r15-selection-progress-runtime-independent-review.json',
        'selection-progress-runtime-before.json', 'selection-progress-runtime-pre-stop.json',
        'selection-progress-runtime-after.json', 'selection-progress-live-before.json',
        'selection-progress-live-after.json', 'selection-progress-restart-readiness-before.json',
        'selection-progress-java-stop.log', 'selection-progress-java-start.log',
        'selection-progress-python-stop.log', 'selection-progress-python-start.log',
        'selection-progress-log-preservation-gap.json',
        'record-selection-progress-runtime.ps1', 'check-selection-progress-live.py',
    ])
    # PS脚本也是明确证据来源，但仅复制文本，不执行。

    images_path = resolve(args.images_manifest)
    images = read_json(images_path)['images']
    add(images_path, '图片/selection-progress-images.json', '图片清单')
    image_sources = set()
    image_hashes = set()
    for row in images:
        source = resolve(row['source'])
        if source.suffix.lower() not in {'.jpg', '.jpeg'}:
            raise ValueError('本轮图片清单只能收集实际JPEG')
        if row.get('actual_format', 'JPEG') != 'JPEG' or row.get('read_back_by_root') is not True:
            raise ValueError('图片必须保留root逐张回读事实，不代替其回读')
        if not source.read_bytes().startswith(b'\xff\xd8'):
            raise ValueError(f'图片并非JPEG字节：{source}')
        if source in image_sources:
            raise ValueError(f'图片清单重复原来源，请先纠正：{source}')
        image_sources.add(source)
        image_hashes.add(row['sha256'])
        destination = resolve(row['path']) if row.get('path') else DESTINATION / '图片' / source.name
        if not destination.is_relative_to(DESTINATION):
            raise ValueError('图片清单path应位于本轮唯一中文证据目录')
        entry = add(source, destination.relative_to(DESTINATION), '实际截图', row)
        entry['read_back_by_root'] = True
    view_images = {p.resolve() for p in (PRIVATE / 'view-selection-browser').glob('*.jpg')}
    if not view_images.issubset(image_sources):
        raise ValueError('root view-selection-browser仍有图片未列最终图片清单')

    def snapshot(group, directory, expected_count=None, copy_rows=None):
        directory = resolve(directory)
        if not directory.is_dir() or not directory.is_relative_to(PRIVATE) or 'r15' not in directory.name or 'final' not in directory.name:
            raise ValueError('只读取明确本轮final快照目录，不递归工作区或整个.runtime')
        files = sorted(p for p in directory.rglob('*') if p.is_file())
        if expected_count is not None and len(files) != expected_count:
            raise ValueError(f'冻结目录文件数改变：{directory}')
        expected = {}
        for row in copy_rows or []:
            frozen = row.get('frozen') or row.get('snapshot') or row
            path = frozen.get('path')
            if path:
                expected[resolve(path)] = frozen
        if copy_rows is not None and set(expected) != {path.resolve() for path in files}:
            raise ValueError('冻结记录与明确快照文件集合不一致')
        copied, skipped = 0, 0
        for path in files:
            resolved = path.resolve()
            if not resolved.is_relative_to(directory):
                raise ValueError('快照链接越过明确目录')
            if path.suffix.lower() in IMAGE_EXTENSIONS:
                if fingerprint(path)['sha256'] not in image_hashes:
                    raise ValueError('快照图片必须在最终图片清单中有同字节来源，不另复制')
                skipped_snapshot_images.append(fingerprint(path)); skipped += 1
                continue
            add(path, Path(group) / '冻结快照' / path.relative_to(directory), group,
                expected.get(resolved), snapshot=True)
            copied += 1
        snapshot_counts.append({'group': group, 'source_directory': str(directory.relative_to(ROOT)),
                                'frozen_files': len(files), 'copied_non_image_files': copied,
                                'images_already_in_manifest_not_copied_again': skipped})

    vue = read_json(PRIVATE / 'r15-evaluation-report-release.json')
    core('评测报告恢复', ['r15-evaluation-report-release.json', 'r15-evaluation-report-independent-review.json'])
    snapshot('评测报告恢复', vue['frozenDirectory'], expected_count=22, copy_rows=vue['frozenCopies'])
    react = read_json(PRIVATE / 'r15-view-selection-release.json')
    core('画布快捷键与选区', ['r15-view-selection-release.json', 'r15-view-selection-readonly-audit.json',
                           'r15-react-view-selection-independent-review.json', 'view-selection-react-sync-final.json'])
    snapshot('画布快捷键与选区', react['final_snapshot'], expected_count=31, copy_rows=react['copies'])
    add(args.react_admin_build_log, '画布快捷键与选区/' + Path(args.react_admin_build_log).name, '画布快捷键与选区')
    for browser_record in args.browser_record:
        add(browser_record, '实际截图/' + Path(browser_record).name, '浏览器实际记录')

    for key in ('caption_release', 'caption_review', 'final_sync', 'final_admin_build_log'):
        value = getattr(args, key)
        add(value, '最终caption与同步/' + Path(value).name, '最终caption与同步')
    snapshot('最终caption与同步', args.caption_snapshot)
    for value in args.extra_record:
        add(value, '明确追加记录/' + Path(value).name, '明确追加记录')
    add(Path(__file__), '证据汇总/collect-selection-progress-evidence.py', '证据汇总')

    # 全部来源、冻结sha、图片覆盖和目标冲突在新建目录前核完。
    DESTINATION.mkdir(parents=True, exist_ok=False)
    copied = []
    for entry in entries.values():
        source, target = entry['source_path'], entry['target_path']
        if entry['generated_bytes'] is not None:
            with source.open('xb') as stream:
                stream.write(entry['generated_bytes'])
        data = source.read_bytes()
        if len(data) != entry['source']['bytes'] or digest_bytes(data) != entry['source']['sha256']:
            raise ValueError(f'预检后来源变化，停止并保留已生成证据：{source}')
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('xb') as stream:
            stream.write(data)
        target_fingerprint = fingerprint(target)
        if target_fingerprint['sha256'] != entry['source']['sha256'] or target_fingerprint['bytes'] != entry['source']['bytes']:
            raise ValueError('复制回读字节不一致')
        copied.append({'source': entry['source'], 'copy': target_fingerprint,
                       'relative_business_link': target.relative_to(EVIDENCE).as_posix(),
                       'group': entry['group'], 'is_image': entry['is_image'],
                       'also_requested_in_groups': entry['also_requested_in_groups'],
                       'derived_from': entry.get('derived_from'),
                       'read_back_by_root': entry.get('read_back_by_root')})

    artifact = read_json(PRIVATE / 'selection-progress-final-artifacts.json')
    manifest = {
        'created_at_utc': datetime.now(UTC).isoformat(),
        'scope': __doc__, 'collector': fingerprint(Path(__file__)),
        'destination': DESTINATION.relative_to(ROOT).as_posix(),
        'copies': copied, 'copied_file_count': len(copied),
        'copied_images_count': sum(row['is_image'] for row in copied),
        'copied_non_image_count': sum(not row['is_image'] for row in copied),
        'group_counts': dict(Counter(row['group'] for row in copied)),
        'snapshots': snapshot_counts, 'snapshot_images_not_copied_again': skipped_snapshot_images,
        'image_source_manifest': fingerprint(images_path),
        'immutable_artifact_references_not_copied': {
            'java': {'path': artifact['java']['frozen_path'], 'sha256': artifact['java']['total_sha256']},
            'module': {'path': artifact['java']['module_frozen_path'], 'sha256': artifact['java']['module_sha256']},
            'wheel': {'path': artifact['python']['wheel_path'], 'sha256': artifact['python']['wheel_sha256']},
            'sdist': {'path': artifact['python']['sdist_path'], 'sha256': artifact['python']['sdist_sha256']},
        },
        'limitations': [
            '三份重启前完整stdout未及时保留，以root gap JSON为准；不复制当前变化stdout、不用较早基线补造。',
            '包/库只引用不可变记录；明确31/22/caption源快照内的技术JS按原字节保留，不再另复制运行库。',
            '照片由最终清单一次复制；Vue快照7张照片不重复，不把总files加上images再次求和。',
            '复制数量不包含本清单自身；后续review/静态检查若追加单列，不冒充本次copies。',
            '这是证据收集，0新增测试/HTTP/CIM/数据库/模型/业务写入，不改变22项业务判定。',
        ],
    }
    manifest_path = DESTINATION / '证据清单.json'
    with manifest_path.open('x', encoding='utf-8') as stream:
        json.dump(manifest, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    print(json.dumps({'manifest': fingerprint(manifest_path), 'copied_files': len(copied),
                      'images': manifest['copied_images_count'],
                      'non_images': manifest['copied_non_image_count'],
                      'snapshot_counts': snapshot_counts}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
