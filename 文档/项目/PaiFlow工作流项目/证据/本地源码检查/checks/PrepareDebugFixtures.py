from pathlib import Path

root = Path('ruoyi-backend/ruoyi-paiflow/src/test/java/com/ruoyi/paiflow/service')
for name in ['AgentPersistenceTest.java', 'WorkflowDefaultScopePersistenceTest.java', 'RuntimeModelCompilationTest.java', 'PureIterationRunPersistenceTest.java', 'StaticQuestionAnswerTestFixture.java']:
    path = root / name
    source = path.read_text(encoding='utf-8')
    if '018-paiflow' not in source:
        marker = '017-paiflow-run-json-codec.sql' if name in ['StaticQuestionAnswerTestFixture.java', 'PureIterationRunPersistenceTest.java'] else '015-paiflow-execution-codec.sql'
        prefix = 'sql/' if name in ['AgentPersistenceTest.java', 'WorkflowDefaultScopePersistenceTest.java'] else ''
        source = source.replace(f'"{prefix}{marker}"', f'"{prefix}{marker}","{prefix}018-paiflow-workflow-debug-snapshot.sql"')
    assert '018-paiflow' in source, name
    if '.replace(" CHARACTER SET ascii COLLATE ascii_bin","")' not in source:
        source = source.replace('.replace(" STORED","")', '.replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" STORED","")')
    assert '.replace(" CHARACTER SET ascii COLLATE ascii_bin","")' in source, name
    path.write_text(source, encoding='utf-8', newline='\r\n')
path = root / 'WorkflowPersistenceTest.java'
source = path.read_text(encoding='utf-8').replace('java.nio.file.Files.readString(java.nio.file.Path.of("sql/015-paiflow-execution-codec.sql"),StandardCharsets.UTF_8)', 'java.nio.file.Files.readString(java.nio.file.Path.of("sql/015-paiflow-execution-codec.sql"),StandardCharsets.UTF_8)+"\\n"+java.nio.file.Files.readString(java.nio.file.Path.of("sql/018-paiflow-workflow-debug-snapshot.sql"),StandardCharsets.UTF_8)')
if '.replace(" CHARACTER SET ascii COLLATE ascii_bin","")' not in source:
    source = source.replace('.replace(" STORED","")', '.replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" STORED","")')
assert '018-paiflow' in source
assert '.replace(" CHARACTER SET ascii COLLATE ascii_bin","")' in source
path.write_text(source, encoding='utf-8', newline='\r\n')
print('six fixture adaptations saved')
