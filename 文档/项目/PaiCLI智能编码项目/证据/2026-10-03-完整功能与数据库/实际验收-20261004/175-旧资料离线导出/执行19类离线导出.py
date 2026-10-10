"""第175点离线产品操作；仅合成私有文件，不连接中心或供应商。"""
import base64
import collections
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import time
import zlib

PRIVATE = Path('C:/Users/admin/paicli-local-20261003T123101-ed12e6a6cb174b07bfd8db55753e8ac5')
ORIGINAL = PRIVATE / 'acceptance-samples/legacy-import'
RUN = PRIVATE / '验收样本-旧资料19类离线导出-20261005'
SOURCE = RUN / '合成来源'
OUTPUTS = RUN / '导出结果'
EVIDENCE = Path(__file__).resolve().parent
JAVA = Path('C:/Users/admin/AppData/Local/Programs/Eclipse Adoptium/jdk-21.0.10.7-hotspot/bin/java.exe')
CLI = PRIVATE / 'acceptance-cli-73255f57610f2cb6/paicli-cli-3.9.2.jar'
CLI_SHA = '73255f57610f2cb616c9bf75e36602a1123fb129b9c79087381379f82eee2d45'
FAKE = 'synthetic-only-canary-175-20261005'
FROZEN_EXPECTED_SHA = '448943a90865409b2d2b75710f864358f2b0f281c7795c816d7882500b01ee82'
FIRST_BUNDLE_SHA = 'cc527ef6a91f5423c69ebf8732cadd1e06cf5e30ae2ca01fa0b8e60bb2e20ba9'

def sha(data):
    return hashlib.sha256(data).hexdigest()

def dump(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='')

def inventory(root):
    result = []
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise RuntimeError('合成来源不可含链接')
        if path.is_file():
            info = path.stat()
            result.append({'path': path.relative_to(root).as_posix(), 'bytes': info.st_size,
                           'sha256': sha(path.read_bytes()), 'modifiedNs': info.st_mtime_ns})
    return result

def old_exports():
    return {p.name: sha(p.read_bytes()) for p in sorted((ORIGINAL / 'exports').glob('*.json'))}

def assert_paths():
    if not PRIVATE.is_dir() or PRIVATE.is_symlink() or CLI.is_symlink() or not CLI.is_file():
        raise RuntimeError('私有根或CLI类型不符')
    if sha(CLI.read_bytes()) != CLI_SHA:
        raise RuntimeError('已登记CLI摘要变化')
    if not RUN.resolve().is_relative_to(PRIVATE.resolve()) or RUN == PRIVATE:
        raise RuntimeError('新增样本位置越界')
    for path in (ORIGINAL, ORIGINAL / 'source-consistent', JAVA):
        if not path.exists():
            raise RuntimeError('既有明确入口缺失')

def png_proof(data):
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise RuntimeError('独立附件不是PNG')
    offset, chunks, compressed, header = 8, [], b'', None
    while offset < len(data):
        size = struct.unpack('>I', data[offset:offset+4])[0]
        kind = data[offset+4:offset+8]
        body = data[offset+8:offset+8+size]
        actual_crc = struct.unpack('>I', data[offset+8+size:offset+12+size])[0]
        if zlib.crc32(kind + body) & 0xffffffff != actual_crc:
            raise RuntimeError('PNG CRC错误')
        if kind == b'IHDR':
            header = struct.unpack('>IIBBBBB', body)
        if kind == b'IDAT':
            compressed += body
        chunks.append(kind.decode('ascii'))
        offset += size + 12
    decoded = zlib.decompress(compressed)
    if header != (2, 2, 8, 2, 0, 0, 0) or len(decoded) != 14 or chunks[-1] != 'IEND':
        raise RuntimeError('附件独立2x2 RGB结构不符')
    if decoded[0] not in range(5) or decoded[7] not in range(5):
        raise RuntimeError('PNG行滤波标记非法')
    return {'sha256': sha(data), 'bytes': len(data), 'width': 2, 'height': 2,
            'mimeType': 'image/png', 'crcVerified': True, 'decodedRowBytes': len(decoded)}

