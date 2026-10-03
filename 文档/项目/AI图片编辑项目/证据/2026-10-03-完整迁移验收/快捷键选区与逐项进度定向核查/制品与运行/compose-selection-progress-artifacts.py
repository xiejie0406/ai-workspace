"""r15本地制品装配：仅替换不可变r13总包中的图片模块，核对38份Python生产源码。"""
from datetime import UTC, datetime
import copy
import hashlib
import io
import json
from pathlib import Path
import tarfile
from zipfile import ZIP_STORED, ZipFile

root = Path(__file__).resolve().parents[2]
private = root / '.runtime/aipicture'
read = lambda path: json.loads(path.read_text(encoding='utf-8-sig'))
digest = lambda data: hashlib.sha256(data).hexdigest()


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def file_record(path):
    return {'path': str(path.resolve()), 'bytes': path.stat().st_size, 'sha256': sha(path)}


def require_equal(value, expected, label):
    if value != expected:
        raise AssertionError(f'{label}: {value!r} != {expected!r}')


baseline_path = private / 'layer-delivery-final-artifacts.json'
module_release_path = private / 'r15-batch-item-package-release.json'
test_release_path = private / 'r15-batch-item-lifecycle-release.json'
selection_release_path = private / 'selection-priority-release.json'
baseline, release, tests, selection = map(read, (baseline_path, module_release_path, test_release_path, selection_release_path))
old_jar = Path(baseline['java']['frozen_path'])
old_sha = baseline['java']['total_sha256']
runtime = private / 'ruoyi-admin.jar'
module = Path(release['moduleFrozen']['path'])
module_sha = release['moduleFrozen']['sha256']
candidate = private / 'ruoyi-admin-selection-progress-20261003-r15.jar'
output = private / 'selection-progress-final-artifacts.json'
require_equal(old_sha, '5d2209f112ee63573f890c28d2a923c2c81502d9ae51de1717041c1ed97fccb7', '当前不可变总包基线')
assert not candidate.exists() and not output.exists(), '不得覆盖阶段包或记录'
require_equal(sha(old_jar), old_sha, '不可变总包')
require_equal(sha(runtime), old_sha, '装配前运行包文件')
require_equal(sha(module), module_sha, '冻结图片模块')
require_equal(sha(Path(release['moduleTarget']['path'])), module_sha, '标准Maven模块')
require_equal(release['package']['exitCode'], 0, '模块打包退出码')
require_equal(release['package']['reactorModules'], ['ruoyi-aipicture'], '打包范围')
require_equal(tests['after']['tests'], 15, 'Java本轮去重检查')
require_equal((tests['after']['newTests'], tests['after']['existingAffectedTestsActuallyReexecuted']), (8, 7), 'Java检查组成')
require_equal((tests['after']['failures'], tests['after']['errors'], tests['after']['skipped']), (0, 0, 0), 'Java定向结果')
require_equal((selection['after']['total'], selection['after']['failed'], selection['after']['errors'], selection['after']['skipped']), (16, 0, 0, 0), 'Python定向结果')

module_root = root / 'ruoyi-backend/ruoyi-aipicture'
sources = {Path(row['path']).relative_to(module_root / 'src/main/java').as_posix(): row['sha256'] for row in release['javaSources']}
classes = {row['entry']: row['embeddedSha256'] for row in release['javaClasses']}
contracts = {Path(row['path']).name: row['embeddedSha256'] for row in release['embeddedContracts']}
require_equal((len(sources), len(classes), len(contracts)), (32, 43, 5), 'Java库存')
require_equal(set(sources), set(baseline['java']['source_sha256']), 'Java源码集合')
changed_java = sorted(name for name, value in sources.items() if value != baseline['java']['source_sha256'][name])
require_equal(changed_java, ['com/ruoyi/aipicture/service/RunService.java'], 'Java产品源码变化')
for row in release['javaSources']:
    require_equal(sha(Path(row['path'])), row['sha256'], '当前Java源码')
with ZipFile(module) as packed:
    assert len(packed.namelist()) == len(set(packed.namelist())), '模块entry不能重名'
    require_equal({name for name in packed.namelist() if name.startswith('com/ruoyi/aipicture/') and name.endswith('.class')}, set(classes), '图片class集合')
    for row in release['javaClasses']:
        require_equal(sha(Path(row['path'])), row['embeddedSha256'], 'Java disk class')
        require_equal(digest(packed.read(row['entry'])), row['embeddedSha256'], 'Java module class')
    for name, value in contracts.items():
        require_equal(value, baseline['java']['contracts'][name], '契约与r14基线')
        require_equal(sha(root / 'contracts/aipicture/v1' / name), value, '当前契约')
        require_equal(digest(packed.read('contracts/aipicture/v1/' + name)), value, '模块内嵌契约')

