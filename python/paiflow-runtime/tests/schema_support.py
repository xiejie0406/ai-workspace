"""只读取工作区机器契约，显式注册URN/文件URI，禁止取网络Schema。"""
import json
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource


def local_validator(path, definition):
    path = Path(path).resolve()
    contracts = Path(__file__).resolve().parents[3] / 'contracts/paiflow'
    registry = Registry()
    for file in [*contracts.glob('*.schema.json'), path]:
        document = json.loads(file.read_text(encoding='utf-8-sig'))
        resource = Resource.from_contents(document)
        registry = registry.with_resource(file.as_uri(), resource)
        if '$id' in document:
            registry = registry.with_resource(document['$id'], resource)
    document = json.loads(path.read_text(encoding='utf-8-sig'))
    Draft202012Validator.check_schema(document)
    return Draft202012Validator({'$ref': path.as_uri() + '#/$defs/' + definition}, registry=registry)