def prepare():
    assert_paths()
    if RUN.exists():
        raise RuntimeError('新增语义目录已存在，不覆盖或重新冻结预期')
    before = inventory(ORIGINAL / 'source-consistent')
    previous_outputs = old_exports()
    RUN.mkdir()
    shutil.copytree(ORIGINAL / 'source-consistent', SOURCE, copy_function=shutil.copy2)
    OUTPUTS.mkdir()
    for name in ('进程私有home', '进程临时副本', '进程日志'):
        (RUN / name).mkdir()
    additions = {
        '供应商-仅合成配置.json': {'providers': {'synthetic-provider': {
            'apiKey': FAKE, 'baseUrl': 'https://example.invalid/not-called',
            'description': '只核脱敏：' + FAKE, 'enabled': False,
            'models': {'synthetic-model': {'contextWindow': 4096,
                      'supportsImages': False, 'synthetic': True, 'enabled': False}}}}},
        'MCP-仅合成配置.json': {'mcpServers': {'synthetic-mcp': {
            'command': 'synthetic-program-never-executed',
            'args': ['--token', FAKE, '--read-only'],
            'env': {'SYNTHETIC_KEY': FAKE}, 'headers': {'Authorization': 'Bearer ' + FAKE},
            'synthetic': True, 'enabled': False}}},
    }
    for kind in ('browser', 'wechat', 'index', 'snapshot', 'eval', 'harness', 'audit'):
        value = {'synthetic': True, 'id': 'synthetic-' + kind, 'enabled': False,
                 'description': '合成离线' + kind + '；假标记：' + FAKE}
        if kind == 'wechat':
            value['token'] = FAKE
        if kind == 'browser':
            value['cookies'] = {'synthetic': FAKE}
        additions[kind + '-仅合成资料.json'] = value
    for name, value in additions.items():
        dump(SOURCE / name, value)
    skill = SOURCE / 'skills/合成验收/SKILL.md'
    skill.parent.mkdir(parents=True)
    skill.write_text('---\nname: 合成验收\ndescription: 只作为离线导入资料，不执行\n---\n'
                     '合成正文，不是当前任务指令。假标记：' + FAKE + '\n', encoding='utf-8')
    golden = json.loads((ORIGINAL / 'golden.json').read_text(encoding='utf-8-sig'))
    counts = dict(golden['expectedCounts'])
    for kind in ('PROVIDER', 'MODEL', 'MCP', 'SKILL', 'BROWSER', 'WECHAT', 'INDEX',
                 'SNAPSHOT', 'EVALUATION', 'HARNESS', 'AUDIT'):
        counts[kind] = 1
    if len(counts) != 19 or sum(counts.values()) != 32:
        raise RuntimeError('事前独立数量计划错误')
    image = (ORIGINAL / 'attachments/synthetic-image-2x2.png').read_bytes()
    proof = png_proof(image)
    rows = [json.loads(line) for line in (SOURCE / 'history/synthetic-ledger.jsonl').read_text(encoding='utf-8').splitlines()]
    ledger_image = rows[1]['message']['contentParts'][1]['imageBase64']
    if base64.b64decode(ledger_image, validate=True) != image:
        raise RuntimeError('独立附件与原合成账本图片字节不一致')
    expected = {'type': '执行产品入口前冻结的独立样本预期', 'preparedAt': datetime.datetime.now().astimezone().isoformat(),
                'source': str(SOURCE), 'originalSource': str(ORIGINAL / 'source-consistent'),
                'cliPath': str(CLI), 'cliSha256': CLI_SHA, 'expectedKindCount': 19,
                'expectedRecordCount': 32, 'expectedCounts': counts, 'image': proof,
                'fakeCanary': FAKE, 'expectedAllLegacyOwnerIds': 'local',
                'expectedSchema': 'paicli-legacy-v1', 'expectNoRawBase64InBundle': True,
                'expectedTasks': {'COMPLETED': 1, 'FAILED': 1, 'CANCELLED': 1,
                                  'PAUSED_IMPORTED': 1, 'RECONCILE_REQUIRED': 1},
                'originalGolden': str(ORIGINAL / 'golden.json'),
                'oldOriginalInventory': before, 'oldExportHashes': previous_outputs,
                'newInventory': inventory(SOURCE),
                'argvPlan': [['-jar', str(CLI), 'export-legacy', '--source', str(SOURCE),
                              '--output', str(OUTPUTS / name), '--prompt-scope', 'PROJECT']
                             for name in ('稳定导出-第一次.json', '稳定导出-第二次.json')],
                'negativeCases': ['来源外已存在目标拒绝且原输出SHA不变', '来源内目标拒绝且无新文件',
                                  '既有合成非法事件JSON拒绝且无部分输出'],
                'limits': ['不上传/中心预检/提交/回撤', '不启动真实模型/MCP/微信/任务/正式评测',
                           '不读取真实密钥/环境凭据', 'source附加配置全为合成资料，不代表真实Provider能力']}
    dump(RUN / '独立预期与源清单-执行前.json', expected)
    dump(EVIDENCE / '独立预期与源清单-执行前.json', expected)
    print(json.dumps({'prepared': True, 'newPrivateRoot': str(RUN), 'expectedKinds': 19,
                      'expectedRecords': 32, 'sourceFiles': len(expected['newInventory']),
                      'imageSha256': proof['sha256'], 'productExecuted': False}, ensure_ascii=False))