python_root = root / 'python/aipicture-runtime'
python_sources = {path.relative_to(python_root).as_posix(): sha(path) for path in (python_root / 'aipicture_runtime').rglob('*.py')}
require_equal(len(python_sources), 38, 'Python库存')
require_equal(python_sources, selection['python']['source_sha256'], 'Python冻结source')
require_equal(set(python_sources), set(baseline['python']['source_sha256']), 'Python源集合')
changed_python = sorted(name for name, value in python_sources.items() if value != baseline['python']['source_sha256'][name])
require_equal(changed_python, ['aipicture_runtime/engine.py'], 'Python产品源码变化')
python_directory = private / 'python-package-selection-progress-20261003'
wheels = list(python_directory.glob('*.whl'))
sdists = list(python_directory.glob('*.tar.gz'))
require_equal((len(wheels), len(sdists)), (1, 1), 'Python新包数量')
wheel, sdist = wheels[0], sdists[0]
with ZipFile(wheel) as packed:
    assert len(packed.namelist()) == len(set(packed.namelist())), 'wheel entry不能重名'
    wheel_sources = {name: digest(packed.read(name)) for name in packed.namelist() if name.startswith('aipicture_runtime/') and name.endswith('.py')}
    require_equal(wheel_sources, python_sources, 'wheel 38源字节')
with tarfile.open(sdist, 'r:gz') as packed:
    members = [member for member in packed.getmembers() if member.isfile()]
    prefixes = {member.name.split('/', 1)[0] for member in members}
    require_equal(len(prefixes), 1, 'sdist唯一根')
    prefix = next(iter(prefixes)) + '/'
    assert len(members) == len({member.name for member in members}), 'sdist member不能重名'
    sdist_sources = {member.name.removeprefix(prefix): digest(packed.extractfile(member).read()) for member in members if member.name.removeprefix(prefix).startswith('aipicture_runtime/') and member.name.endswith('.py')}
    require_equal(sdist_sources, python_sources, 'sdist 38源字节')
    require_equal(packed.extractfile(prefix + 'README.md').read(), (python_root / 'README.md').read_bytes(), 'sdist README')
require_equal(sha(python_root / 'uv.lock'), baseline['python']['uv_lock_sha256'], '锁文件未变')
require_equal(sha(python_root / 'README.md'), baseline['python']['readme_sha256'], 'README未变')
build_log = private / 'selection-progress-python-build.log'
assert 'Successfully built' in build_log.read_text(encoding='utf-8-sig'), '根Python构建成功日志缺失'

member = 'BOOT-INF/lib/ruoyi-aipicture-3.9.2.jar'
entry_records = []
with ZipFile(old_jar) as old:
    infos = old.infolist()
    require_equal(len(infos), 484, '原总包entry数量')
    assert len({info.filename for info in infos}) == len(infos), '总包entry不能重名'
    require_equal(old.getinfo(member).compress_type, ZIP_STORED, '嵌套模块STORED')
    require_equal(digest(old.read(member)), baseline['java']['module_sha256'], '原嵌套模块')
    signature_entries = [name for name in old.namelist() if name.upper().endswith(('.SF', '.RSA', '.DSA')) and name.startswith('META-INF/')]
    # 原包唯一空BOOT.SF标记原样保留；非空SF与签名块继续拒绝。
    require_equal(signature_entries, ['META-INF/BOOT.SF'], '原签名标记范围')
    assert old.read(signature_entries[0]) == b'', '不重装配真正签名总包'
    assert infos[0].header_offset == 0, '不处理带前导脚本的归档'
    require_equal(sum(name == member for name in old.namelist()), 1, '唯一图片nested lib')
    require_equal([name for name in old.namelist() if name.startswith('BOOT-INF/lib/ruoyi-aipicture')], [member], '图片依赖唯一装配')
    with ZipFile(candidate, 'x') as new:
        new.comment = old.comment
        for info in infos:
            old_payload = old.read(info)
            payload = module.read_bytes() if info.filename == member else old_payload
            new.writestr(copy.copy(info), payload)
            entry_records.append({'entry': info.filename, 'baseline_sha256': digest(old_payload), 'candidate_sha256': digest(payload), 'changed': info.filename == member, 'compression_type': info.compress_type})
with ZipFile(old_jar) as old, ZipFile(candidate) as new:
    require_equal(new.namelist(), old.namelist(), '总包entry顺序/列表')
    require_equal(new.comment, old.comment, '总包comment')
    for row in entry_records:
        require_equal(digest(new.read(row['entry'])), row['candidate_sha256'], '候选entry回读')
        require_equal(new.getinfo(row['entry']).compress_type, row['compression_type'], 'entry压缩方式')
        if not row['changed']:
            require_equal(row['baseline_sha256'], row['candidate_sha256'], '未受影响entry内容')
    require_equal(digest(new.read(member)), module_sha, '候选嵌套模块')
require_equal(sha(old_jar), old_sha, '装配后不可变基线')
require_equal(sha(runtime), old_sha, '运行包未写入')

