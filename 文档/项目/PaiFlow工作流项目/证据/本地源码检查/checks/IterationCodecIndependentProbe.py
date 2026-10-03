"""实际冻结Codec+Policy私有compile/project，保首反例，不执行Engine或Mapper。"""
from pathlib import Path
import argparse
import hashlib,json,subprocess,tempfile
import base64
from datetime import datetime,timezone
ROOT=Path(__file__).resolve().parents[6];CHECKS=Path(__file__).resolve().parent
parser=argparse.ArgumentParser();parser.add_argument('--output-name',default='纯迭代原帧实际Codec独立复核.json');parser.add_argument('--codec-sha',default='5d7c67c62f9b3680c13e14e4f2c3a8a9a02a5b7e10eb7da11472847f1de3214c');args=parser.parse_args()
paths=['ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/'+p for p in [
 'adapter/legacy/LegacyWorkflowStreamCodec.java','service/PureIterationPolicy.java','service/ModelNodeOptions.java',
 'service/ModelOperationJson.java','service/RunJsonBoundary.java','service/RunJsonCodec.java','service/RunProtocolLimits.java',
 'domain/NodeCatalog.java','domain/RunRecord.java','domain/CheckpointRecord.java']]
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
before={p:sha(ROOT/p) for p in paths}
assert before[paths[0]]==args.codec_sha
assert before[paths[1]]=='07df720d4b2b505a4b50b95a44058c5a102353ad9ae216eecba5160f6d1c4684'
saved=ROOT/'python/paiflow-runtime/verification/pure-iteration-java-assignment-check.json'
artifact=ROOT/'python/paiflow-runtime/verification/pure-iteration-java-assignment-original.json'
assert sha(saved)=='fa4fd2b1d374d447b11edbd676ff6c02849ff1723781b101dbc6959410bc937b'
assert sha(artifact)=='59507f49972d93e8d207c3e746c1caf7acd2b7981793303c7a202d346297e816'
task=json.loads(artifact.read_text(encoding='utf-8'));proof=json.loads(saved.read_text(encoding='utf-8'))
temp=Path(tempfile.mkdtemp(prefix='paiflow-iteration-codec-independent-'));files=[]
for path in paths:
 p=temp/Path(path).name;p.write_bytes((ROOT/path).read_bytes());files.append(p)
probe=CHECKS/'IterationCodecIndependentProbe.java';p=temp/probe.name;p.write_bytes(probe.read_bytes());files.append(p)
input_path=temp/'payload.json';input_path.write_text(json.dumps({'assignment':task,'originalFrames':proof['originalFactory']['originalCallbackFrames']},ensure_ascii=False,indent=2),encoding='utf-8')
cp=(CHECKS/'ModelOperationSourceAnalysis.args').read_text(encoding='utf-8').splitlines()[1].strip('"')
jdk=Path('C:/Users/admin/AppData/Local/Programs/Eclipse Adoptium/jdk-21.0.10.7-hotspot/bin');started=datetime.now(timezone.utc).isoformat()
def decode(value):
 try:return value.decode('utf-8'),'utf-8'
 except UnicodeDecodeError:return value.decode('gb18030'),'gb18030'
compile_result=subprocess.run([str(jdk/'javac.exe'),'-J-Dfile.encoding=UTF-8','-encoding','UTF-8','-proc:none','-classpath',cp,'-d',str(temp),*map(str,files)],capture_output=True,timeout=60)
run_result=None;observed=None
if compile_result.returncode==0:
 run_result=subprocess.run([str(jdk/'java.exe'),'-Dfile.encoding=UTF-8','-classpath',str(temp)+';'+cp,'IterationCodecIndependentProbe',str(input_path)],capture_output=True,timeout=30)
 if run_result.stdout.startswith(b'{'):observed=json.loads(decode(run_result.stdout)[0])
after={p:sha(ROOT/p) for p in paths}
record={'startedAtUtc':started,'finishedAtUtc':datetime.now(timezone.utc).isoformat(),'scope':'actual frozen source compile/project in privateTemp, 0Engine/Mapper/DB/HTTP/Provider/Maven/target writes; no real Run authority',
 'sourceBefore':before,'sourceAfter':after,'sourceWindowChanged':[p for p in before if before[p]!=after[p]],'privateTemp':str(temp),
 'originalSavedFrames':{'path':str(saved),'sha256':sha(saved),'frameCount':24},'originalAssignment':{'path':str(artifact),'sha256':sha(artifact),'deadline':task['deadline'],'unchanged':True},
 'explicitPrivateRebinding':'raw.id only rebound to privateRun UUID; node IDs/raw value order/worker private iteration mapping retained, no Engine replay',
 'compile':{'exitCode':compile_result.returncode,'stdout':decode(compile_result.stdout)[0],'stderr':decode(compile_result.stderr)[0]},
 'run':None if run_result is None else {'exitCode':run_result.returncode,'stdout':decode(run_result.stdout)[0],'stderr':decode(run_result.stderr)[0],
  'stdoutEncoding':decode(run_result.stdout)[1],'stdoutBase64':base64.b64encode(run_result.stdout).decode()},'observed':observed,
 'privateHarnessSetupFailures':['首私有Java compound var导致javac1（首证据1429保全；实际Codec未执行）',
  '修私有var后Java actualcompile0/run1，但stdout为GB18030而入口误UTF8解码，未得回执；现保原字节与实测encoding'],
 'probeSha256':sha(probe),'checks':None if observed is None else {'count':len(observed['rows']),'failures':[r for r in observed['rows'] if not r['matches']]}}
out=CHECKS/args.output_name
if out.exists():raise RuntimeError('首证据拒覆盖')
out.write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'path':str(out),'sha256':sha(out),'compileExit':compile_result.returncode,'runExit':None if run_result is None else run_result.returncode,'failures':None if observed is None else record['checks']['failures'],'sourceChanged':record['sourceWindowChanged']},ensure_ascii=False))
if record['sourceWindowChanged'] or compile_result.returncode or run_result is None or run_result.returncode:raise SystemExit(1)