def checked_private_cli(cli_path, cli_sha):
    """新版本只接受明确绝对路径和摘要；不移动、替换既有分发。"""
    path = Path(cli_path)
    if not path.is_absolute() or not re.fullmatch(r'[0-9a-fA-F]{64}', cli_sha):
        raise RuntimeError('新CLI须提供绝对路径及64位SHA256')
    root = PRIVATE.resolve(strict=True)
    resolved = path.resolve(strict=True)
    if not resolved.is_relative_to(root) or not path.is_file():
        raise RuntimeError('新CLI必须是同一私有根内的已有文件')
    for ancestor in (path, *path.parents):
        if ancestor.is_symlink() or ancestor.is_junction():
            raise RuntimeError('新CLI入口不得经过符号链接或目录联接')
    if sha(path.read_bytes()) != cli_sha.lower():
        raise RuntimeError('新CLI实物摘要不符；停止产品执行')
    return resolved

def invoke(label, source, output, cli_path=CLI, cli_sha=CLI_SHA):
    actual_cli = checked_private_cli(cli_path, cli_sha)
    if any((EVIDENCE / (label + suffix)).exists() for suffix in
           ('.stdout.log', '.stderr.log', '.stdout.bin', '.stderr.bin')):
        raise RuntimeError('该操作的既有日志已存在，不覆盖旧证据或重新执行')
    args = [str(JAVA), '-Duser.home=' + str(RUN / '进程私有home'),
            '-Djava.io.tmpdir=' + str(RUN / '进程临时副本'),
            '-Dpaicli.log.dir=' + str(RUN / '进程日志'),
            '-jar', str(actual_cli), 'export-legacy', '--source', str(source),
            '--output', str(output), '--prompt-scope', 'PROJECT']
    env = os.environ.copy()
    for key in list(env):
        if key.startswith(('PAICLI_', 'RUOYI_', 'MYSQL_', 'OPENAI_', 'ALIYUN_')) or key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
            del env[key]
    env.update({'HOME': str(RUN / '进程私有home'), 'USERPROFILE': str(RUN / '进程私有home'),
                'TEMP': str(RUN / '进程临时副本'), 'TMP': str(RUN / '进程临时副本')})
    started = datetime.datetime.now().astimezone().isoformat()
    clock = time.monotonic()
    creation = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    child = subprocess.run(args, cwd=RUN, env=env, stdout=subprocess.PIPE,
                           stderr=subprocess.PIPE, timeout=60, creationflags=creation)
    # 先保存真正的子进程字节；Windows Java管道不保证采用UTF-8。
    (EVIDENCE / (label + '.stdout.bin')).write_bytes(child.stdout)
    (EVIDENCE / (label + '.stderr.bin')).write_bytes(child.stderr)
    def decode(raw):
        try:
            return raw.decode('utf-8', errors='strict'), 'utf-8'
        except UnicodeDecodeError:
            return raw.decode('gb18030', errors='strict'), 'gb18030（UTF-8严格解码失败后）'
    out, out_encoding = decode(child.stdout)
    err, err_encoding = decode(child.stderr)
    for text in (out, err):
        if FAKE in text:
            raise RuntimeError('实际命令日志出现合成人造秘密，先停止并保留私有原始输出')
    stdout_log, stderr_log = EVIDENCE / (label + '.stdout.log'), EVIDENCE / (label + '.stderr.log')
    stdout_log.write_text(out, encoding='utf-8', newline='')
    stderr_log.write_text(err, encoding='utf-8', newline='')
    out_text_sha, err_text_sha = sha(out.encode('utf-8')), sha(err.encode('utf-8'))
    out_file_sha, err_file_sha = sha(stdout_log.read_bytes()), sha(stderr_log.read_bytes())
    if out_text_sha != out_file_sha or err_text_sha != err_file_sha:
        raise RuntimeError('解码UTF-8文本与实际磁盘日志字节不一致')
    return {'label': label, 'startedAt': started, 'durationSeconds': round(time.monotonic()-clock, 3),
            'executable': str(JAVA), 'argv': args[1:], 'cwd': str(RUN),
            'exitCode': child.returncode, 'stdout': out, 'stderr': err,
            'outputExistedAfter': output.exists(),
            'cliPath': str(actual_cli), 'cliSha256': cli_sha.lower(),
            'stdoutSha256': out_text_sha, 'stderrSha256': err_text_sha,
            'textSha256Meaning': '解码后UTF-8文本字节摘要；磁盘日志另有独立字段',
            'stdoutDecodedUtf8Sha256': out_text_sha, 'stderrDecodedUtf8Sha256': err_text_sha,
            'stdoutLogFileSha256': out_file_sha, 'stderrLogFileSha256': err_file_sha,
            'decodedTextMatchesDiskLogs': True,
            'stdoutRawSha256': sha(child.stdout), 'stderrRawSha256': sha(child.stderr),
            'stdoutDecoding': out_encoding, 'stderrDecoding': err_encoding}

