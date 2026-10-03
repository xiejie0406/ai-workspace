"""一次性来源复制工具；仅创建目标缺失文件，不覆盖已有实现。"""
from pathlib import Path
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET

workspace = Path(__file__).resolve().parents[3]
source = workspace / "参考项目/paicli-main"
target = workspace / "ruoyi-backend/ruoyi-paicli"
manifest = []

def module_for(relative):
    parts = relative.parts
    if parts[-1] in {"TeamExecutionObservationTest.java", "ToolRegistryWebDependenciesTest.java"}:
        return "paicli-eval"
    if parts[0] == "eval":
        return "paicli-eval"
    if parts[0] in {"cli", "tui", "wechat", "harness"}:
        return "paicli-cli"
    if parts[0] == "render" and parts[-1] not in {
        "Renderer.java", "PlainRenderer.java", "StatusInfo.java", "PlainRendererTest.java", "StatusInfoTest.java"
    }:
        return "paicli-cli"
    if parts[0] == "runtime" and parts[-1] not in {
        "CancellationToken.java", "CancellationContext.java", "TaskRunner.java",
        "CancellationTokenTest.java", "CancellationContextTest.java"
    }:
        return "paicli-cli"
    return "paicli-core"

def copy(original, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not destination.exists():
        shutil.copy2(original, destination)
    manifest.append({"source": original.relative_to(workspace).as_posix(),
                     "target": destination.relative_to(workspace).as_posix(),
                     "sourceSha256": hashlib.sha256(original.read_bytes()).hexdigest()})

for flavor in ("main", "test"):
    java = source / f"src/{flavor}/java/com/paicli"
    for original in sorted(java.rglob("*")):
        if original.is_file():
            relative = original.relative_to(java)
            copy(original, target / module_for(relative) / f"src/{flavor}/java/com/paicli" / relative)
    resources = source / f"src/{flavor}/resources"
    for original in sorted(resources.rglob("*")):
        if original.is_file():
            relative = original.relative_to(resources)
            module = "paicli-eval" if relative.parts[0] == "benchmark" else "paicli-cli" if relative.name == "logback.xml" else "paicli-core"
            copy(original, target / module / f"src/{flavor}/resources" / relative)

for original in sorted((source / "benchmarks").rglob("*")):
    if original.is_file():
        copy(original, target / "paicli-eval/benchmarks" / original.relative_to(source / "benchmarks"))

ns = {"m": "http://maven.apache.org/POM/4.0.0"}
original_pom = ET.parse(source / "pom.xml").getroot()
deps = list(original_pom.find("m:dependencies", ns))
terminal = {"core", "lanterna"}
test = {"junit-jupiter", "mockwebserver", "mockito-core"}

def element_text(element):
    return ET.tostring(element, encoding="unicode").replace(' xmlns:ns0="http://maven.apache.org/POM/4.0.0"', '').replace('ns0:', '')

for module in ("paicli-core", "paicli-cli", "paicli-eval"):
    selected = []
    if module != "paicli-core":
        selected.append('''<dependency><groupId>com.ruoyi</groupId><artifactId>paicli-core</artifactId><version>3.9.2</version></dependency>''')
    for dep in deps:
        name = dep.find("m:artifactId", ns).text
        if (module == "paicli-core" and name not in terminal) or (module == "paicli-cli" and name in terminal | test) or (module == "paicli-eval" and name in test):
            selected.append(element_text(dep))
    extra = ""
    if module == "paicli-cli":
        extra = '''<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId><version>3.5.1</version><executions><execution><phase>package</phase><goals><goal>shade</goal></goals><configuration><createDependencyReducedPom>false</createDependencyReducedPom><filters><filter><artifact>*:*</artifact><excludes><exclude>META-INF/*.SF</exclude><exclude>META-INF/*.DSA</exclude><exclude>META-INF/*.RSA</exclude></excludes></filter></filters><transformers><transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer"><mainClass>com.paicli.cli.Main</mainClass></transformer></transformers></configuration></execution></executions></plugin>'''
    pom = f'''<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.ruoyi</groupId><artifactId>{module}</artifactId><version>3.9.2</version>
  <name>PaiCLI {module}</name>
  <properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding><skipTests>false</skipTests></properties>
  <dependencies>{''.join(selected)}</dependencies>
  <build><plugins>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.0</version><configuration><release>17</release></configuration></plugin>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version><configuration><argLine>-Dfile.encoding=UTF-8</argLine><skipTests>${{skipTests}}</skipTests><systemPropertyVariables><user.home>${{project.build.directory}}/test-home</user.home></systemPropertyVariables></configuration></plugin>
    {extra}
  </plugins></build>
</project>
'''
    path = target / module / "pom.xml"
    if not path.exists():
        path.write_text(pom, encoding="utf-8")

(target / "paicli-core/source-migration-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"copiedOrPreserved": len(manifest), "moduleCounts": {m: sum(m in row["target"].split("/") for row in manifest) for m in ("paicli-core", "paicli-cli", "paicli-eval")}}, ensure_ascii=False))
