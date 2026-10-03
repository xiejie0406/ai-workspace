#!/usr/bin/env python3
"""只读处理人工批准的旧库 JSON 快照，输出供审核的 SQL；本工具不连接任何数据库。

输入必须来自同一写冻结时点，包含全部已知表（空表也须显式 []）、Redis 余额、
对象 SHA-256 清单及人工旧用户映射。不会复制密码、JWT、Provider 密文或平台角色。
生成 SQL 必须另行审核执行；存在目标数据、未映射用户和来源变化时由断言拒绝。
"""
import argparse
import hashlib
import json
import re
import uuid
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

TABLES = {
    'users': ('id', 'username', 'password', 'role', 'org_tags', 'primary_org', 'created_at', 'updated_at'),
    'organization_tags': ('tag_id', 'name', 'description', 'parent_tag', 'upload_max_size_bytes', 'created_by', 'created_at', 'updated_at'),
    'file_upload': ('id', 'file_md5', 'file_name', 'total_size', 'status', 'user_id', 'org_tag', 'is_public', 'estimated_embedding_tokens', 'estimated_chunk_count', 'actual_embedding_tokens', 'actual_chunk_count', 'vectorization_status', 'vectorization_error_message', 'created_at', 'merged_at'),
    'chunk_info': ('id', 'file_md5', 'chunk_index', 'chunk_md5', 'storage_path'),
    'document_vectors': ('vector_id', 'file_md5', 'chunk_id', 'text_content', 'page_number', 'anchor_text', 'chunk_context', 'model_version', 'user_id', 'org_tag', 'is_public'),
    'conversation_sessions': ('id', 'user_id', 'conversation_id', 'title', 'status', 'created_at', 'updated_at'),
    'conversations': ('id', 'user_id', 'question', 'answer', 'conversation_id', 'reference_mappings_json', 'timestamp'),
    'document_conflicts': ('id', 'pair_key', 'new_file_md5', 'new_chunk_id', 'new_file_name', 'existing_file_md5', 'existing_chunk_id', 'existing_file_name', 'similarity', 'masked_similarity', 'new_snippet', 'existing_snippet', 'new_values', 'existing_values', 'llm_reason', 'status', 'created_at', 'resolved_by', 'resolved_at', 'resolution_note'),
    'invite_codes': ('id', 'code', 'max_uses', 'used_count', 'expires_at', 'enabled', 'created_by', 'created_at', 'updated_at'),
    'model_provider_configs': ('id', 'config_scope', 'provider_code', 'display_name', 'api_style', 'api_base_url', 'model_name', 'api_key_ciphertext', 'embedding_dimension', 'enabled', 'active', 'updated_by', 'created_at', 'updated_at'),
    'rate_limit_configs': ('config_key', 'single_max', 'single_window_seconds', 'minute_max', 'minute_window_seconds', 'day_max', 'day_window_seconds', 'updated_by', 'created_at', 'updated_at'),
    'recharge_packages': ('id', 'package_name', 'package_price', 'package_desc', 'package_benefit', 'llm_token', 'embedding_token', 'enabled', 'deleted', 'sort_order', 'created_at', 'updated_at'),
    'recharge_orders': ('id', 'trade_no', 'user_id', 'package_id', 'amount', 'llm_token', 'embedding_token', 'wx_transaction_id', 'status', 'description', 'pay_time', 'created_at', 'updated_at'),
    'user_token_record': ('id', 'user_id', 'record_date', 'token_type', 'change_type', 'amount', 'balance_before', 'balance_after', 'reason', 'remark', 'request_count', 'created_at'),
    'user_daily_chat_count': ('id', 'user_id', 'record_date', 'chat_request_count', 'created_at', 'updated_at'),
}
SECRET_FIELDS = {'password', 'passwordhash', 'pwd', 'apikeyciphertext', 'apikey', 'jwt', 'accesstoken', 'refreshtoken', 'secretkey', 'privatekey', 'privatekeypem', 'clientsecret', 'authorization'}
NAMESPACE = uuid.UUID('7d545b60-0499-4d78-b075-c0d6fc4af6a1')

def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))

def digest(value):
    return hashlib.sha256(canonical(value).encode('utf-8')).hexdigest()

def identifier(table, key):
    return str(uuid.uuid5(NAMESPACE, table + ':' + str(key)))

def clean(value):
    if isinstance(value, dict):
        return {k: clean(v) for k, v in value.items() if re.sub(r'[^a-z0-9]', '', str(k).lower()) not in SECRET_FIELDS}
    if isinstance(value, list):
        return [clean(v) for v in value]
    return value

def integer(value, *, nonnegative=False):
    if isinstance(value, bool) or not isinstance(value, int) or abs(value) > 9223372036854775807 or (nonnegative and value < 0):
        raise ValueError('整数缺失、越界或非法负数')
    return value

def string(value, limit=1000):
    if not isinstance(value, str) or not value or len(value) > limit or '\0' in value:
        raise ValueError('必要字符串缺失或超出长度')
    return value

def utc_offset(value):
    if not isinstance(value, str) or not re.fullmatch(r'[+-](?:0\d|1[0-4]):[0-5]\d', value):
        raise ValueError('需要人工确认sourceUtcOffset和targetUtcOffset，不能猜测旧本地时间时区')
    hours, minutes = (int(part) for part in value[1:].split(':'))
    if hours == 14 and minutes:
        raise ValueError('UTC时区偏移超出范围')
    return timezone(timedelta(minutes=(hours * 60 + minutes) * (-1 if value[0] == '-' else 1)))

def sql(value):
    if value is None:
        return 'NULL'
    if isinstance(value, bool):
        return '1' if value else '0'
    if isinstance(value, int):
        return str(integer(value))
    # 十六进制 UTF-8 字符串，避免内容中的引号、反斜杠和 SQL 字样被当成命令。
    return "CONVERT(X'%s' USING utf8mb4)" % str(value).encode('utf-8').hex()