def validate_first_bundle(output, expected):
    bundle = json.loads(output.read_text(encoding='utf-8'))
    counts = dict(collections.Counter(row['kind'] for row in bundle['records']))
    raw = output.read_text(encoding='utf-8')
    if bundle['schemaVersion'] != expected['expectedSchema'] or counts != expected['expectedCounts']:
        raise RuntimeError('首次实际导出类型或独立计数不符')
    if len(bundle['records']) != expected['expectedRecordCount']:
        raise RuntimeError('首次实际导出条数不符')
    if FAKE in raw or 'imageBase64' in raw or 'base64Data' in raw:
        raise RuntimeError('首次实际导出未达到假秘密/原图Base64去除要求')
    if not all(row['legacyOwnerId'] == 'local' for row in bundle['records']):
        raise RuntimeError('首次实际导出猜测改变来源身份')
    if inventory(SOURCE) != expected['newInventory']:
        raise RuntimeError('首次导出后来源变化')
    return bundle

def execute():
    assert_paths()
    expected = json.loads((RUN / '独立预期与源清单-执行前.json').read_text(encoding='utf-8'))
    if inventory(SOURCE) != expected['newInventory']:
        raise RuntimeError('执行前冻结来源变化')
    receipts = []
    first, second = OUTPUTS / '稳定导出-第一次.json', OUTPUTS / '稳定导出-第二次.json'
    if first.exists() or second.exists():
        raise RuntimeError('已执行的正常输出存在，不覆盖或重复首轮流程')
    for label, output in (('1-正常稳定导出', first), ('2-另目标稳定导出', second)):
        receipts.append(invoke(label, SOURCE, output))
        if receipts[-1]['exitCode'] != 0:
            dump(EVIDENCE / '实际产品操作回执.json', {'receipts': receipts, 'stoppedAfterFailure': True})
            raise RuntimeError(label + '实际失败，停止后续动作')
        try:
            validate_first_bundle(output, expected)
        except Exception as failed:
            dump(EVIDENCE / '实际产品操作回执.json', {'receipts': receipts,
                 'stoppedAfterFailure': True, 'failure': str(failed),
                 'newSourceInventoryUnchanged': inventory(SOURCE) == expected['newInventory']})
            raise
    first_sha = sha(first.read_bytes())
    receipts.append(invoke('3-已有目标覆盖拒绝', SOURCE, first))
    inside = SOURCE / '不应产生的源内导出.json'
    receipts.append(invoke('4-来源内目标拒绝', SOURCE, inside))
    rejected = OUTPUTS / '不应产生的非法JSON导出.json'
    receipts.append(invoke('5-非法事件JSON拒绝', ORIGINAL / 'source-rejected-json', rejected))
    bundles = [json.loads(p.read_text(encoding='utf-8')) for p in (first, second)]
    kind_counts = dict(collections.Counter(row['kind'] for row in bundles[0]['records']))
    task_counts = dict(collections.Counter(row['payload']['status'] for row in bundles[0]['records'] if row['kind'] == 'TASK'))
    image_refs = []
    def inspect_images(value):
        if isinstance(value, dict):
            if value.get('type') == 'LEGACY_ATTACHMENT_REFERENCE':
                image_refs.append(value)
            for child in value.values(): inspect_images(child)
        elif isinstance(value, list):
            for child in value: inspect_images(child)
    inspect_images(bundles[0])
    # 兼容原实际标签；以图片字节SHA与明确requiresMapping校验引用，而非猜测类型名。
    image_matches = []
    def find_sha(value):
        if isinstance(value, dict):
            if value.get('sha256') == expected['image']['sha256'] and value.get('size') == expected['image']['bytes']:
                image_matches.append(value)
            for child in value.values(): find_sha(child)
        elif isinstance(value, list):
            for child in value: find_sha(child)
    find_sha(bundles[0]['records'])
    content = first.read_text(encoding='utf-8')
    checks = {'schemaCorrect': all(b['schemaVersion'] == expected['expectedSchema'] for b in bundles),
              'nineteenKindsAnd32RecordsExact': kind_counts == expected['expectedCounts'] and len(bundles[0]['records']) == 32,
              'everyOwnerLocal': all(r['legacyOwnerId'] == 'local' for b in bundles for r in b['records']),
              'twoExportsSameFingerprint': bundles[0]['sourceFingerprint'] == bundles[1]['sourceFingerprint'],
              'twoExportsSameRecords': bundles[0]['records'] == bundles[1]['records'],
              'fakeCanaryAbsent': FAKE not in content,
              'rawBase64Absent': 'imageBase64' not in content and 'base64Data' not in content,
              'imageByteShaReferencePresent': len(image_matches) == 1,
              'fiveTaskStatesExact': task_counts == expected['expectedTasks'],
              'allNegativeExit2': all(r['exitCode'] == 2 for r in receipts[2:]),
              'existingOutputNotOverwritten': sha(first.read_bytes()) == first_sha,
              'noInsideOrRejectedOutput': not inside.exists() and not rejected.exists(),
              'newSourceInventoryUnchanged': inventory(SOURCE) == expected['newInventory'],
              'oldSourceInventoryUnchanged': inventory(ORIGINAL / 'source-consistent') == expected['oldOriginalInventory'],
              'oldExportsUnchanged': old_exports() == expected['oldExportHashes'],
              'sourceFilesManifestCountExact': len(bundles[0]['sourceFiles']) == len(expected['newInventory'])}
    record = {'type': '第175步骤1实际离线分发业务操作，不是JUnit或中心导入通过',
              'completedAt': datetime.datetime.now().astimezone().isoformat(), 'cliSha256': CLI_SHA,
              'expectedPreparedAt': expected['preparedAt'], 'receipts': receipts,
              'counts': kind_counts, 'recordCount': len(bundles[0]['records']),
              'imageReferences': image_matches, 'taskStates': task_counts,
              'sourceFingerprint': bundles[0]['sourceFingerprint'], 'checks': checks,
              'outputs': [{'path': str(p), 'bytes': p.stat().st_size, 'sha256': sha(p.read_bytes())} for p in (first, second)],
              'limitedDataChecksPass': all(checks.values()),
              'observedObsoleteWorkspaceMemberWarning': any('工作区成员' in r['stdout'] for r in receipts)
                  or any('工作区成员' in w for b in bundles for w in b.get('warnings', [])),
              'actualBundleWarnings': bundles[0].get('warnings', []),
              'wordingCheckResult': '未通过' if any('工作区成员' in w for b in bundles for w in b.get('warnings', [])) else '未发现该旧措辞',
              'wholeStepPassed': False,
              'businessScope': '19类合成资料正常两次导出、3种拒绝与来源保护；整点175中心预检/映射未验',
              'providerMcpWechatExecuted': False, 'centralHttpOrDatabaseUsed': False,
              'logsReplaceScreenshotReason': '纯离线无界面产品入口，保存实际stdout/stderr/退出码/独立数据核对，不造终端图',
              'limits': expected['limits']}
    dump(EVIDENCE / '实际产品操作回执.json', record)
    dump(RUN / '实际产品操作回执.json', record)
    print(json.dumps({'recordCount': record['recordCount'], 'kindCount': len(kind_counts), 'checks': checks,
                      'exitCodes': [r['exitCode'] for r in receipts],
                      'obsoleteWorkspaceMemberWarning': record['observedObsoleteWorkspaceMemberWarning'],
                      'receipt': str(EVIDENCE / '实际产品操作回执.json')}, ensure_ascii=False))

