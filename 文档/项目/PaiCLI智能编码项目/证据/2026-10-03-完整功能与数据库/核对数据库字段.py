"""读取既有若依配置核对实际MySQL字段；只读元数据，功能样例使用临时表。"""
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import os
from datetime import datetime
from urllib.parse import urlparse

root = pathlib.Path(__file__).resolve().parents[5]
settings = {}
for line in (root / '.env').read_text(encoding='utf-8-sig').splitlines():
    match = re.fullmatch(r'(RUOYI_DB_[A-Z0-9_]+)=(.*)', line)
    if match and (value := match[2].strip().strip('\"\'')):
        settings[match[1]] = value
url = urlparse(settings['RUOYI_DB_URL'].removeprefix('jdbc:'))
if url.hostname not in ('localhost', '127.0.0.1') or url.port != 3306 or url.path != '/ry-vue':
    raise RuntimeError('数据库与批准目标不符')
env = os.environ.copy()
env['MYSQL_PWD'] = settings['RUOYI_DB_PASSWORD']
base = [shutil.which('mysql.exe'), '--protocol=TCP', '--host=' + url.hostname, '--port=3306',
        '--user=' + settings['RUOYI_DB_USERNAME'], '--database=ry-vue', '--default-character-set=utf8mb4',
        '--batch', '--raw', '--skip-column-names']

def query(sql, expected_failure=False):
    result = subprocess.run(base + ['--execute=' + sql], env=env, capture_output=True, timeout=30)
    if expected_failure:
        if result.returncode == 0 or b'3819' not in result.stderr:
            raise RuntimeError('预期CHECK拒绝没有发生；错误正文未输出')
        return 'MySQL CHECK错误3819按预期拒绝'
    if result.returncode:
        raise RuntimeError('数据库核对失败，退出码' + str(result.returncode) + '；错误正文未输出')
    return result.stdout.decode('utf-8').splitlines()

source = root / 'ruoyi-backend/ruoyi-paicli/paicli-api/src/main/resources'
manifest_bytes = (source / 'paicli/functional-schema-v4.json').read_bytes()
manifest = json.loads(manifest_bytes.decode('utf-8-sig'))
rows = query("SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,EXTRA,GENERATION_EXPRESSION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND LEFT(TABLE_NAME,3)='pc_' AND GENERATION_EXPRESSION<>'';")
actual = {(cells[0], cells[1]): cells[2:] for line in rows if len(cells := line.split('\t')) == 5}
checks = []
for table, columns in manifest['columns'].items():
    for field, sql_type, path, *options in columns:
        key = ('pc_' + table, field)
        if key not in actual:
            raise RuntimeError('缺少业务生成字段：' + str(key))
        column_type, extra, expression = actual[key]
        if 'GENERATED' not in extra or 'STORED' not in extra or not expression:
            raise RuntimeError('字段未保持不可独立写入的生成语义：' + str(key))
        if column_type.split('(')[0].upper() != sql_type.split('(')[0].upper():
            raise RuntimeError('业务字段类型不符：' + str(key))
        expected_sources = [part.removeprefix('@') for part in path.split('|')]
        if not all(part in expression for part in expected_sources):
            raise RuntimeError('业务生成字段来源不符：' + str(key))
        checks.append({'table': key[0], 'field': field, 'source': path, 'passed': True})
actual_indexes = set(query("SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND LEFT(TABLE_NAME,3)='pc_';"))
expected_indexes = {'ix_pc_' + table + '_' + field for table, fields in manifest['indexes'].items() for field in fields}
if not expected_indexes <= actual_indexes:
    raise RuntimeError('缺少功能查询索引：' + str(sorted(expected_indexes - actual_indexes)))
sql = (source / 'db/paicli-migration/V4__paicli_function_fields.sql').read_text(encoding='utf-8-sig')
expected_constraints = set(re.findall(r'CONSTRAINT\s+(ck_pc_\w+)', sql))
constraints = set(query("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE() AND LEFT(TABLE_NAME,3)='pc_' AND CONSTRAINT_TYPE='CHECK';"))
if not expected_constraints <= constraints:
    raise RuntimeError('缺少功能约束')
tables = set(query("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND LEFT(TABLE_NAME,3)='pc_';"))
if tables != {'pc_' + t for t in manifest['columns']} | {'pc_flyway_schema_history'}:
    raise RuntimeError('实际表与功能映射不一致')
model_prefix = "CREATE TEMPORARY TABLE pc_model_projection_probe LIKE pc_model_config; INSERT INTO pc_model_projection_probe(kind,state,payload,created_at,updated_at) VALUES ('MODEL','ACTIVE',JSON_OBJECT('contextWindow',128000,'maxOutputTokens',4096,'dsml',true),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));"
sample = query(model_prefix + 'SELECT context_window,max_output_tokens,dsml_enabled FROM pc_model_projection_probe;')
if sample != ['128000\t4096\t1']:
    raise RuntimeError('实际模型业务字段投影不符')
rejection = query("CREATE TEMPORARY TABLE pc_model_invalid_probe LIKE pc_model_config; INSERT INTO pc_model_invalid_probe(kind,state,payload,created_at,updated_at) VALUES ('MODEL','ACTIVE',JSON_OBJECT('contextWindow',1024,'maxOutputTokens',4096),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));", True)
result = {'time': datetime.now().astimezone().isoformat(), 'target': 'localhost:3306/ry-vue',
          'businessTables': len(tables) - 1, 'migrationTables': 1, 'generatedColumns': len(checks),
          'functionalIndexes': len(expected_indexes), 'constraints': sorted(expected_constraints),
          'manifestSHA256': hashlib.sha256(manifest_bytes).hexdigest(), 'fieldChecks': checks,
          'modelSample': sample, 'invalidModelRejected': rejection, 'persistentSampleRowsWritten': 0,
          'scope': '数据库结构和临时样例核对；不计真实业务验收通过'}
pathlib.Path(__file__).with_name('数据库字段实际核对.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({k: v for k, v in result.items() if k != 'fieldChecks'}, ensure_ascii=False))
