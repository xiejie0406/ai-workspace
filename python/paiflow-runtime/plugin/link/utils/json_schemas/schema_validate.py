"""JSON Schema Validation Module.

This module provides functionality for validating JSON data against JSON schemas
using the jsonschema library. It includes utilities for API input parameter validation
and comprehensive error reporting for schema validation failures.
"""

import json

import jsonschema


def api_validate(schema_: str, data_: dict) -> str:
    """
    校验 api 入参
    :param schema_:
    :param data_:
    :return:
    """
    schema_json = json.loads(schema_)
    validator = jsonschema.Draft7Validator(schema_json)
    errs = list(validator.iter_errors(data_))
    err_info = []
    if errs:
        for err in errs:
            err_info.append(f"path: {err.json_path}, message: {err.message}")
    if err_info:
        return ";".join(err_info)
    return ""


# test
# 原可执行服务/供应商示例已剥离；来源和范围见 import-manifest.json。
