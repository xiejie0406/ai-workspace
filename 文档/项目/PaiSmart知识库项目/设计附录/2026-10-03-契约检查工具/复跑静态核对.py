"""从保留原文加载静态核对逻辑；只读当前源码和正式契约，不生成或覆盖产物。"""
from pathlib import Path

archive = Path(__file__).resolve().parent
workspace = next(parent for parent in archive.parents if (parent / 'ruoyi-backend').is_dir() and (parent / 'contracts').is_dir())
contract_dir = workspace / 'contracts/paismart'

# 仅执行保留生成器中声明/Controller解析部分，路径写入部分不会执行。
build_file = archive / '.contract-build.py'
build = build_file.read_text(encoding='utf-8').split('paths={}\nfor i,', 1)[0]
build = build.replace('ROOT=Path(__file__).resolve().parents[2]', f'ROOT=Path({str(workspace)!r})')
namespace = {'__file__': str(build_file), '__name__': '__contract_inventory__'}
exec(compile(build, str(build_file), 'exec'), namespace)
current_routes = namespace['routes']

check_file = archive / '.contract-validate.py'
check = check_file.read_text(encoding='utf-8')
check = check.replace('DIR=Path(__file__).parent', f'DIR=Path({str(contract_dir)!r})')
check = check.replace("inventory=json.loads((DIR/'.route-inventory.json').read_text(encoding='utf-8'))", 'inventory=CURRENT_ROUTES')
print('静态核对：读取当前Controller/前端API/正式契约，未修改源码、契约或运行真实服务。')
exec(compile(check, str(check_file), 'exec'), {'__file__': str(check_file), '__name__': '__main__', 'CURRENT_ROUTES': current_routes})