java = {
    'frozen_path': str(candidate), 'total_sha256': sha(candidate), 'module_sha256': module_sha,
    'source_sha256': sources, 'compiled_classes': classes, 'contracts': contracts,
    'changed_sources': changed_java, 'current_changed_vs_baseline': changed_java,
    'source_release_record': str(module_release_path), 'source_release_sha256': sha(module_release_path),
    'module_frozen_path': str(module), 'module_size_bytes': module.stat().st_size,
    'module_package_command': release['package']['command'], 'module_package_exit_code': 0,
    'module_build_started_at_utc': release['package']['startedAtUtc'],
    'module_build_finished_at_utc': release['package']['finishedAtUtc'],
    'module_build_log': release['package']['log'], 'build_skip_tests': True,
    'assembly_method': '不可变r13总包5d+本轮标准Maven图片模块；非全聚合构建',
    'assembly_finished_at_utc': datetime.now(UTC).isoformat(),
    'total_entries': 484, 'unchanged_entry_payloads': 483, 'changed_entry': member,
    'preserved_empty_marker_entries': signature_entries, 'nested_module_compression': 'STORED',
    'baseline_frozen_path': str(old_jar), 'baseline_total_sha256': old_sha,
    'runtime_jar_path': str(runtime), 'runtime_jar_sha256': old_sha, 'runtime_synchronized': False,
    'targeted_tests': 15, 'new_checks': 8, 'related_original_checks': 7,
    'targeted_tests_origin': '本轮实际XML15=新增8+受影响原7；修前8/2失败单独保留；不叠加原r13的20/旧13',
    'tests_release_record': str(test_release_path), 'tests_release_sha256': sha(test_release_path),
    'tests_executed_during_this_freeze': 0, 'mysql_concurrency_executed_this_turn': False,
    'readonly_rechecked_counts': {'sources': 32, 'classes': 43, 'contracts': 5},
    'baseline_stage_metadata': {'path': str(baseline_path), 'sha256': sha(baseline_path),
                                'note': '原r13装配时间/AAD5聚合来源/20项属于历史，不继承为本轮构建或当前运行结论'},
}
python = {
    'source_sha256': python_sources, 'source_wheel_sdist_match_count': 38,
    'changed_sources': changed_python, 'wheel_sha256': sha(wheel), 'sdist_sha256': sha(sdist),
    'wheel_size_bytes': wheel.stat().st_size, 'sdist_size_bytes': sdist.stat().st_size,
    'frozen_directory': str(python_directory), 'wheel_path': str(wheel), 'sdist_path': str(sdist),
    'uv_lock_sha256': sha(python_root / 'uv.lock'), 'readme_sha256': sha(python_root / 'README.md'),
    'sdist_readme_matches': True, 'parent_build_exit_code': 0,
    'parent_build_method': 'root实际uv build --offline --out-dir ../../.runtime/aipicture/python-package-selection-progress-20261003；本helper只回读包，无构建/安装/模型操作',
    'build_log': str(build_log), 'build_log_sha256': sha(build_log),
    'build_finished_at_utc': None,
    'build_time_note': '原log无精确开始/结束时间；不拿mtime当实际构建完成时间，不继承r14时间',
    'build_log_last_write_at_utc': datetime.fromtimestamp(build_log.stat().st_mtime, UTC).isoformat(),
    'artifact_last_write_at_utc': {'wheel': datetime.fromtimestamp(wheel.stat().st_mtime, UTC).isoformat(), 'sdist': datetime.fromtimestamp(sdist.stat().st_mtime, UTC).isoformat()},
    'source_delta_baseline': str(baseline_path), 'source_delta_baseline_sha256': sha(baseline_path),
    'agent_release': str(selection_release_path), 'agent_release_sha256': sha(selection_release_path),
    'targeted_tests': 16, 'new_checks': 4, 'related_original_checks': 12,
    'targeted_test_composition': selection['python']['targeted_test_composition'],
    'tests_executed_during_this_freeze': 0, 'real_inference_reexecuted_this_turn': False,
    'runtime_synchronized': False,
    'scope_note': '恢复合法混合参数的原点选优先，全部参数仍验证；原交付59沿用历史且不计为本轮执行；固定SAM替身不代表真实模型质量',
}
result = {
    'created_at': datetime.now(UTC).isoformat(), 'scope': __doc__, 'java': java, 'python': python,
    'runtime_synchronized': False, 'entry_payload_comparison': entry_records,
    'baseline': file_record(baseline_path), 'assembly_script_sha256': sha(Path(__file__)),
    'technical_tests_executed': 0, 'business_operations_executed': 0,
    'business_acceptance': False,
    'limitations': ['只核源码、class、包字节与现有定向证据，不执行服务/HTTP/DB/模型。', '当前运行文件仍5d，候选与Python包待root统一同步。', 'Java15与Python16分别计数，不与阶段基线或业务22项相加。'],
}
with output.open('x', encoding='utf-8') as stream:
    json.dump(result, stream, ensure_ascii=False, indent=2)
    stream.write('\n')
print(json.dumps({'assembled': True, 'candidate': file_record(candidate), 'module_sha256': module_sha,
                  'entries': 484, 'unchanged_entries': 483, 'python_wheel': file_record(wheel),
                  'python_sdist': file_record(sdist), 'output': file_record(output),
                  'runtime_synchronized': False}, ensure_ascii=False, indent=2))
