"""生成五个原项目的只读基线；不调用模型、不连接数据库、不运行工程。

只枚举源文件及对应目标业务目录。哈希相同只表示内容相同，绝不代表业务通过。
使用 --root 指定工作区；输出固定于各项目证据目录，已存在则拒绝覆盖。
"""
from pathlib import Path
from collections import Counter, defaultdict
import argparse
import csv
import hashlib
import json
import os
import zipfile
import re
import tomllib
import xml.etree.ElementTree as ET
from datetime import datetime
from zoneinfo import ZoneInfo

PROJECTS = [
    ('ai-picture-editor-main', 'AI图片编辑项目', 'ai-picture-editor-main.zip', ['ruoyi-backend/ruoyi-aipicture', 'frontend/aipicture-workbench', 'python/aipicture-runtime', 'contracts/aipicture']),
    ('ai-ppt-generator-main', 'AI演示文稿项目', 'ai-ppt-generator-main.zip', ['ruoyi-backend/ruoyi-aippt', 'frontend/aippt-web', 'python/aippt-worker', 'contracts/aippt']),
    ('PaiSmart-main', 'PaiSmart知识库项目', 'PaiSmart-main (2).zip', ['ruoyi-backend/ruoyi-paismart', 'frontend/admin-web/src/views/paismart', 'frontend/admin-web/src/api/paismart', 'frontend/admin-web/src/types/paismart', 'contracts/paismart']),
    ('PaiFlow-main', 'PaiFlow工作流项目', 'PaiFlow-main (1).zip', ['ruoyi-backend/ruoyi-paiflow', 'ruoyi-backend/paiflow-worker-java', 'frontend/paiflow-web', 'python/paiflow-runtime', 'contracts/paiflow']),
    ('paicli-main', 'PaiCLI智能编码项目', 'paicli-main (2).zip', ['ruoyi-backend/ruoyi-paicli', 'frontend/admin-web/src/views/paicli', 'frontend/admin-web/src/api/paicli', 'frontend/admin-web/src/types/paicli.ts', 'contracts/paicli']),
]
GENERATED = {'.git', 'node_modules', '.venv', 'venv', '__pycache__', 'target', 'dist', '.vite', '.pytest_cache', '.mypy_cache'}
CODE = {'.java', '.py', '.ts', '.tsx', '.js', '.jsx', '.vue', '.cjs', '.mjs', '.sh', '.ps1', '.bat', '.cmd'}
ASSETS = {'.png', '.jpg', '.jpeg', '.svg', '.gif', '.webp', '.ico', '.ttf', '.otf', '.woff', '.woff2', '.pptx', '.pdf', '.mp4'}

def digest_file(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()

def classify(rel):
    p = Path(rel)
    if any(x in GENERATED for x in p.parts):
        return '生成物或依赖缓存'
    if p.name == '.env' or p.name.endswith(('.pem', '.key')):
        return '敏感配置候选（只记录元数据）'
    if 'test' in rel.lower() or 'benchmark' in rel.lower() or 'fixture' in rel.lower():
        return '测试或评测资料'
    if p.name.lower().startswith(('license', 'notice', 'copying')):
        return '许可及归属'
    if p.suffix.lower() in CODE:
        return '代码或脚本'
    if p.suffix.lower() in ASSETS:
        return '媒体或字体资产'
    if p.suffix.lower() in {'.md', '.txt', '.html'}:
        return '文档或页面资料'
    return '依赖配置数据或其他资产'

def files_under(path, prune=False):
    if path.is_file():
        yield path
        return
    for base, dirs, files in os.walk(path, followlinks=False):
        if prune:
            dirs[:] = [d for d in dirs if d not in GENERATED]
        for name in sorted(files):
            yield Path(base) / name

def write_csv(path, fields, rows):
    with path.open('w', encoding='utf-8-sig', newline='') as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)