def recapture():
    """补原四类日志解码证据，保留首次真实结果及乱码文件，不改业务预期。"""
    assert_paths()
    expected = json.loads((RUN / '独立预期与源清单-执行前.json').read_text(encoding='utf-8'))
    if inventory(SOURCE) != expected['newInventory']:
        raise RuntimeError('日志补证前冻结来源变化')
    output = OUTPUTS / '日志补证-新增正常导出.json'
    if output.exists():
        raise RuntimeError('日志补证目标已存在，不重复或覆盖')
    first = OUTPUTS / '稳定导出-第一次.json'
    old_hash = sha(first.read_bytes())
    receipts = [invoke('6-日志补证-正常稳定导出', SOURCE, output)]
    if receipts[0]['exitCode'] != 0:
        dump(EVIDENCE / '日志解码定向补证.json', {'receipts': receipts, 'stoppedAfterFailure': True})
        raise RuntimeError('日志补证正常导出失败，停止后续')
    fresh = validate_first_bundle(output, expected)
    receipts.append(invoke('7-日志补证-覆盖拒绝', SOURCE, first))
    inside = SOURCE / '日志补证-不应产生的源内目标.json'
    receipts.append(invoke('8-日志补证-源内拒绝', SOURCE, inside))
    rejected = OUTPUTS / '日志补证-不应产生的非法JSON目标.json'
    receipts.append(invoke('9-日志补证-非法JSON拒绝', ORIGINAL / 'source-rejected-json', rejected))
    old_bundle = json.loads(first.read_text(encoding='utf-8'))
    proof = {'type': '原日志UTF-8替换解码缺口的定向补证；不抹除首次数据事实或旧文案失败',
             'completedAt': datetime.datetime.now().astimezone().isoformat(), 'cliSha256': CLI_SHA,
             'originalReceipt': '实际产品操作回执.json', 'originalLogsPreserved': True,
             'receipts': receipts,
             'sourceFingerprint': fresh['sourceFingerprint'], 'sameFrozenRecords': fresh['records'] == old_bundle['records'],
             'sameFrozenSourceFingerprint': fresh['sourceFingerprint'] == old_bundle['sourceFingerprint'],
             'negativeExitCodesExact': [r['exitCode'] for r in receipts[1:]] == [2, 2, 2],
             'existingOutputUnchanged': sha(first.read_bytes()) == old_hash,
             'newSourceUnchanged': inventory(SOURCE) == expected['newInventory'],
             'oldSourceUnchanged': inventory(ORIGINAL / 'source-consistent') == expected['oldOriginalInventory'],
             'oldExportsUnchanged': old_exports() == expected['oldExportHashes'],
             'noNegativePartialOutput': not inside.exists() and not rejected.exists(),
             'actualBundleWarnings': fresh.get('warnings', []),
             'obsoleteWordingActuallyInStdout': '工作区成员' in receipts[0]['stdout'],
             'wordingCheckResult': '未通过', 'wholeStepPassed': False,
             'output': {'path': str(output), 'bytes': output.stat().st_size, 'sha256': sha(output.read_bytes())},
             'limits': expected['limits']}
    dump(EVIDENCE / '日志解码定向补证.json', proof)
    dump(RUN / '日志解码定向补证.json', proof)
    print(json.dumps({k: proof[k] for k in ('sameFrozenRecords', 'sameFrozenSourceFingerprint',
          'negativeExitCodesExact', 'existingOutputUnchanged', 'newSourceUnchanged', 'oldSourceUnchanged',
          'oldExportsUnchanged', 'noNegativePartialOutput', 'obsoleteWordingActuallyInStdout')}, ensure_ascii=False))

