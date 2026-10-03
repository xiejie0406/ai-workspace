"""以本批不可变JAR/依赖实际启动独立Worker，逐例核对正文，不连接业务或外部服务。"""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[4]
MODULE = ROOT / 'ruoyi-backend/ruoyi-paismart'
WORKER = Path(sys.argv[1]).resolve()
if not WORKER.is_relative_to((MODULE / 'target').resolve()) or not WORKER.is_dir():
    raise SystemExit('必须使用本模块target下已审核的本批Worker目录')
JARS = list(WORKER.glob('ruoyi-paismart-*.jar'))
if len(JARS) != 1:
    raise SystemExit('主JAR未唯一确认')
CLASSPATH = os.pathsep.join([str(JARS[0]), str(WORKER / 'lib/*')])
JAVA = shutil.which('java')
if JAVA is None:
    raise SystemExit('未找到已安装Java')
RUN = Path(__file__).parent / '2026-10-03-终版解析进程复验' / datetime.now().strftime('%H%M%S')
RUN.mkdir(parents=True, exist_ok=False)
SAMPLES = MODULE / 'src/test/resources/paismart/format-samples'
EXPECTED = {
    'testWORD.doc': 'Sample Word Document Title',
    'testWORD.docx': 'Sample Word Document Title',
    'testEXCEL.xls': 'Sample Excel Worksheet',
    'testEXCEL.xlsx': 'Sample Excel Worksheet',
    'testPPT.ppt': 'Sample Powerpoint Slide',
    'testPPT.pptx': 'Attachment Test',
    'testOpenOffice2.odt': 'This is a sample Open Office document',
    'testFooter.ods': 'Here is a footer in the center area',
    'testMasterFooter.odp': 'Master footer is here',
    'testPages.pages': 'Sample pages document',
    'testNumbers.numbers': 'Checking Account: 300545668',
    'testKeynote.key': 'A sample presentation',
    'testKeynote2013.key': 'Some random text',
    'testPages2013.pages': 'A second page',
    'testNumbers2013.numbers': 'Groceries',
    'testEPUB.epub': 'The previous headings were subchapters',
}

def pdf_sample():
    stream = b'BT /F1 12 Tf 20 60 Td (Approved PDF process evidence) Tj ET'
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>',
               b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
               b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 100] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
               b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
               b'<< /Length ' + str(len(stream)).encode() + b' >>\nstream\n' + stream + b'\nendstream']
    data = b'%PDF-1.4\n'
    offsets = [0]
    for number, body in enumerate(objects, 1):
        offsets.append(len(data))
        data += str(number).encode() + b' 0 obj\n' + body + b'\nendobj\n'
    xref = len(data)
    data += b'xref\n0 6\n0000000000 65535 f \n'
    data += b''.join(f'{offset:010d} 00000 n \n'.encode() for offset in offsets[1:])
    return data + b'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n' + str(xref).encode() + b'\n%%EOF\n'

fixtures = [(name, (SAMPLES / name).read_bytes(), marker) for name, marker in EXPECTED.items()]
fixtures += [('approved.txt', b'Approved text process evidence', 'Approved text process evidence'),
             ('approved.rtf', b'{\\rtf1\\ansi\\uc1 \\u30693?\\u35782? Unicode process evidence}', '知识 Unicode process evidence'),
             ('approved.pdf', pdf_sample(), 'Approved PDF process evidence')]

def parse(fixture):
    name, content, expected = fixture
    case = RUN / name
    case.mkdir()
    input_path = case / 'input'
    input_path.write_bytes(content)
    output = case / 'body.json'
    temporary = case / 'temp'
    temporary.mkdir()
    command = [JAVA, '-Xmx256m', '-Djava.awt.headless=true', '-Djava.io.tmpdir=' + str(temporary),
               '-cp', CLASSPATH, 'com.ruoyi.paismart.infrastructure.intelligence.ParserWorker',
               str(input_path), str(output), '100', '200000']
    environment = {key: value for key, value in os.environ.items() if key.upper() in {'PATH', 'SYSTEMROOT', 'TMP', 'TEMP'}}
    result = subprocess.run(command, cwd=case, env=environment, capture_output=True, timeout=50)
    (case / 'stderr.log').write_bytes(result.stderr)
    (case / 'stdout.log').write_bytes(result.stdout)
    row = {'file': name, 'sha256': hashlib.sha256(content).hexdigest(), 'expected': expected,
           'exitCode': result.returncode, 'command': command}
    if output.is_file():
        document = json.loads(output.read_text(encoding='utf-8'))
        body = '\n'.join(page['text'] for page in document.get('pages', []))
        row.update({'pageCount': len(document.get('pages', [])), 'textChars': len(body),
                    'expectedBodyFound': expected in body, 'excerpt': body[:250]})
    row['passed'] = result.returncode == 0 and row.get('expectedBodyFound', False)
    print(name + ' passed=' + str(row['passed']) + ' chars=' + str(row.get('textChars')), flush=True)
    return row

with ThreadPoolExecutor(max_workers=2) as pool:
    results = list(pool.map(parse, fixtures))
artifacts = [JARS[0]] + sorted((WORKER / 'lib').glob('*.jar'))
report = {'time': datetime.now().isoformat(), 'scope': '19份固定/自制脱敏样例的独立Worker技术复验；没有OS沙箱或真实业务验收',
          'workerBatch': str(WORKER), 'artifacts': [{'file': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()} for path in artifacts],
          'results': results, 'passed': sum(row['passed'] for row in results), 'total': len(results)}
(RUN / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('evidence=' + str(RUN), flush=True)
raise SystemExit(any(not row['passed'] for row in results))