def dependency_inventory(src, out):
    """仅解析声明和已有npm锁；Maven继承属性、Python锁解析不冒称已解析。"""
    target = out / '依赖声明清单.csv'
    if target.exists():
        raise FileExistsError(f'拒绝覆盖已有清单：{target}')
    rows = []
    def add(p, kind, scope, name, version, note=''):
        rows.append({'来源文件': p.relative_to(src).as_posix(), '声明类型': kind, '作用域': scope, '依赖或镜像': name, '版本或约束': version, '解释': note})
    for p in sorted(files_under(src), key=lambda x: x.as_posix()):
        if any(part in GENERATED for part in p.relative_to(src).parts):
            continue
        if p.name == 'pom.xml':
            tree = ET.parse(p)
            ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
            rt = tree.getroot()
            parent = rt.find('m:parent', ns)
            if parent is not None:
                add(p, 'Maven父工程', 'parent', parent.findtext('m:groupId', '', ns) + ':' + parent.findtext('m:artifactId', '', ns), parent.findtext('m:version', '', ns))
            for dep in rt.findall('.//m:dependency', ns):
                add(p, 'Maven声明', dep.findtext('m:scope', 'compile或继承', ns), dep.findtext('m:groupId', '', ns) + ':' + dep.findtext('m:artifactId', '', ns), dep.findtext('m:version', '继承或dependencyManagement', ns), '含profile/管理依赖；未运行dependency:tree，属性未展开')
            for plug in rt.findall('.//m:plugin', ns):
                add(p, 'Maven插件', 'build/profile', plug.findtext('m:groupId', 'org.apache.maven.plugins', ns) + ':' + plug.findtext('m:artifactId', '', ns), plug.findtext('m:version', '继承', ns))
        elif p.name == 'package.json':
            data = json.loads(p.read_text(encoding='utf-8-sig'))
            for group in ['dependencies', 'devDependencies', 'peerDependencies', 'optionalDependencies', 'engines']:
                for name, version in data.get(group, {}).items():
                    add(p, 'npm声明', group, name, str(version), '声明范围；workspace依赖需关联本项目package')
        elif p.name == 'package-lock.json':
            data = json.loads(p.read_text(encoding='utf-8-sig'))
            for loc, meta in data.get('packages', {}).items():
                if loc and meta.get('version'):
                    add(p, 'npm锁定', loc, meta.get('name') or loc.split('node_modules/')[-1], str(meta['version']), '锁内含传递依赖；不证明当前已安装或运行')
        elif p.name == 'pyproject.toml':
            data = tomllib.loads(p.read_text(encoding='utf-8-sig'))
            project_data = data.get('project', {})
            add(p, 'Python运行声明', 'requires-python', 'Python', project_data.get('requires-python', '未声明'))
            for requirement in project_data.get('dependencies', []):
                add(p, 'Python声明', 'dependencies', requirement.split('@')[0].strip() if '@' in requirement else requirement, '见依赖表达式', '直接声明；完整锁及传递依赖见对应uv.lock/poetry.lock')
            for group, requirements in project_data.get('optional-dependencies', {}).items():
                for requirement in requirements:
                    add(p, 'Python声明', 'extra:' + group, requirement.split('@')[0].strip() if '@' in requirement else requirement, '见依赖表达式')
            for group, requirements in data.get('dependency-groups', {}).items():
                for requirement in requirements:
                    add(p, 'Python声明', 'group:' + group, str(requirement), '见依赖表达式')
            poetry = data.get('tool', {}).get('poetry', {})
            for name, version in poetry.get('dependencies', {}).items():
                add(p, 'Poetry声明', 'dependencies', name, str(version.get('version', '需查看声明')) if isinstance(version, dict) else str(version))
        elif p.suffix in {'.yml', '.yaml'} and ('compose' in p.name.lower()):
            for line_no, line in enumerate(p.read_text(encoding='utf-8-sig').splitlines(), 1):
                match = re.match(r'^\s*image:\s*([^#]+)', line)
                if match:
                    add(p, 'Compose镜像声明', f'行{line_no}', match.group(1).strip().strip('\"\''), 'tag/变量；未解析digest', '只提取镜像声明，不读取账号或环境变量值')
        elif p.name.startswith('requirements') and p.suffix in {'.txt', '.in'}:
            for line_no, line in enumerate(p.read_text(encoding='utf-8-sig').splitlines(), 1):
                clean = line.split('#')[0].strip()
                if clean and not clean.startswith('-'):
                    add(p, 'Python requirements声明', f'行{line_no}', clean if '://' not in clean else 'URL依赖（来源值未复制）', '见依赖表达式')
    write_csv(target, ['来源文件', '声明类型', '作用域', '依赖或镜像', '版本或约束', '解释'], rows)
    print(json.dumps({'项目': src.name, '依赖声明行数': len(rows)}, ensure_ascii=False), flush=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--dependencies-only', action='store_true', help='仅生成依赖声明清单，不触碰已有文件基线')
    args = parser.parse_args()
    root = args.root.resolve()
    results = []
    for src_name, project, archive, target_roots in PROJECTS:
        src = root / '参考项目' / src_name
        out = root / '文档' / '项目' / project / '证据' / '2026-10-04-原项目复现调查'
        if args.dependencies_only:
            out.mkdir(parents=True, exist_ok=True)
            dependency_inventory(src, out)
            continue
        planned = ['源文件清单.csv', '源码基线.json', '源文件目标候选对照.csv', '压缩包成员清单.csv']
        if any((out / p).exists() for p in planned):
            raise FileExistsError(f'拒绝覆盖已有基线：{out}')
        out.mkdir(parents=True, exist_ok=True)
        target_by_size = defaultdict(list)
        existing_targets = []
        missing_targets = []
        for rel_root in target_roots:
            target_root = root / rel_root
            if not target_root.exists():
                missing_targets.append(rel_root)
                continue
            existing_targets.append(rel_root)
            for p in files_under(target_root, prune=True):
                if p.is_symlink():
                    continue
                target_by_size[p.stat().st_size].append(p)
        target_hash_cache = {}
        archive_path = root / '参考项目' / archive
        archive_members = {}
        archive_rows = []
        with zipfile.ZipFile(archive_path) as z:
            for info in z.infolist():
                if info.is_dir():
                    continue
                name = info.filename
                prefix = src_name + '/'
                rel = name[len(prefix):] if name.startswith(prefix) else name
                with z.open(info) as f:
                    h = hashlib.sha256()
                    for chunk in iter(lambda: f.read(1024 * 1024), b''):
                        h.update(chunk)
                sh = h.hexdigest()
                archive_members[rel] = sh
                archive_rows.append({'压缩包成员': name, '相对路径': rel, '字节数': info.file_size, 'SHA256': sh})
        rows = []
        mappings = []
        for p in sorted(files_under(src), key=lambda x: x.as_posix()):
            rel = p.relative_to(src).as_posix()
            if p.is_symlink():
                rows.append({'源相对路径': rel, '类别': '符号链接（未读取目标）', '字节数': p.lstat().st_size, 'SHA256': '', '压缩包对照': '待人工核对'})
                continue
            size = p.stat().st_size
            sh = digest_file(p)
            archive_state = '与压缩包一致' if archive_members.get(rel) == sh else ('与压缩包不同' if rel in archive_members else '本地新增')
            category = classify(rel)
            rows.append({'源相对路径': rel, '类别': category, '字节数': size, 'SHA256': sh, '压缩包对照': archive_state})
            candidates = []
            if category != '敏感配置候选（只记录元数据）' and category != '生成物或依赖缓存':
                for tp in target_by_size.get(size, []):
                    if tp not in target_hash_cache:
                        target_hash_cache[tp] = digest_file(tp)
                    if target_hash_cache[tp] == sh:
                        candidates.append(tp.relative_to(root).as_posix())
            mappings.append({'源相对路径': rel, '源SHA256': sh, '类别': category, '字节相同目标候选': ' | '.join(candidates), '静态对照结论': '找到字节相同文件，职责仍需核对' if candidates else '没有字节相同候选，不能据此判断漏迁', '迁移处置': '待关联原有功能与迁移记录', '功能或规则关联': '', '等价重写依据': '', '验证证据入口': ''})
        source_paths = {r['源相对路径'] for r in rows}
        zip_only = sorted(set(archive_members) - source_paths)
        baseline = {
            '取得时间': datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),
            '来源': '本地参考目录与同目录压缩包，不认证远端仓库提交',
            '源目录': src.relative_to(root).as_posix(),
            '压缩包': archive_path.relative_to(root).as_posix(),
            '压缩包SHA256': digest_file(archive_path),
            '文件数': len(rows), '分类统计': dict(Counter(r['类别'] for r in rows)),
            '压缩包文件数': len(archive_rows), '压缩包对照统计': dict(Counter(r['压缩包对照'] for r in rows)),
            '仅压缩包存在的成员': zip_only,
            '目标扫描目录': existing_targets, '不存在目标目录': missing_targets,
            '找到字节相同候选的源文件数': sum(bool(r['字节相同目标候选']) for r in mappings),
            '限制': ['源目录枚举所有普通文件和点文件，不排除生成物；符号链接不跟随', '目标只扫描列明业务目录，跳过生成物及依赖缓存，共享设施由已有设计核对', '字节相同仅证明静态内容相同，可能是分发/参考副本，不能证明处于活跃调用路径', '无字节相同文件可能是批准的等价重写；不得据此计算功能迁移率', '迁移处置与验证关联尚未逐文件签认；沿用既有唯一实施和验收记录', '不运行项目、不调用真实模型、不连接数据库、不执行功能验收'],
        }
        write_csv(out / planned[0], ['源相对路径', '类别', '字节数', 'SHA256', '压缩包对照'], rows)
        write_csv(out / planned[2], list(mappings[0]), mappings)
        write_csv(out / planned[3], ['压缩包成员', '相对路径', '字节数', 'SHA256'], archive_rows)
        (out / planned[1]).write_text(json.dumps(baseline, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        results.append({'项目': project, '源文件数': len(rows), '原包文件数': len(archive_rows), '原包差异': baseline['压缩包对照统计'], '仅原包文件': len(zip_only), '相同候选数': baseline['找到字节相同候选的源文件数']})
        print(json.dumps(results[-1], ensure_ascii=False), flush=True)
    print('完成五项目依赖声明清单。' if args.dependencies_only else '完成五项目基线。', flush=True)

if __name__ == '__main__':
    main()