def reverify(cli_path, cli_sha):
    """只执行一次新版本正常导出；冻结预期、旧输出及旧日志原样保留。"""
    assert_paths()
    actual_cli = checked_private_cli(cli_path, cli_sha)
    if cli_sha.lower() == CLI_SHA:
        raise RuntimeError('定向复验须显式交接新版本，不能复用已知旧文案版本')
    expected_path = RUN / '独立预期与源清单-执行前.json'
    evidence_expected = EVIDENCE / expected_path.name
    if any(sha(p.read_bytes()) != FROZEN_EXPECTED_SHA for p in (expected_path, evidence_expected)):
        raise RuntimeError('原始独立预期文件变化，不重新冻结或降低预期')
    expected = json.loads(expected_path.read_text(encoding='utf-8'))
    if inventory(SOURCE) != expected['newInventory']:
        raise RuntimeError('新版本复验前来源不等于原冻结清单')
    first = OUTPUTS / '稳定导出-第一次.json'
    if sha(first.read_bytes()) != FIRST_BUNDLE_SHA:
        raise RuntimeError('原始正常导出版本变化，不以新结果替换基线')
    baseline = json.loads(first.read_text(encoding='utf-8'))
    output = OUTPUTS / '文案修复复验-新版本.json'
    receipt_name = '10-文案修复定向复验.json'
    receipt_paths = (EVIDENCE / receipt_name, RUN / receipt_name)
    if output.exists() or any(p.exists() for p in receipt_paths):
        raise RuntimeError('新10输出或回执已存在，不覆盖或重跑')
    for directory in (RUN, SOURCE, OUTPUTS, EVIDENCE):
        if directory.is_symlink() or directory.is_junction():
            raise RuntimeError('复验目录不能是链接或目录联接')
    # 将所有既有本工具证据、私有输出冻结为保护清单，后续只新增10号证据。
    protected = {str(p): sha(p.read_bytes()) for directory in (EVIDENCE, OUTPUTS)
                 for p in directory.iterdir() if p.is_file()}
    protected.update({str(p): sha(p.read_bytes()) for p in
                      (expected_path, RUN / '实际产品操作回执.json', RUN / '日志解码定向补证.json')})
    record = {'type': '第175步骤1文案修复单次正常导出定向复验，不等于中心导入通过',
              'cliPath': str(actual_cli), 'cliSha256': cli_sha.lower(),
              'originalCliSha256': CLI_SHA, 'frozenExpectedSha256': FROZEN_EXPECTED_SHA,
              'originalBundleSha256': FIRST_BUNDLE_SHA, 'expectedPreparedAt': expected['preparedAt'],
              'toolSha256': sha(Path(__file__).read_bytes()), 'receipts': [],
              'providerMcpWechatExecuted': False, 'centralHttpOrDatabaseUsed': False,
              'wholeStepPassed': False, 'limits': expected['limits'],
              'businessScope': '仅原冻结19类32条资料的一次新正常导出、本人映射文案及原证据保护；中心预检/导入/回撤未验',
              'logsReplaceScreenshotReason': '纯离线无界面入口，保存真实流字节、解码日志、退出码与逐项数据核对，不造终端图'}
    try:
        actual = invoke('10-文案修复复验', SOURCE, output, actual_cli, cli_sha)
        record['receipts'].append(actual)
        if actual['exitCode'] != 0:
            raise RuntimeError('新版本正常导出退出码非0，停止依赖操作')
        fresh = validate_first_bundle(output, expected)
        counts = dict(collections.Counter(row['kind'] for row in fresh['records']))
        tasks = dict(collections.Counter(row['payload']['status'] for row in fresh['records'] if row['kind'] == 'TASK'))
        image_matches = []
        def inspect_image(value):
            if isinstance(value, dict):
                if value.get('sha256') == expected['image']['sha256']:
                    image_matches.append(value)
                for child in value.values(): inspect_image(child)
            elif isinstance(value, list):
                for child in value: inspect_image(child)
        inspect_image(fresh['records'])
        warnings = fresh.get('warnings', [])
        bundle_warning_text = '\n'.join(warnings)
        def valid_mapping_warning(text):
            return ('local' in text and '本人' in text and '未映射' in text and '不能导入' in text
                    and '工作区' not in text and '成员' not in text)
        data_checks = {
            'nineteenKindsAnd32RecordsExact': len(counts) == 19 and counts == expected['expectedCounts'] and len(fresh['records']) == 32,
            'all32RecordsEqualOriginal': fresh['records'] == baseline['records'],
            'sameSourceFingerprint': fresh['sourceFingerprint'] == baseline['sourceFingerprint'],
            'sameSourceFilesManifest': fresh['sourceFiles'] == baseline['sourceFiles'],
            'fiveTaskStatesExact': tasks == expected['expectedTasks'],
            'imageByteShaSizeTypeExact': len(image_matches) == 1 and image_matches[0].get('type') == 'image_omitted'
                and image_matches[0].get('size') == expected['image']['bytes'] and image_matches[0].get('mimeType') == 'image/png',
            'sourceFilesMatchIndependentInventory': sorted(fresh['sourceFiles'], key=lambda row: row['name']) ==
                sorted([{'name': row['path'], 'sha256': row['sha256'], 'size': row['bytes']} for row in expected['newInventory']], key=lambda row: row['name']),
            'decodedTextMatchesDiskLogs': actual['decodedTextMatchesDiskLogs'],
            'newCliShaUnchanged': sha(actual_cli.read_bytes()) == cli_sha.lower()}
        wording_checks = {'stdoutRequiresMappingToSelfAndRejectsUnmapped': valid_mapping_warning(actual['stdout']),
                          'bundleRequiresMappingToSelfAndRejectsUnmapped': valid_mapping_warning(bundle_warning_text),
                          'noObsoleteWorkspaceOrMemberWording': all('工作区' not in text and '成员' not in text for text in
                              (actual['stdout'], actual['stderr'], bundle_warning_text))}
        record.update({'counts': counts, 'recordCount': len(fresh['records']), 'taskStates': tasks,
                       'sourceFingerprint': fresh['sourceFingerprint'], 'imageReferences': image_matches,
                       'actualBundleWarnings': warnings, 'dataChecks': data_checks, 'wordingChecks': wording_checks,
                       'limitedDataChecksPass': all(data_checks.values()),
                       'wordingCheckResult': '通过' if all(wording_checks.values()) else '未通过'})
        if not all(data_checks.values()) or not all(wording_checks.values()):
            raise RuntimeError('新版本定向复验存在实际不符，保留未通过')
    except Exception as failed:
        record['stoppedAfterFailure'] = True
        record['failure'] = str(failed)
    finally:
        protection = {'newSourceUnchanged': inventory(SOURCE) == expected['newInventory'],
                      'oldSourceUnchanged': inventory(ORIGINAL / 'source-consistent') == expected['oldOriginalInventory'],
                      'oldExportsUnchanged': old_exports() == expected['oldExportHashes'],
                      'allPriorEvidenceAndBundlesUnchanged': all(Path(p).is_file() and sha(Path(p).read_bytes()) == digest for p, digest in protected.items()),
                      'bothFrozenExpectedFilesUnchanged': all(sha(p.read_bytes()) == FROZEN_EXPECTED_SHA for p in (expected_path, evidence_expected))}
        record['sourceProtectionChecks'] = protection
        record['sourceInventoryAfter'] = inventory(SOURCE)
        record['sourceInventoryBefore'] = expected['newInventory']
        record['protectedPriorFileHashes'] = protected
        record['completedAt'] = datetime.datetime.now().astimezone().isoformat()
        record['limitedReverificationPassed'] = (not record.get('stoppedAfterFailure', False) and all(protection.values()))
        if output.is_file():
            record['output'] = {'path': str(output), 'bytes': output.stat().st_size, 'sha256': sha(output.read_bytes())}
        for path in receipt_paths:
            with path.open('x', encoding='utf-8', newline='') as stream:
                stream.write(json.dumps(record, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'receipt': str(receipt_paths[0]), 'limitedReverificationPassed': record['limitedReverificationPassed'],
                      'wordingCheckResult': record.get('wordingCheckResult', '未完成判定'),
                      'sourceProtectionChecks': protection, 'wholeStepPassed': False}, ensure_ascii=False))
    if not record['limitedReverificationPassed']:
        raise RuntimeError(record.get('failure', '复验后来源或旧证据保护不符'))

if __name__ == '__main__':
    if sys.argv[1] == 'reverify':
        if len(sys.argv) != 4:
            raise RuntimeError('用法：执行19类离线导出.py reverify <新CLI绝对路径> <新CLI_SHA256>')
        reverify(sys.argv[2], sys.argv[3])
    else:
        {'prepare': prepare, 'execute': execute, 'recapture': recapture}[sys.argv[1]]()