class Preparation:
    def __init__(self, snapshot, mapping, batch, target_schema):
        if snapshot.get('schemaVersion') != 1 or mapping.get('approved') is not True:
            raise ValueError('快照版本或人工映射批准标记不满足')
        self.snapshot, self.mapping, self.batch = snapshot, mapping, batch
        self.schema = string(target_schema, 64)
        if not re.fullmatch(r'[A-Za-z0-9_]+', self.schema) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', batch):
            raise ValueError('schema 或批次标识无效')
        self.frozen = string(snapshot.get('writeFrozenAt'), 64)
        self.source_zone, self.target_zone = utc_offset(mapping.get('sourceUtcOffset')), utc_offset(mapping.get('targetUtcOffset'))
        self.timestamp(self.frozen)
        self.snapshot_id = string(snapshot.get('snapshotId'), 128)
        self.operator = integer(mapping.get('operatorUserId'), nonnegative=True)
        if not self.operator:
            raise ValueError('需要已批准操作人的平台用户ID')
        tables = snapshot.get('tables')
        if not isinstance(tables, dict) or set(tables) != set(TABLES):
            raise ValueError('必须提供全部已核实表；未知或缺失表不得静默跳过')
        for table, rows in tables.items():
            if not isinstance(rows, list) or len(rows) > 1000000:
                raise ValueError('表快照结构错误或超出本工具容量')
            for row in rows:
                if not isinstance(row, dict) or set(row) - set(TABLES[table]):
                    raise ValueError('表 %s 包含未经审核的新字段' % table)
        for extra in ('redisBalances', 'objectManifest', 'evaluationReports', 'humanReviews', 'indexMetadata'):
            if not isinstance(snapshot.get(extra), list):
                raise ValueError('缺少独立快照：' + extra)
        self.tables = tables
        self.users = {}
        self.aliases = {}
        self.groups = defaultdict(list)
        self.raw_rows = {}
        self.document_ids = {}
        self.chunk_ids = {}
        self.sessions = set()
        self.consumed = defaultdict(int)
        self.imported_user_ids = set()
        self.warnings = []
        self.object_operations = []
        self.objects = {str(r.get('fileUploadId')): r for r in snapshot['objectManifest']}
        if len(self.objects) != len(snapshot['objectManifest']):
            raise ValueError('对象清单fileUploadId重复')
        if set(self.objects) - {str(row.get('id')) for row in tables['file_upload']}:
            raise ValueError('对象清单包含快照之外文件')
        mappings = mapping.get('users')
        if not isinstance(mappings, list):
            raise ValueError('需要旧账户到平台账户的人工映射')
        approved = {str(r.get('oldId')): r for r in mappings}
        targets = set()
        for old in tables['users']:
            source_id = str(old['id'])
            item = approved.get(source_id)
            if not item or item.get('oldUsername') != old.get('username') or item.get('confirmed') is not True:
                raise ValueError('存在未经人工确认的旧账户映射')
            target = integer(item.get('targetUserId'), nonnegative=True)
            if target <= 0 or target in targets:
                raise ValueError('不得自动合并旧用户或建立无效平台账户')
            targets.add(target)
            self.users[source_id] = target
            for alias in (source_id, old['username']):
                if alias in self.aliases and self.aliases[alias] != target:
                    raise ValueError('旧ID与用户名别名存在冲突，需要人工消歧')
                self.aliases[alias] = target
        if set(approved) != set(self.users):
            raise ValueError('人工账户映射包含快照外账户')
        self.imported_user_ids = targets
        self.tags = {r['tag_id']: r for r in tables['organization_tags']}
        self.spaces = mapping.get('spaces', {})
        if set(self.spaces) != set(self.tags):
            raise ValueError('每个旧组织（含DEFAULT/私人标签）均须人工确认目标类型和范围')
        personal_owners = set()
        for tag, old in self.tags.items():
            policy = self.spaces[tag]
            if policy.get('confirmed') is not True or policy.get('type') not in ('PERSONAL', 'ORGANIZATION'):
                raise ValueError('组织类型尚未批准')
            if policy['type'] == 'PERSONAL':
                owner = self.user(policy.get('ownerOldId'))
                if owner in personal_owners:
                    raise ValueError('同一平台用户映射多个个人空间，须人工消重')
                personal_owners.add(owner)
            parent = old.get('parent_tag')
            if parent in self.spaces and self.spaces[parent]['type'] == 'PERSONAL':
                raise ValueError('个人空间不能作为组织父空间')
            seen, current = set(), tag
            while current:
                if current in seen or current not in self.tags:
                    raise ValueError('旧组织存在父环或父组织缺失')
                seen.add(current)
                current = self.tags[current].get('parent_tag')
        for table, rows in tables.items():
            key_name = {'organization_tags': 'tag_id', 'document_vectors': 'vector_id', 'rate_limit_configs': 'config_key'}.get(table, 'id')
            for row in rows:
                key = str(row[key_name])
                if (table, key) in self.raw_rows:
                    raise ValueError('旧表主键重复')
                self.raw_rows[table, key] = clean(row)

    def user(self, alias):
        key = str(alias)
        if key not in self.aliases:
            raise ValueError('业务记录引用未映射账户')
        return self.aliases[key]

    def date(self, row, field='created_at'):
        return row.get(field) or self.frozen

    def timestamp(self, value):
        try:
            parsed = datetime.fromisoformat(string(value, 64).replace('Z', '+00:00'))
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=self.source_zone)
            return parsed.astimezone(self.target_zone).replace(tzinfo=None).isoformat(sep=' ', timespec='microseconds')
        except (TypeError, ValueError) as exc:
            raise ValueError('日期字段无效，不能输出无法回读的MySQL时间') from exc

    def add(self, source, source_id, table, row):
        row = {key: self.timestamp(value) if key.endswith('_at') and value is not None else value for key, value in row.items()}
        self.groups[source, str(source_id)].append((table, row))

    def archive(self, table, key, row):
        self.add(table, key, 'ps_legacy_archive', {'source_table': table, 'source_id': str(key), 'sanitized_json': canonical(clean(row)), 'migration_batch_id': self.batch})

    def conversion_hash(self, source, key, rows):
        # 绑定实际转换；批次归属不改变等价性，归档自身不参与指纹，避免递归。
        targets = [{'table': table, 'row': {field: value for field, value in row.items()
                                          if field != 'migration_batch_id'}}
                   for table, row in rows if table != 'ps_legacy_archive']
        operations = []
        if source == 'file_upload':
            target_id = identifier(source, key)
            operations = [row for row in self.object_operations
                          if row.get('id', row.get('uploadId')) == target_id]
        return digest({'format': 'paismart-conversion-v1', 'sourceTable': source, 'sourceId': key,
                       'sourceHash': digest(self.raw_rows[source, key]),
                       'targets': sorted(targets, key=canonical),
                       'objectOperations': sorted(operations, key=canonical)})

    def convert(self):
        for old in self.tables['users']:
            key = str(old['id']); target = self.user(key)
            self.add('users', key, 'ps_member', {'user_id': target, 'status': 'ACTIVE', 'created_at': self.date(old), 'updated_at': self.date(old, 'updated_at')})
            self.add('users', key, 'ps_legacy_identity_map', {'source_user_id': key, 'user_id': target, 'migration_batch_id': self.batch, 'source_hash': digest(clean(old)), 'created_by': self.operator})
            member_tags = list(filter(None, (old.get('org_tags') or '').split(',')))
            if len(set(member_tags)) != len(member_tags) or (old.get('primary_org') and old['primary_org'] not in member_tags):
                raise ValueError('旧成员关系重复或主空间不属于成员，须人工核对')
            for tag in member_tags:
                if tag not in self.tags:
                    raise ValueError('用户引用未导出组织标签')
                if self.spaces[tag]['type'] == 'PERSONAL':
                    if self.user(self.spaces[tag].get('ownerOldId')) != target:
                        raise ValueError('旧个人成员关系与确认owner不符，不能批量强占')
                managers = self.mapping.get('spaceManagers', [])
                manager = any(str(m.get('oldUserId')) == key and m.get('tagId') == tag and m.get('confirmed') is True for m in managers)
                self.add('users', key, 'ps_member_space', {'user_id': target, 'space_id': identifier('organization_tags', tag), 'primary_flag': old.get('primary_org') == tag, 'upload_allowed': True, 'manager_allowed': manager})
        for tag, old in self.tags.items():
            policy = self.spaces[tag]; personal = policy['type'] == 'PERSONAL'
            owner = self.user(policy.get('ownerOldId')) if personal else None
            if personal and old.get('parent_tag'):
                raise ValueError('私人空间不能带组织父关系')
            if tag == 'DEFAULT' and personal:
                raise ValueError('原默认组织不能映射为私人空间')
            self.add('organization_tags', tag, 'ps_space', {'id': identifier('organization_tags', tag), 'parent_id': identifier('organization_tags', old['parent_tag']) if old.get('parent_tag') else None, 'name': string(old['name'], 120), 'description': string(old['description'], 5000) if old.get('description') else '', 'system_key': 'DEFAULT' if tag == 'DEFAULT' else None, 'type': policy['type'], 'owner_id': owner, 'upload_limit': -1, 'upload_max_size_bytes': old.get('upload_max_size_bytes'), 'created_at': self.date(old), 'updated_at': self.date(old, 'updated_at')})
        for old in self.tables['file_upload']:
            key = str(old['id']); owner = self.user(old['user_id'])
            if old.get('status') != 1:
                obj = self.objects.get(key)
                if obj and obj.get('kind') == 'UPLOAD':
                    self.convert_incomplete_upload(old, owner, obj)
                    continue
                if self.mapping.get('terminateIncompleteUploads') is not True:
                    raise ValueError('存在未完成上传，必须补分片manifest或明确批准终止；不能伪装完整文件')
                self.warnings.append('已按批准策略终止未完成上传，保留原manifest归档')
                continue
            obj = self.objects.get(key)
            if not obj or not re.fullmatch('[a-f0-9]{64}', str(obj.get('sha256', ''))) or obj.get('verified') is not True or obj.get('byteSize') != old['total_size']:
                raise ValueError('正式文档缺少独立校验的SHA-256/字节对象清单')
            document_id = identifier('file_upload', key)
            if not str(obj.get('targetKey', '')).startswith('paismart/documents/' + document_id + '/'):
                raise ValueError('目标对象必须归属确定的本项目文档目录')
            tag = old.get('org_tag')
            if tag not in self.tags:
                raise ValueError('文档所属旧组织缺失')
            policy = self.spaces[tag]
            visibility = 'PRIVATE' if policy['type'] == 'PERSONAL' else ('PUBLIC' if old.get('is_public') else 'ORGANIZATION')
            if policy['type'] == 'PERSONAL' and self.user(policy.get('ownerOldId')) != owner:
                raise ValueError('私人空间归属与旧文档上传者不一致，需人工处理')
            explicit = self.mapping.get('documentVisibility', {}).get(key)
            if explicit:
                if explicit not in ('PRIVATE', 'ORGANIZATION', 'PUBLIC') or (policy['type'] == 'PERSONAL' and explicit != 'PRIVATE'):
                    raise ValueError('文档可见范围映射非法')
                visibility = explicit
            lookup = (str(old['file_md5']), owner)
            if lookup in self.document_ids:
                raise ValueError('同一MD5与用户对应多个旧文档，需要人工消重')
            self.document_ids[lookup] = document_id
            job_id = identifier('file_upload_job', key)
            self.add('file_upload', key, 'ps_document', {'id': document_id, 'user_id': owner, 'space_id': identifier('organization_tags', tag), 'visibility': visibility, 'file_name': string(old['file_name'], 255), 'sha256': obj['sha256'], 'legacy_md5': old['file_md5'], 'object_key': obj['targetKey'], 'byte_size': integer(old['total_size'], nonnegative=True), 'mime_type': obj.get('mimeType', 'application/octet-stream'), 'status': 'QUEUED', 'version': 1, 'process_job_id': job_id, 'estimated_tokens': old.get('estimated_embedding_tokens') or 0, 'actual_tokens': old.get('actual_embedding_tokens') or 0, 'created_at': self.date(old)})
            if obj.get('sourceKey'):
                if obj['sourceKey'] not in ('merged/' + old['file_md5'], 'merged/' + old['file_name']):
                    raise ValueError('旧正式对象来源键与文件元数据不一致')
                self.object_operations.append({'id': document_id, 'legacySourceKey': obj['sourceKey'], 'targetKey': obj['targetKey'], 'sha256': obj['sha256'], 'byteSize': old['total_size']})
            # 迁入待处理文档有真实中心任务和outbox；消费及付费仍需另行显式启用。
            self.add('file_upload', key, 'ps_job', {'id': job_id, 'user_id': owner, 'document_id': document_id, 'type': 'DOCUMENT_PROCESS', 'state': 'QUEUED', 'payload_json': canonical({'documentId': document_id, 'contentVersion': 1}), 'request_key': 'legacy-document:' + key, 'created_at': self.frozen, 'updated_at': self.frozen})
            self.add('file_upload', key, 'ps_outbox', {'id': identifier('file_upload_outbox', key), 'job_id': job_id, 'topic': 'paismart.jobs', 'payload_json': canonical({'jobId': job_id}), 'created_at': self.frozen})
        for old in self.tables['document_vectors']:
            key = str(old['vector_id']); owner = self.user(old['user_id']); doc = self.document_ids.get((str(old['file_md5']), owner))
            if not doc:
                raise ValueError('内容块无法绑定独立用户文档，不按全局MD5猜测')
            chunk = identifier('document_vectors', key)
            pair = (doc, old['chunk_id'])
            if pair in self.chunk_ids:
                raise ValueError('同版本内容块序号重复')
            self.chunk_ids[pair] = chunk
            self.add('document_vectors', key, 'ps_document_chunk', {'id': chunk, 'document_id': doc, 'content_version': 1, 'chunk_no': integer(old['chunk_id'], nonnegative=True), 'page_no': old.get('page_number'), 'text_content': old.get('text_content') or '', 'context_text': old.get('chunk_context'), 'anchor_text': (old.get('anchor_text') or '')[:256], 'token_count': 0, 'vector_json': None, 'embedding_revision': None, 'created_at': self.frozen})
        sessions = {}
        for old in self.tables['conversation_sessions']:
            key = str(old['id']); owner = self.user(old['user_id']); logical = string(old['conversation_id'], 160)
            if (owner, logical) in sessions:
                raise ValueError('旧会话逻辑ID重复，需人工消重，不能覆盖已有会话')
            sessions[owner, logical] = identifier('conversation_sessions', str(owner) + ':' + logical)
            self.add('conversation_sessions', key, 'ps_conversation', {'id': sessions[owner, logical], 'user_id': owner, 'title': (old.get('title') or '旧会话')[:160], 'archived': old.get('status') not in ('ACTIVE', None), 'created_at': self.date(old), 'updated_at': self.date(old, 'updated_at')})
        for old in self.tables['conversations']:
            key = str(old['id']); owner = self.user(old['user_id']); logical = old.get('conversation_id') or 'legacy-' + key
            sid = sessions.get((owner, logical))
            if sid is None:
                sid = identifier('conversation_sessions', str(owner) + ':' + logical); sessions[owner, logical] = sid
                self.add('conversations', key, 'ps_conversation', {'id': sid, 'user_id': owner, 'title': (old.get('question') or '旧会话')[:160], 'archived': False, 'created_at': self.date(old, 'timestamp'), 'updated_at': self.date(old, 'timestamp')})
            assistant = identifier('conversations', key + ':assistant')
            refs = old.get('reference_mappings_json') or '{}'
            try:
                refs = json.loads(refs) if isinstance(refs, str) else refs
            except json.JSONDecodeError as exc:
                raise ValueError('旧引用JSON损坏，必须先处理') from exc
            resolved = []
            reference_numbers = set()
            for number, reference in refs.items():
                if isinstance(reference, str):
                    reference = {'fileMd5': reference}
                md5 = reference.get('fileMd5') or reference.get('file_md5')
                doc = self.document_ids.get((str(md5), owner))
                if doc is None:
                    approved = self.mapping.get('referenceDocuments', {}).get(key + ':' + str(number))
                    doc = identifier('file_upload', approved) if approved else None
                chunk_no = reference.get('chunkId', reference.get('chunk_id'))
                chunk = self.chunk_ids.get((doc, chunk_no))
                if not doc or not chunk:
                    raise ValueError('旧引用不能安全绑定文档/块；需要referenceDocuments人工映射')
                reference_no = int(number)
                if reference_no < 1 or reference_no in reference_numbers:
                    raise ValueError('旧引用编号重复或无效')
                reference_numbers.add(reference_no)
                rid = identifier('reference', key + ':' + str(number)); item = {'id': rid, 'documentId': doc, 'contentVersion': 1, 'page': reference.get('pageNumber'), 'anchor': reference.get('anchorText') or '', 'text': reference.get('matchedChunkText') or reference.get('evidenceSnippet') or reference.get('content') or '', 'title': reference.get('fileName') or '旧引用'}
                resolved.append(item)
                verification = reference.get('verification') or {field: reference[field] for field in ('citationStatus', 'citationReason', 'retrievalMode', 'retrievalLabel', 'retrievalQuery', 'score') if field in reference}
                self.add('conversations', key, 'ps_reference', {'id': rid, 'message_id': assistant, 'document_id': doc, 'content_version': 1, 'chunk_id': chunk, 'reference_no': reference_no, 'page_no': item['page'], 'anchor_text': str(item['anchor'])[:256], 'evidence_text': item['text'], 'verification_json': canonical(verification) if verification else None, 'created_at': self.date(old, 'timestamp')})
            for role in ('user', 'assistant'):
                self.add('conversations', key, 'ps_chat_message', {'id': identifier('conversations', key + ':' + role), 'conversation_id': sid, 'role': role, 'content': old.get('question' if role == 'user' else 'answer') or '', 'references_json': canonical(resolved) if role == 'assistant' else None, 'state': 'COMPLETED', 'created_at': self.date(old, 'timestamp')})
        for old in self.tables['recharge_packages']:
            key = str(old['id'])
            self.add('recharge_packages', key, 'ps_recharge_package', {'id': identifier('recharge_packages', key), 'name': old['package_name'], 'amount_fen': integer(old['package_price'], nonnegative=True), 'llm_tokens': integer(old['llm_token'], nonnegative=True), 'embedding_tokens': integer(old['embedding_token'], nonnegative=True), 'description': (old.get('package_desc') or '')[:500], 'benefit': old.get('package_benefit') or '', 'enabled': bool(old.get('enabled')), 'deleted': bool(old.get('deleted')), 'sort_order': old.get('sort_order') or 0, 'created_at': self.date(old)})
        state_map = {'NOT_PAY': 'NOT_PAY', 'PAYING': 'PAYING', 'SUCCEED': 'SUCCEEDED', 'FAIL': 'FAILED', 'CANCELLED': 'CLOSED'}
        for old in self.tables['recharge_orders']:
            key = str(old['id']); owner = self.user(old['user_id']); state = state_map.get(old['status'])
            if state is None:
                raise ValueError('旧订单状态无法映射')
            if state == 'SUCCEEDED':
                string(old.get('wx_transaction_id'), 128)
            package = old.get('package_id'); package_id = identifier('recharge_packages', package) if package else None
            if package and not any(str(p['id']) == str(package) for p in self.tables['recharge_packages']):
                raise ValueError('旧订单缺套餐快照，不能套用新价')
            self.add('recharge_orders', key, 'ps_recharge_order', {'id': identifier('recharge_orders', key), 'user_id': owner, 'request_key': 'legacy:' + key, 'trade_no': string(old['trade_no'], 64), 'package_id': package_id, 'snapshot_json': canonical({'legacy': True, 'amountFen': old['amount'], 'chatTokens': old['llm_token'], 'embeddingTokens': old['embedding_token'], 'packageId': package_id}), 'amount_fen': integer(old['amount'], nonnegative=True), 'llm_tokens': integer(old['llm_token'], nonnegative=True), 'embedding_tokens': integer(old['embedding_token'], nonnegative=True), 'state': state, 'transaction_id': old.get('wx_transaction_id') or None, 'expires_at': self.date(old), 'paid_at': old.get('pay_time'), 'created_at': self.date(old)})
        daily = defaultdict(lambda: {'used_tokens': 0, 'request_count': 0})
        for old in self.tables['user_token_record']:
            if old.get('change_type') == 'CONSUME':
                uid, scope = self.user(old['user_id']), old['token_type']
                amount = integer(old['amount'], nonnegative=True)
                self.consumed[uid, scope] += amount
                stat = daily[uid, old['record_date'], scope]
                stat['used_tokens'] += amount
                stat['request_count'] += integer(old.get('request_count', 0), nonnegative=True)
        for uid, day, scope in daily:
            key = str(uid) + ':' + str(day) + ':' + scope
            stat = daily[uid, day, scope]
            self.raw_rows['daily_usage_snapshot', key] = stat
            self.add('daily_usage_snapshot', key, 'ps_daily_usage', {'user_id': uid, 'business_day': day, 'scope': scope, 'used_tokens': stat['used_tokens'], 'request_count': stat['request_count']})
        balance_keys = set()
        for balance in self.snapshot['redisBalances']:
            owner = self.user(balance['oldUserId']); scope = balance['scope']
            if scope not in ('LLM', 'EMBEDDING') or (owner, scope) in balance_keys or balance.get('snapshotId') != self.snapshot_id:
                raise ValueError('Redis余额快照范围、重复或冻结版本错误')
            balance_keys.add((owner, scope)); available = integer(balance['availableTokens']); consumed = self.consumed[owner, scope]
            if balance.get('totalRequests') is None:
                raise ValueError('必须同时保存原Redis总请求次数，不能用临时token或流水推断')
            integer(balance['totalRequests'], nonnegative=True)
            key = str(balance['oldUserId']) + ':' + scope
            self.raw_rows['redis_balances', key] = clean(balance)
            self.add('redis_balances', key, 'ps_token_account', {'user_id': owner, 'scope': scope, 'available': max(0, available), 'frozen': 0, 'consumed': consumed, 'debt': max(0, -available)})
            self.add('redis_balances', key, 'ps_token_ledger', {'id': identifier('redis_balances', key), 'user_id': owner, 'scope': scope, 'operation_key': 'legacy-opening:' + key, 'action': 'MIGRATION_OPENING', 'tokens': available, 'available_after': max(0, available), 'frozen_after': 0, 'debt_after': max(0, -available), 'reason': '旧Redis余额写冻结快照；历史流水另导，不再次加款', 'operator_id': self.operator, 'created_at': self.frozen})
        if balance_keys != {(uid, scope) for uid in self.imported_user_ids for scope in ('LLM', 'EMBEDDING')}:
            raise ValueError('缺少每个账户两种Redis真实余额，不能从聚合流水推断或补赠送')
        for old in self.tables['user_token_record']:
            key = str(old['id']); owner = self.user(old['user_id']); scope = old['token_type']
            if scope not in ('LLM', 'EMBEDDING') or old['change_type'] not in ('INCREASE', 'CONSUME'):
                raise ValueError('旧Token流水类型不明')
            after = integer(old.get('balance_after') or 0)
            self.add('user_token_record', key, 'ps_token_ledger', {'id': identifier('user_token_record', key), 'user_id': owner, 'scope': scope, 'operation_key': 'legacy-ledger:' + key, 'action': 'LEGACY_' + old['change_type'], 'tokens': integer(old['amount'], nonnegative=True), 'available_after': max(0, after), 'frozen_after': 0, 'debt_after': max(0, -after), 'reason': (old.get('reason') or '旧日聚合用量记录')[:500], 'operator_id': self.operator, 'created_at': self.date(old)})
        providers = self.mapping.get('providerSecretRefs', {})
        for old in self.tables['model_provider_configs']:
            key = str(old['id']); scope = str(old['config_scope']).upper(); code = old['provider_code']; ref = providers.get(scope + ':' + code)
            if scope not in ('LLM', 'EMBEDDING', 'RERANK') or not isinstance(ref, str) or not re.fullmatch(r'ai\.paismart\.[a-z0-9._-]{2,100}', ref):
                raise ValueError('Provider需要已批准的本项目托管密钥引用，不复制旧密文')
            self.add('model_provider_configs', key, 'ps_provider', {'id': identifier('model_provider_configs', key), 'scope': scope, 'name': code, 'endpoint': old['api_base_url'], 'model': old['model_name'], 'dimension': old.get('embedding_dimension'), 'secret_ref': ref, 'enabled': False, 'active': False, 'revision': 1, 'config_json': canonical({'providerCode': code, 'apiStyle': old.get('api_style'), 'legacyEnabled': bool(old.get('enabled')), 'legacyActive': bool(old.get('active'))}), 'updated_at': self.date(old, 'updated_at')})
        for old in self.tables['invite_codes']:
            key = str(old['id']); creator = self.user(old['created_by'])
            self.add('invite_codes', key, 'ps_invite', {'id': identifier('invite_codes', key), 'code': old['code'], 'max_uses': integer(old['max_uses'], nonnegative=True), 'used_count': integer(old.get('used_count', 0), nonnegative=True), 'expires_at': old.get('expires_at'), 'enabled': bool(old.get('enabled')), 'initial_llm_tokens': 0, 'initial_embedding_tokens': 0, 'created_by': creator, 'created_at': self.date(old)})
        for old in self.tables['document_conflicts']:
            key = str(old['id']); approved = self.mapping.get('conflictDocuments', {}).get(key)
            if not approved or approved.get('confirmed') is not True:
                raise ValueError('冲突需人工确认两篇文档，不能按全局MD5跨用户绑定')
            new = identifier('file_upload', approved['newFileUploadId']); previous = identifier('file_upload', approved['oldFileUploadId'])
            new_chunk = self.chunk_ids.get((new, old['new_chunk_id'])); old_chunk = self.chunk_ids.get((previous, old['existing_chunk_id']))
            if not new_chunk or not old_chunk:
                if old['status'] in ('KEEP_NEW', 'KEEP_EXISTING', 'OBSOLETE') and approved.get('archiveOnly') is True:
                    self.warnings.append('已删除冲突文档按明确批准只保留历史裁决归档')
                    continue
                raise ValueError('冲突证据块或文档缺失，需先处理旧删除历史')
            old_status = old['status']
            if old_status not in ('PENDING', 'KEEP_NEW', 'KEEP_EXISTING', 'IGNORED', 'OBSOLETE'):
                raise ValueError('旧冲突状态不明')
            self.add('document_conflicts', key, 'ps_document_conflict', {'id': identifier('document_conflicts', key), 'new_document_id': new, 'old_document_id': previous, 'new_version': 1, 'old_version': 1, 'new_chunk_id': new_chunk, 'old_chunk_id': old_chunk, 'pair_key': '|'.join(sorted((new_chunk, old_chunk))), 'reason_json': canonical({'similarity': old['similarity'], 'maskedSimilarity': old.get('masked_similarity'), 'newSnippet': old.get('new_snippet'), 'oldSnippet': old.get('existing_snippet'), 'newValues': old.get('new_values'), 'oldValues': old.get('existing_values'), 'llmReason': old.get('llm_reason')}), 'status': 'PENDING' if old_status == 'PENDING' else ('OBSOLETE' if old_status == 'OBSOLETE' else 'RESOLVED'), 'action': None if old_status in ('PENDING', 'OBSOLETE') else old_status, 'resolved_by': self.user(old['resolved_by']) if old.get('resolved_by') else None, 'note': old.get('resolution_note'), 'created_at': self.date(old), 'resolved_at': old.get('resolved_at')})
        # 所有旧字段与未完成上传manifest另作完整脱敏归档，保留来源而非覆盖新业务数据。
        # 运行限流需要明确批准的新窗口策略；原完整窗口仍保留并列入报告。
        if self.tables['rate_limit_configs']:
            policy = self.mapping.get('rateLimitPolicy')
            if not isinstance(policy, dict) or policy.get('confirmed') is not True:
                raise ValueError('旧多窗口限流需要人工批准对应策略，不能静默压成单分钟窗口')
            self.add('rate_limit_configs', self.tables['rate_limit_configs'][0]['config_key'], 'ps_rate_limit_policy', {'id': 1, 'chat_per_minute': integer(policy['chatPerMinute'], nonnegative=True), 'upload_per_minute': integer(policy['uploadPerMinute'], nonnegative=True), 'daily_chat_tokens': integer(policy['dailyChatTokens'], nonnegative=True), 'max_file_bytes': integer(policy['maxFileBytes'], nonnegative=True)})
        evaluation_ids = {}
        evaluation_cases = {}
        for report in self.snapshot['evaluationReports']:
            old_id = string(report.get('runId'), 128); policy = self.mapping.get('evaluationOwners', {}).get(old_id)
            if old_id in evaluation_ids:
                raise ValueError('评测报告runId重复')
            if not policy or policy.get('confirmed') is not True:
                raise ValueError('评测运行需要人工确认旧用户归属及数据外发范围')
            owner = self.user(policy['oldUserId']); target = identifier('evaluationReports', old_id); evaluation_ids[old_id] = target
            cases = report.get('cases')
            if not isinstance(cases, list):
                raise ValueError('评测报告逐例数据缺失')
            case_ids = [string(case.get('caseId'), 100) for case in cases]
            if len(set(case_ids)) != len(case_ids):
                raise ValueError('评测报告caseId重复')
            evaluation_cases[old_id] = set(case_ids)
            self.raw_rows['evaluationReports', old_id] = clean(report)
            self.add('evaluationReports', old_id, 'ps_evaluation', {'id': target, 'user_id': owner, 'label': report.get('label') or '旧评测', 'state': 'SUCCEEDED', 'config_json': canonical(clean(report.get('config', {}))), 'dataset_json': canonical([{'id': c['caseId'], 'question': c['question'], 'expectedAnswer': c.get('referenceAnswer')} for c in cases]), 'report_json': canonical(clean(report)), 'created_at': report.get('startedAt') or self.frozen, 'updated_at': report.get('finishedAt') or self.frozen})
            for case in cases:
                self.add('evaluationReports', old_id, 'ps_evaluation_case', {'evaluation_id': target, 'case_id': string(case['caseId'], 100), 'result_json': canonical(clean(case))})
        review_revisions = defaultdict(int)
        reviews = []
        for document in self.snapshot['humanReviews']:
            if 'reviews' in document:
                # 原HumanReviewStore旁路文件的真实结构，不要求手工重写文件内容。
                run = string(document.get('runId'), 128)
                if not isinstance(document['reviews'], dict):
                    raise ValueError('人工复核旁路文件结构无效')
                self.raw_rows['humanReviewSidecars', run] = clean(document)
                for case, item in sorted(document['reviews'].items()):
                    if not isinstance(item, dict) or item.get('caseId') != case:
                        raise ValueError('人工复核旁路caseId与字典键不一致')
                    reviews.append({**item, 'runId': run})
            else:
                reviews.append(document)
        for review in reviews:
            run = string(review.get('runId'), 128); case = string(review.get('caseId'), 100); key = str(review.get('id') or (run + ':' + case + ':' + str(review.get('reviewedAt'))))
            if run not in evaluation_ids or case not in evaluation_cases[run]:
                raise ValueError('人工复核引用缺失评测报告或逐例记录')
            if ('humanReviews', key) in self.raw_rows:
                raise ValueError('人工复核记录标识重复')
            if not isinstance(review.get('correct'), bool) or (review.get('score') is not None and (not isinstance(review['score'], int) or isinstance(review['score'], bool) or not 1 <= review['score'] <= 5)):
                raise ValueError('人工复核correct/score不满足原业务规则')
            reviewer = self.user(review['reviewer']); review_revisions[run, case] += 1
            self.raw_rows['humanReviews', key] = clean(review)
            self.add('humanReviews', key, 'ps_evaluation_review', {'id': identifier('humanReviews', key), 'evaluation_id': evaluation_ids[run], 'case_id': case, 'reviewer_id': reviewer, 'correct': review.get('correct'), 'score': review.get('score'), 'reason': review.get('note') or '旧人工复核', 'revision': review_revisions[run, case], 'created_at': review.get('reviewedAt') or self.frozen})
        for table, key in self.raw_rows:
            self.archive(table, key, self.raw_rows[table, key])
        for kind in ('indexMetadata',):
            for index, row in enumerate(self.snapshot[kind]):
                key = str(row.get('id', index)); self.raw_rows[kind, key] = clean(row); self.archive(kind, key, row)
        return self

    def convert_incomplete_upload(self, old, owner, obj):
        key = str(old['id']); upload = identifier('file_upload', key)
        if old.get('status') not in (0, 2) or obj.get('verified') is not True or obj.get('fullFileHashConfirmed') is not True:
            raise ValueError('未完成上传需要已确认完整文件摘要及分片独立校验，不推断MERGING已完成')
        sha = str(obj.get('sha256', '')); size = integer(old['total_size'], nonnegative=True)
        chunk_size = integer(obj.get('chunkSize'), nonnegative=True)
        if not re.fullmatch('[a-f0-9]{64}', sha) or size <= 0 or obj.get('byteSize') != size or not 65536 <= chunk_size <= 10 * 1024 * 1024:
            raise ValueError('未完成上传摘要、大小或分片大小非法')
        count = (size + chunk_size - 1) // chunk_size
        if obj.get('totalChunks') != count or count > 10000:
            raise ValueError('未完成上传总片数与文件大小不符')
        expiry = obj.get('expiresAt')
        if not expiry or self.timestamp(expiry) <= self.timestamp(self.frozen):
            raise ValueError('续传迁入过期时间须经批准且晚于冻结时点')
        tag = old.get('org_tag')
        if tag not in self.tags:
            raise ValueError('未完成上传所属旧组织缺失')
        personal = self.spaces[tag]['type'] == 'PERSONAL'
        if personal and self.user(self.spaces[tag].get('ownerOldId')) != owner:
            raise ValueError('未完成上传私人空间归属冲突')
        visibility = 'PRIVATE' if personal else ('PUBLIC' if old.get('is_public') else 'ORGANIZATION')
        explicit = self.mapping.get('documentVisibility', {}).get(key)
        if explicit:
            if explicit not in ('PRIVATE', 'ORGANIZATION', 'PUBLIC') or (personal and explicit != 'PRIVATE'):
                raise ValueError('未完成上传可见范围非法')
            visibility = explicit
        parts = obj.get('chunks')
        if not isinstance(parts, list):
            raise ValueError('未完成上传缺少分片对象manifest')
        source_parts = {str(row['id']): row for row in self.tables['chunk_info']}
        expected_parts = {str(row['id']) for row in self.tables['chunk_info'] if row['file_md5'] == old['file_md5']}
        manifest_parts = {str(item.get('chunkInfoId')) for item in parts}
        if len(manifest_parts) != len(parts) or manifest_parts != expected_parts:
            raise ValueError('续传manifest必须完整覆盖旧已上传分片；缺失对象须先人工核对，不静默丢片')
        seen = set()
        self.add('file_upload', key, 'ps_upload', {'id': upload, 'user_id': owner, 'space_id': identifier('organization_tags', tag), 'visibility': visibility, 'file_name': string(old['file_name'], 255), 'byte_size': size, 'sha256': sha, 'legacy_md5': old['file_md5'], 'chunk_size': chunk_size, 'total_chunks': count, 'status': 'UPLOADING', 'request_key': 'legacy-upload:' + key, 'expires_at': expiry, 'created_at': self.date(old)})
        for item in parts:
            part_key = str(item.get('chunkInfoId')); source = source_parts.get(part_key)
            number = integer(item.get('chunkNo'), nonnegative=True)
            expected = min(chunk_size, size - number * chunk_size)
            target = 'paismart/uploads/' + upload + '/' + str(number)
            source_key = 'chunks/' + old['file_md5'] + '/' + str(number)
            if number >= count or number in seen or not source or source['file_md5'] != old['file_md5'] or source['chunk_index'] != number or source['storage_path'] != source_key:
                raise ValueError('未完成上传分片归属或序号冲突，旧全局MD5关系必须明确映射')
            if item.get('verified') is not True or item.get('byteSize') != expected or item.get('targetKey') != target or item.get('sourceKey') != source_key or not re.fullmatch('[a-f0-9]{64}', str(item.get('sha256', ''))):
                raise ValueError('未完成上传分片缺少独立摘要/字节及精确来源目标键')
            seen.add(number)
            self.add('chunk_info', part_key, 'ps_upload_chunk', {'upload_id': upload, 'chunk_no': number, 'byte_size': expected, 'sha256': item['sha256'], 'object_key': target})
            self.object_operations.append({'uploadId': upload, 'chunkNo': number, 'legacySourceKey': source_key, 'targetKey': target, 'sha256': item['sha256'], 'byteSize': expected})
        self.warnings.append('未完成上传保留续传manifest，旧分片须先按独立批准清单复制并回读后才开放续传')

    def artifacts(self):
        groups, conversions = {}, {}
        for (source, key), rows in self.groups.items():
            conversion = self.conversion_hash(source, key, rows)
            conversions[source, key] = conversion
            groups[source, key] = []
            for table, original in rows:
                row = dict(original)
                if table == 'ps_legacy_archive':
                    # 复用现有来源归档，原脱敏字段完整保留在source中，不新增表或列。
                    row['sanitized_json'] = canonical({'source': self.raw_rows[source, key],
                        'migration': {'format': 'paismart-conversion-v1',
                                      'sourceHash': digest(self.raw_rows[source, key]),
                                      'conversionSha256': conversion}})
                groups[source, key].append((table, row))
        counts = defaultdict(int)
        for rows in groups.values():
            for table, _ in rows:
                counts[table] += 1
        manifest = {'schemaVersion': 1, 'migrationBatchId': self.batch, 'snapshotId': self.snapshot_id, 'writeFrozenAt': self.frozen, 'sourceHash': digest(clean(self.snapshot)), 'mappingHash': digest(clean(self.mapping)), 'targetSchema': self.schema, 'sourceCounts': {t: len(r) for t, r in self.tables.items()}, 'targetCounts': dict(sorted(counts.items())), 'userMappings': [{'oldId': key, 'targetUserId': value} for key, value in sorted(self.users.items())], 'rows': [{'sourceTable': t, 'sourceId': k, 'sourceHash': digest(self.raw_rows[t, k]), 'conversionSha256': conversions[t, k], 'targets': [{'table': table, 'hash': digest(row)} for table, row in rows]} for (t, k), rows in sorted(groups.items())], 'objectManifest': clean(self.snapshot['objectManifest']), 'warnings': sorted(set(self.warnings)), 'requiresManualActivation': ['Provider配置与ES新索引重建', '冲突、评测/人工复核及日聚合正式记录与脱敏归档须逐项回读核对', '真实支付关闭，历史订单不再次结算/入账', '原限流多窗口按批准映射核对'], 'executed': False}
        manifest['objectOperations'] = sorted(self.object_operations, key=lambda row: (row.get('id', row.get('uploadId', '')), row.get('chunkNo', -1)))
        manifest['manifestHash'] = digest(manifest)
        guard = lambda condition: 'INSERT INTO tmp_ps_migration_assert(check_ok) SELECT IF(%s,1,0);' % condition
        lines = ['-- 审核后执行；本工具从未连接目标数据库。需先显式应用V001—V005。', '-- 不创建平台用户、数据库或角色；不得在无备份/写冻结/对象校验时执行。', 'DROP TEMPORARY TABLE IF EXISTS tmp_ps_migration_assert;', 'CREATE TEMPORARY TABLE tmp_ps_migration_assert(check_ok INT NOT NULL CHECK(check_ok=1));', 'START TRANSACTION;', guard('DATABASE()=%s' % sql(self.schema)), guard('(SELECT COUNT(DISTINCT version_no) FROM paismart_schema_history WHERE version_no BETWEEN 1 AND 5)=5')]
        for uid in sorted(self.imported_user_ids | {self.operator}):
            lines.append(guard("(SELECT COUNT(*) FROM sys_user WHERE user_id=%d AND del_flag='0')=1" % uid))
        lines.append(guard('(SELECT COUNT(*) FROM ps_legacy_migration_batch WHERE id=%s AND manifest_hash<>%s)=0' % (sql(self.batch), sql(manifest['manifestHash']))))
        lines.append('-- 先核对全部既有来源回执及转换；旧裸归档、指纹缺失或变化必须停止并人工复核，不自动补写。')
        for source, key in sorted(groups):
            source_hash = digest(self.raw_rows[source, key]); receipt = 'source_table=%s AND source_id=%s' % (sql(source), sql(key))
            lines.append(guard('(SELECT COUNT(*) FROM ps_legacy_migration_row WHERE %s AND source_hash<>%s)=0' % (receipt, sql(source_hash))))
            archive = ('SELECT 1 FROM ps_legacy_migration_row r JOIN ps_legacy_archive a '
                       'ON a.source_table=r.source_table AND a.source_id=r.source_id AND a.migration_batch_id=r.migration_batch_id '
                       'WHERE r.source_table=%s AND r.source_id=%s AND r.target_table=%s AND r.target_id=%s '
                       'AND CASE WHEN JSON_VALID(a.sanitized_json) THEN '
                       '(JSON_UNQUOTE(JSON_EXTRACT(a.sanitized_json,\'$.migration.format\'))=%s '
                       'AND JSON_UNQUOTE(JSON_EXTRACT(a.sanitized_json,\'$.migration.sourceHash\'))=%s '
                       'AND JSON_UNQUOTE(JSON_EXTRACT(a.sanitized_json,\'$.migration.conversionSha256\'))=%s) '
                       'ELSE 0 END') % (sql(source), sql(key), sql('ps_legacy_archive'), sql(key),
                                        sql('paismart-conversion-v1'), sql(source_hash), sql(conversions[source, key]))
            lines.append(guard('(NOT EXISTS(SELECT 1 FROM ps_legacy_migration_row WHERE %s) OR EXISTS(%s))' % (receipt, archive)))
            if source == 'users':
                identity = 'source_user_id=%s AND user_id=%d AND source_hash=%s' % (sql(key), self.user(key), sql(source_hash))
                lines.append(guard('((SELECT COUNT(*) FROM ps_legacy_identity_map WHERE source_user_id=%s '
                                   'AND (user_id<>%d OR source_hash<>%s))=0 '
                                   'AND (NOT EXISTS(SELECT 1 FROM ps_legacy_migration_row WHERE %s) '
                                   'OR EXISTS(SELECT 1 FROM ps_legacy_identity_map WHERE %s)))' %
                                   (sql(key), self.user(key), sql(source_hash), receipt, identity)))
        for (source, key), rows in sorted(groups.items()):
            source_hash = digest(self.raw_rows[source, key]); receipt = 'source_table=%s AND source_id=%s' % (sql(source), sql(key))
            for table, row in rows:
                fields = list(row); literals = ','.join(sql(row[f]) for f in fields)
                # 仅在该来源行尚未导入时新增，业务新写入或先前批次均不得覆盖。
                pk = {'ps_member': ('user_id',), 'ps_member_space': ('user_id', 'space_id'), 'ps_upload_chunk': ('upload_id', 'chunk_no'), 'ps_token_account': ('user_id', 'scope'), 'ps_daily_usage': ('user_id', 'business_day', 'scope'), 'ps_evaluation_case': ('evaluation_id', 'case_id'), 'ps_legacy_identity_map': ('source_user_id',), 'ps_legacy_archive': ('source_table', 'source_id')}.get(table, ('id',))
                existing = ' AND '.join('`%s`=%s' % (p, sql(row[p])) for p in pk)
                lines.append(guard('(EXISTS(SELECT 1 FROM ps_legacy_migration_row WHERE %s) OR NOT EXISTS(SELECT 1 FROM `%s` WHERE %s))' % (receipt, table, existing)))
                lines.append('INSERT INTO `%s`(%s) SELECT %s WHERE NOT EXISTS(SELECT 1 FROM ps_legacy_migration_row WHERE %s);' % (table, ','.join('`%s`' % f for f in fields), literals, receipt))
            lines.append('INSERT INTO ps_legacy_migration_row(source_table,source_id,source_hash,target_table,target_id,migration_batch_id) SELECT %s,%s,%s,%s,%s,%s WHERE NOT EXISTS(SELECT 1 FROM ps_legacy_migration_row WHERE %s);' % (sql(source), sql(key), sql(source_hash), sql('ps_legacy_archive'), sql(key), sql(self.batch), receipt))
        lines.append('INSERT INTO ps_legacy_migration_batch(id,manifest_hash,source_snapshot,source_hash) SELECT %s,%s,%s,%s WHERE NOT EXISTS(SELECT 1 FROM ps_legacy_migration_batch WHERE id=%s);' % (sql(self.batch), sql(manifest['manifestHash']), sql(self.snapshot_id), sql(manifest['sourceHash']), sql(self.batch)))
        lines.extend(['COMMIT;', 'DROP TEMPORARY TABLE tmp_ps_migration_assert;', '-- 回读来源收据、逐表行数、对象hash、两种余额与历史订单；不得只凭SQL成功宣称迁移通过。'])
        checks = ['-- 只读核对SQL；金额与Token必须和manifest独立复算。', 'SELECT source_table,COUNT(*) AS row_count FROM ps_legacy_migration_row WHERE migration_batch_id=%s GROUP BY source_table;' % sql(self.batch), 'SELECT user_id,scope,available,frozen,consumed,debt FROM ps_token_account ORDER BY user_id,scope;', 'SELECT state,COUNT(*) AS order_count,SUM(amount_fen) AS amount_fen,SUM(llm_tokens) AS llm_tokens,SUM(embedding_tokens) AS embedding_tokens FROM ps_recharge_order GROUP BY state;', 'SELECT id,object_key,sha256,byte_size,user_id,visibility FROM ps_document ORDER BY id;', 'SELECT COUNT(*) AS chunk_count FROM ps_document_chunk;', 'SELECT source_table,COUNT(*) AS archive_count FROM ps_legacy_archive GROUP BY source_table;', "SELECT source_table,source_id,migration_batch_id,CASE WHEN JSON_VALID(sanitized_json) THEN JSON_UNQUOTE(JSON_EXTRACT(sanitized_json,'$.migration.conversionSha256')) ELSE NULL END AS conversion_sha256 FROM ps_legacy_archive ORDER BY source_table,source_id;"]
        return manifest, '\n'.join(lines) + '\n', '\n'.join(checks) + '\n'

