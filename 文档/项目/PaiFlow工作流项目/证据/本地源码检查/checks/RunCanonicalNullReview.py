"""只读现有编译classes，复现非DSL JSON随V2默认改变旧幂等hash。"""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[6]
CHECKS = Path(__file__).resolve().parent
OUT = CHECKS.parent
FILES = [ROOT / "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/RunJsonBoundary.java", ROOT / "ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/DslCodec.java", ROOT / "ruoyi-backend/ruoyi-paiflow/target/classes/com/ruoyi/paiflow/service/RunJsonBoundary.class", ROOT / "ruoyi-backend/ruoyi-paiflow/target/classes/com/ruoyi/paiflow/service/DslCodec.class"]


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    before = {p.relative_to(ROOT).as_posix():sha(p) for p in FILES}
    classpath = os.pathsep.join(str(p) for p in [ROOT / "ruoyi-backend/ruoyi-paiflow/target/classes", ROOT / "ruoyi-backend/ruoyi-common/target/classes", Path("C:/Users/admin/.m2/repository/com/alibaba/fastjson2/fastjson2/2.0.64/fastjson2-2.0.64.jar")])
    command = ["java", "--class-path", classpath, str(CHECKS / "RunCanonicalNullReview.java")]
    completed = subprocess.run(command,cwd=ROOT,capture_output=True,text=True,encoding="utf-8")
    (OUT / "非DSL运行JSON空值幂等-独立反例.log").write_text(completed.stdout+completed.stderr,encoding="utf-8")
    if completed.returncode:
        raise RuntimeError((completed.returncode,completed.stderr))
    observations=json.loads(completed.stdout)
    assert all(value["bytesChanged"] and value["currentBoundaryUsesV2"] and value["sameRequestAgainstOldHashWouldCompareDifferent"] for value in observations.values())
    after={p.relative_to(ROOT).as_posix():sha(p) for p in FILES}
    assert before == after
    output={"checkedAt":datetime.now(timezone.utc).isoformat(),"status":"confirmed_non_dsl_canonical_default_compatibility_gap","method":"actual compiled DslCodec and RunJsonBoundary from previous 378/29 freeze; explicit V1 versus V2 and actual default on inert request/event/result JSON","command":command,"exitCode":completed.returncode,"workingDirectory":str(ROOT),"observations":observations,"sourcesAndCompiledClassesBefore":before,"sourcesAndCompiledClassesAfter":after,"sourceWindowChanges":[],"claims":["RunService same owner/idempotencyKey uses hash inequality as409, so old V1 inputHash differs for same nullable request under accidental V2 default", "RunLease append compares duplicate stored canonical bytes, result compares stored resultHash; switching default without record codec changes both comparisons", "No existing real database replay was executed; this proves actual serialization/hash difference plus source comparison trigger"],"rootProposedFix":"非DSL RunJsonBoundary canonical explicitly pins previous V1; DSL version V2/null-preserving canonical remains independent","noMaven":True,"targetWritten":False,"realDatabaseWrites":False,"network":False,"servicesStarted":False,"businessVerified":False,"limitations":["Current DslCodec source may include newly planned V3 while loaded classes are previous frozen V2; functions exercised explicit V1/V2 existed in recorded previous build.","Java source-file launcher compiles only its private probe to JVM temporary space; production classes are read-only."]}
    path=OUT / "非DSL运行JSON空值幂等独立反例.json"
    path.write_text(json.dumps(output,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"path":path.relative_to(ROOT).as_posix(),"sha256":sha(path),"status":output["status"],"cases":observations},ensure_ascii=False,indent=2))


if __name__ == "__main__":
    main()