def export_template():
    lines = ['-- 仅由明确授权的只读账号在原库手工运行；不连接目标库。', '-- 必须先冻结本项目写入，并另外导出原Redis两种余额/总请求、对象manifest和评测/复核文件。', 'SET SESSION TRANSACTION READ ONLY;', 'START TRANSACTION WITH CONSISTENT SNAPSHOT;']
    for table, fields in TABLES.items():
        safe = [f for f in fields if re.sub(r'[^a-z0-9]', '', f.lower()) not in SECRET_FIELDS]
        lines.append('SELECT %s FROM `%s`;' % (','.join('`%s`' % f for f in safe), table))
    lines.append('COMMIT;')
    return '\n'.join(lines) + '\n'

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', type=Path)
    parser.add_argument('--mapping', type=Path)
    parser.add_argument('--batch')
    parser.add_argument('--target-schema')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--export-template', action='store_true')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    if args.export_template:
        (args.output / 'readonly-export.sql').write_text(export_template(), encoding='utf-8')
        print('已生成只读导出语句；没有连接数据库。')
        return
    if not all((args.snapshot, args.mapping, args.batch, args.target_schema)):
        parser.error('需要snapshot、mapping、batch和target-schema；不能猜测库或账户')
    source = json.loads(args.snapshot.read_text(encoding='utf-8'))
    mapping = json.loads(args.mapping.read_text(encoding='utf-8'))
    prepared = Preparation(source, mapping, args.batch, args.target_schema).convert()
    manifest, statements, checks = prepared.artifacts()
    (args.output / 'migration-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    (args.output / 'reviewed-import.sql').write_text(statements, encoding='utf-8')
    (args.output / 'readonly-verify.sql').write_text(checks, encoding='utf-8')
    print('已生成批次清单和待审核SQL；未连接数据库、未执行SQL、未迁移对象。')

if __name__ == '__main__':
    main()
