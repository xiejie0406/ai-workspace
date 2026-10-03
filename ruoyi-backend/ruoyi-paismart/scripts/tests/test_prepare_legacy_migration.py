"""离线快照转换反例测试；无网络、数据库、对象存储或真实用户数据。"""
import copy
import importlib.util
import json
import re
import sqlite3
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('migration', Path(__file__).parents[1] / 'prepare-legacy-migration.py')
migration = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(migration)


def fixture():
    tables = {table: [] for table in migration.TABLES}
    tables['users'] = [{'id': 9, 'username': 'legacy-user', 'password': 'SENSITIVE-PASSWORD', 'role': 'ADMIN', 'org_tags': 'ROOT,PRIVATE_9', 'primary_org': 'ROOT', 'created_at': '2026-10-01T08:00:00'}]
    tables['organization_tags'] = [
        {'tag_id': 'ROOT', 'name': '研发', 'upload_max_size_bytes': 100000, 'created_by': 'legacy-user'},
        {'tag_id': 'PRIVATE_9', 'name': '个人空间', 'created_by': 'legacy-user'}]
    tables['file_upload'] = [{'id': 11, 'file_md5': 'a' * 32, 'file_name': 'notes.txt', 'total_size': 4, 'status': 1, 'user_id': 'legacy-user', 'org_tag': 'ROOT', 'is_public': False}]
    tables['chunk_info'] = [{'id': 12, 'file_md5': 'a' * 32, 'chunk_index': 0, 'chunk_md5': 'b' * 32, 'storage_path': 'legacy/0'}]
    tables['document_vectors'] = [{'vector_id': 'vector-1', 'file_md5': 'a' * 32, 'chunk_id': 0, 'user_id': 'legacy-user', 'text_content': 'body', 'page_number': 1, 'anchor_text': 'body', 'model_version': 'old-v1'}]
    tables['conversation_sessions'] = [{'id': 13, 'user_id': 9, 'conversation_id': 'session-1', 'title': '旧会话', 'status': 'ACTIVE'}]
    tables['conversations'] = [{'id': 14, 'user_id': 9, 'conversation_id': 'session-1', 'question': '问题', 'answer': '答复[1]', 'reference_mappings_json': json.dumps({'1': {'fileMd5': 'a' * 32, 'chunkId': 0, 'pageNumber': 1, 'content': 'body'}})}]
    tables['document_conflicts'] = [{'id': 15, 'new_file_md5': 'a' * 32, 'existing_file_md5': 'a' * 32, 'new_chunk_id': 0, 'existing_chunk_id': 0, 'similarity': 0.98, 'status': 'PENDING'}]
    tables['invite_codes'] = [{'id': 16, 'code': 'INVITE-SAMPLE', 'max_uses': 10, 'used_count': 2, 'enabled': True, 'created_by': 'legacy-user'}]
    tables['model_provider_configs'] = [{'id': 17, 'config_scope': 'LLM', 'provider_code': 'sample', 'api_base_url': 'https://model.example.invalid', 'model_name': 'old-model', 'api_key_ciphertext': 'SENSITIVE-CIPHER', 'enabled': True, 'active': True}]
    tables['rate_limit_configs'] = [{'config_key': 'upload', 'single_max': 1, 'single_window_seconds': 2, 'minute_max': 10, 'minute_window_seconds': 60, 'day_max': 100, 'day_window_seconds': 86400}]
    tables['recharge_packages'] = [{'id': 18, 'package_name': '套餐', 'package_price': 100, 'llm_token': 1000, 'embedding_token': 500, 'enabled': True, 'deleted': False}]
    tables['recharge_orders'] = [{'id': 19, 'user_id': 'legacy-user', 'package_id': 18, 'trade_no': 'TRADE-19', 'amount': 100, 'llm_token': 1000, 'embedding_token': 500, 'status': 'SUCCEED', 'wx_transaction_id': 'wx-19', 'pay_time': '2026-10-01T09:00:00+08:00'}]
    tables['user_token_record'] = [
        {'id': 20, 'user_id': 'legacy-user', 'record_date': '2026-10-01', 'token_type': 'LLM', 'change_type': 'INCREASE', 'amount': 1000, 'balance_after': 1000, 'reason': '历史充值'},
        {'id': 21, 'user_id': 'legacy-user', 'record_date': '2026-10-01', 'token_type': 'LLM', 'change_type': 'CONSUME', 'amount': 40, 'balance_after': 960, 'request_count': 2}]
    tables['user_daily_chat_count'] = [{'id': 22, 'user_id': 'legacy-user', 'record_date': '2026-10-01', 'chat_request_count': 2}]
    snapshot = {'schemaVersion': 1, 'snapshotId': 'freeze-1', 'writeFrozenAt': '2026-10-03T10:00:00+08:00', 'tables': tables,
                'redisBalances': [{'oldUserId': 9, 'scope': 'LLM', 'availableTokens': 777, 'totalRequests': 3, 'snapshotId': 'freeze-1'}, {'oldUserId': 9, 'scope': 'EMBEDDING', 'availableTokens': -2, 'totalRequests': 1, 'snapshotId': 'freeze-1'}],
                'objectManifest': [{'fileUploadId': 11, 'verified': True, 'sha256': 'c' * 64, 'byteSize': 4, 'targetKey': 'paismart/documents/' + migration.identifier('file_upload', 11) + '/source.txt', 'mimeType': 'text/plain'}],
                'evaluationReports': [{'runId': 'run-1', 'startedAt': '2026-10-01T10:00:00Z', 'finishedAt': '2026-10-01T10:01:00Z', 'config': {'API_KEY': 'SENSITIVE-NESTED'}, 'cases': [{'caseId': 'case-1', 'question': '问题', 'referenceAnswer': '回答'}]}],
                'humanReviews': [{'id': 'review-1', 'runId': 'run-1', 'caseId': 'case-1', 'reviewer': 'legacy-user', 'correct': True, 'score': 4, 'note': '人工意见'}],
                'indexMetadata': [{'id': 'legacy-vectors', 'dimension': 768, 'model': 'old-v1'}]}
    mapping = {'approved': True, 'operatorUserId': 1, 'sourceUtcOffset': '+08:00', 'targetUtcOffset': '+00:00',
               'users': [{'oldId': 9, 'oldUsername': 'legacy-user', 'targetUserId': 2, 'confirmed': True}],
               'spaces': {'ROOT': {'confirmed': True, 'type': 'ORGANIZATION'}, 'PRIVATE_9': {'confirmed': True, 'type': 'PERSONAL', 'ownerOldId': 9}},
               'providerSecretRefs': {'LLM:sample': 'ai.paismart.sample.key'},
               'conflictDocuments': {'15': {'confirmed': True, 'newFileUploadId': 11, 'oldFileUploadId': 11}},
               'rateLimitPolicy': {'confirmed': True, 'chatPerMinute': 20, 'uploadPerMinute': 10, 'dailyChatTokens': 1000000, 'maxFileBytes': 104857600},
               'evaluationOwners': {'run-1': {'confirmed': True, 'oldUserId': 9}}}
    return snapshot, mapping


class LegacyMigrationTest(unittest.TestCase):
    def prepare(self, source=None, mapping=None):
        default_source, default_mapping = fixture()
        return migration.Preparation(source or default_source, mapping or default_mapping, 'batch-1', 'approved_schema').convert()

    @staticmethod
    def sqlite_statement(statement):
        # 只转换本工具的安全字符串字面量；实际执行的仍是生成物中的守卫。
        return re.sub(r"CONVERT\(X'([a-f0-9]*)' USING utf8mb4\)", r"CAST(X'\1' AS TEXT)", statement)

    def receipt_database(self, statements):
        db = sqlite3.connect(':memory:')
        db.create_function('IF', 3, lambda condition, yes, no: yes if condition else no)
        db.create_function('DATABASE', 0, lambda: 'approved_schema')
        # SQLite与MySQL的JSON_EXTRACT返回形状不同，模拟MySQL的JSON标量。
        def extract(value, path):
            current = json.loads(value)
            for key in path.removeprefix('$.').split('.'):
                if not isinstance(current, dict) or key not in current:
                    return None
                current = current[key]
            return json.dumps(current, ensure_ascii=False)
        db.create_function('JSON_EXTRACT', 2, extract)
        db.create_function('JSON_UNQUOTE', 1, lambda value: json.loads(value) if value is not None else None)
        db.executescript('''
            CREATE TABLE tmp_ps_migration_assert(check_ok INTEGER NOT NULL CHECK(check_ok=1));
            CREATE TABLE paismart_schema_history(version_no INTEGER PRIMARY KEY);
            INSERT INTO paismart_schema_history VALUES(1),(2),(3),(4),(5);
            CREATE TABLE sys_user(user_id INTEGER PRIMARY KEY,del_flag TEXT);
            INSERT INTO sys_user VALUES(1,'0'),(2,'0'),(3,'0');
            CREATE TABLE ps_legacy_migration_batch(id TEXT PRIMARY KEY,manifest_hash TEXT,source_snapshot TEXT,source_hash TEXT);
            CREATE TABLE ps_legacy_migration_row(source_table TEXT,source_id TEXT,source_hash TEXT,target_table TEXT,target_id TEXT,migration_batch_id TEXT,PRIMARY KEY(source_table,source_id));
            CREATE TABLE ps_legacy_archive(source_table TEXT,source_id TEXT,sanitized_json TEXT,migration_batch_id TEXT,PRIMARY KEY(source_table,source_id));
            CREATE TABLE ps_legacy_identity_map(source_user_id TEXT PRIMARY KEY,user_id INTEGER,migration_batch_id TEXT,source_hash TEXT,created_by INTEGER);
            CREATE TABLE ps_member(user_id INTEGER PRIMARY KEY,status TEXT,created_at TEXT,updated_at TEXT);
        ''')
        # 只模拟生成SQL写入的回执/身份及一份成员业务行，不是完整导入或真实MySQL。
        for statement in statements.splitlines():
            if statement.startswith(('INSERT INTO `ps_member`', 'INSERT INTO `ps_legacy_archive`', 'INSERT INTO `ps_legacy_identity_map`',
                                     'INSERT INTO ps_legacy_migration_row', 'INSERT INTO ps_legacy_migration_batch')):
                db.execute(self.sqlite_statement(statement))
        return db

    def execute_preflight(self, db, statements):
        first_business = statements.index('INSERT INTO `ps_')
        for statement in statements[:first_business].splitlines():
            if statement.startswith('INSERT INTO tmp_ps_migration_assert'):
                db.execute(self.sqlite_statement(statement))

    def test_new_batch_same_conversion_reuses_receipts_without_rewriting_business(self):
        source, mapping = fixture()
        _, original, _ = self.prepare(source, mapping).artifacts()
        _, repeated, _ = migration.Preparation(source, mapping, 'batch-2', 'approved_schema').convert().artifacts()
        with self.receipt_database(original) as db:
            db.execute("UPDATE ps_member SET status='INELIGIBLE' WHERE user_id=2")
            self.execute_preflight(db, repeated)
            for statement in repeated.splitlines():
                if statement.startswith('INSERT INTO `ps_member`'):
                    db.execute(self.sqlite_statement(statement))
            self.assertEqual('INELIGIBLE', db.execute('SELECT status FROM ps_member WHERE user_id=2').fetchone()[0])
            self.assertEqual(2, db.execute("SELECT user_id FROM ps_legacy_identity_map WHERE source_user_id='9'").fetchone()[0])
            self.assertIn('WHERE NOT EXISTS(SELECT 1 FROM ps_legacy_migration_row', repeated)

    def test_changed_identity_is_rejected_before_first_business_insert(self):
        source, mapping = fixture()
        _, original, _ = self.prepare(source, mapping).artifacts()
        mapping['users'][0]['targetUserId'] = 3
        _, changed, _ = migration.Preparation(source, mapping, 'batch-2', 'approved_schema').convert().artifacts()
        with self.receipt_database(original) as db:
            with self.assertRaises(sqlite3.IntegrityError):
                self.execute_preflight(db, changed)
            self.assertEqual(2, db.execute("SELECT user_id FROM ps_legacy_identity_map WHERE source_user_id='9'").fetchone()[0])
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM ps_legacy_migration_batch WHERE id='batch-2'").fetchone()[0])

    def test_changed_real_conversions_are_rejected_even_with_unchanged_source_rows(self):
        source, original_mapping = fixture()
        _, original, _ = self.prepare(source, original_mapping).artifacts()
        alternatives = {}
        alternative = copy.deepcopy(original_mapping)
        alternative['spaces']['PRIVATE_9'] = {'confirmed': True, 'type': 'ORGANIZATION'}
        alternatives['space type and owner'] = alternative
        alternative = copy.deepcopy(original_mapping)
        alternative['documentVisibility'] = {'11': 'PUBLIC'}
        alternatives['visibility'] = alternative
        alternative = copy.deepcopy(original_mapping)
        alternative['providerSecretRefs']['LLM:sample'] = 'ai.paismart.alternate.key'
        alternatives['secret reference'] = alternative
        alternative = copy.deepcopy(original_mapping)
        alternative['spaceManagers'] = [{'oldUserId': 9, 'tagId': 'ROOT', 'confirmed': True}]
        alternatives['manager permission'] = alternative
        alternative = copy.deepcopy(original_mapping)
        alternative['rateLimitPolicy']['chatPerMinute'] = 42
        alternatives['rate limit'] = alternative
        alternative = copy.deepcopy(original_mapping)
        alternative['sourceUtcOffset'] = '+07:00'
        alternatives['source timezone'] = alternative
        for name, mapping in alternatives.items():
            with self.subTest(conversion=name), self.receipt_database(original) as db:
                _, changed, _ = migration.Preparation(source, mapping, 'batch-2', 'approved_schema').convert().artifacts()
                with self.assertRaises(sqlite3.IntegrityError):
                    self.execute_preflight(db, changed)

    def test_object_copy_plan_is_bound_when_target_business_rows_are_unchanged(self):
        source, mapping = fixture()
        source['objectManifest'][0]['sourceKey'] = 'merged/' + 'a' * 32
        _, original, _ = self.prepare(source, mapping).artifacts()
        source['objectManifest'][0]['sourceKey'] = 'merged/notes.txt'
        _, changed, _ = migration.Preparation(source, mapping, 'batch-2', 'approved_schema').convert().artifacts()
        with self.receipt_database(original) as db:
            with self.assertRaises(sqlite3.IntegrityError):
                self.execute_preflight(db, changed)

    def test_legacy_missing_malformed_and_unknown_archive_fingerprints_require_review(self):
        _, original, _ = self.prepare().artifacts()
        source, _ = fixture()
        legacy_source = json.dumps(migration.clean(source['tables']['users'][0]))
        for archive in [None, '{', '{}', legacy_source, '{"migration":{"format":"unknown"}}']:
            with self.subTest(archive=archive), self.receipt_database(original) as db:
                if archive is None:
                    db.execute("DELETE FROM ps_legacy_archive WHERE source_table='users' AND source_id='9'")
                else:
                    db.execute("UPDATE ps_legacy_archive SET sanitized_json=? WHERE source_table='users' AND source_id='9'", (archive,))
                with self.assertRaises(sqlite3.IntegrityError):
                    self.execute_preflight(db, original)
        with self.receipt_database(original) as db:
            db.execute("UPDATE ps_legacy_identity_map SET user_id=3 WHERE source_user_id='9'")
            with self.assertRaises(sqlite3.IntegrityError):
                self.execute_preflight(db, original)
        with self.receipt_database(original) as db:
            db.execute("DELETE FROM ps_legacy_identity_map WHERE source_user_id='9'")
            with self.assertRaises(sqlite3.IntegrityError):
                self.execute_preflight(db, original)
        with self.receipt_database(original) as db:
            db.execute("UPDATE ps_legacy_migration_row SET migration_batch_id='unmatched' WHERE source_table='users' AND source_id='9'")
            with self.assertRaises(sqlite3.IntegrityError):
                self.execute_preflight(db, original)

    def test_conversion_envelope_preserves_source_and_is_independent_of_batch_id(self):
        first = self.prepare()
        manifest, statements, _ = first.artifacts()
        source, mapping = fixture()
        repeated, _, _ = migration.Preparation(source, mapping, 'batch-2', 'approved_schema').convert().artifacts()
        self.assertEqual([row['conversionSha256'] for row in manifest['rows']],
                         [row['conversionSha256'] for row in repeated['rows']])
        with self.receipt_database(statements) as db:
            for source_table, source_id, stored in db.execute('SELECT source_table,source_id,sanitized_json FROM ps_legacy_archive'):
                envelope = json.loads(stored)
                self.assertEqual(first.raw_rows[source_table, source_id], envelope['source'])
                self.assertEqual('paismart-conversion-v1', envelope['migration']['format'])
                self.assertEqual(migration.digest(first.raw_rows[source_table, source_id]), envelope['migration']['sourceHash'])
                row = next(row for row in manifest['rows'] if row['sourceTable'] == source_table and row['sourceId'] == source_id)
                self.assertEqual(row['conversionSha256'], envelope['migration']['conversionSha256'])

    def test_all_entities_deterministic_and_secrets_excluded(self):
        prepared = self.prepare()
        manifest, sql, checks = prepared.artifacts()
        self.assertEqual((manifest, sql, checks), self.prepare().artifacts())
        for table in ('ps_member', 'ps_space', 'ps_member_space', 'ps_document', 'ps_document_chunk', 'ps_job', 'ps_outbox', 'ps_conversation', 'ps_chat_message', 'ps_reference', 'ps_recharge_package', 'ps_recharge_order', 'ps_token_account', 'ps_token_ledger', 'ps_daily_usage', 'ps_provider', 'ps_invite', 'ps_document_conflict', 'ps_rate_limit_policy', 'ps_evaluation', 'ps_evaluation_case', 'ps_evaluation_review', 'ps_legacy_archive'):
            self.assertGreater(manifest['targetCounts'].get(table, 0), 0, table)
        serialized = json.dumps(manifest) + sql + checks
        # SQL内容是十六进制安全字面量，也不能把秘密编码后带出去。
        for secret in ('SENSITIVE-PASSWORD', 'SENSITIVE-CIPHER', 'SENSITIVE-NESTED'):
            self.assertNotIn(secret, serialized)
            self.assertNotIn(secret.encode().hex(), serialized)
        self.assertFalse(manifest['executed'])
        provider = next(row for rows in prepared.groups.values() for table, row in rows if table == 'ps_provider')
        self.assertFalse(provider['enabled'])
        self.assertFalse(provider['active'])
        paid = next(row for rows in prepared.groups.values() for table, row in rows if table == 'ps_recharge_order')
        self.assertEqual('SUCCEEDED', paid['state'])
        self.assertEqual('wx-19', paid['transaction_id'])

    def test_import_requires_all_five_history_versions_before_business_inserts(self):
        _, statements, _ = self.prepare().artifacts()
        guards = [line for line in statements.splitlines()
                  if line.startswith('INSERT INTO tmp_ps_migration_assert') and 'paismart_schema_history' in line]
        self.assertEqual(1, len(guards), '导入必须有真实版本守卫，不能只保留DDL前提注释')
        first_business_insert = statements.index('INSERT INTO `ps_')
        self.assertLess(statements.index(guards[0]), first_business_insert)
        # 执行生成物中的共同SQL子集；仅用内存库与IF函数，不连接任何来源或目标库。
        for installed, accepted in [([1, 2, 3, 4], False), ([1, 2, 3, 4, 6], False),
                                    ([1, 2, 4, 5], False), ([1, 2, 3, 4, 5], True)]:
            with self.subTest(installed=installed), sqlite3.connect(':memory:') as db:
                db.create_function('IF', 3, lambda condition, yes, no: yes if condition else no)
                db.execute('CREATE TABLE paismart_schema_history(version_no INTEGER PRIMARY KEY)')
                db.execute('CREATE TABLE tmp_ps_migration_assert(check_ok INTEGER NOT NULL CHECK(check_ok=1))')
                db.executemany('INSERT INTO paismart_schema_history VALUES(?)', [(v,) for v in installed])
                if accepted:
                    db.execute(guards[0])
                    self.assertEqual(1, db.execute('SELECT COUNT(*) FROM tmp_ps_migration_assert').fetchone()[0])
                else:
                    with self.assertRaises(sqlite3.IntegrityError):
                        db.execute(guards[0])

    def test_space_description_default_marker_and_package_benefit_are_not_dropped(self):
        source, mapping = fixture()
        source['tables']['organization_tags'][0]['description'] = '原组织业务说明'
        source['tables']['organization_tags'].append({'tag_id': 'DEFAULT', 'name': '默认组织', 'created_by': 'legacy-user'})
        mapping['spaces']['DEFAULT'] = {'confirmed': True, 'type': 'ORGANIZATION'}
        source['tables']['recharge_packages'][0]['package_benefit'] = '原套餐权益说明'
        prepared = self.prepare(source, mapping)
        rows = [(table, row) for group in prepared.groups.values() for table, row in group]
        self.assertEqual('原组织业务说明', next(row for table, row in rows if table == 'ps_space' and row['name'] == '研发')['description'])
        self.assertEqual('DEFAULT', next(row for table, row in rows if table == 'ps_space' and row['name'] == '默认组织')['system_key'])
        self.assertEqual('原套餐权益说明', next(row for table, row in rows if table == 'ps_recharge_package')['benefit'])

    def test_redis_is_balance_authority_and_debt_is_preserved(self):
        accounts = [row for rows in self.prepare().groups.values() for table, row in rows if table == 'ps_token_account']
        self.assertEqual({'LLM': (777, 0, 40), 'EMBEDDING': (0, 2, 0)}, {r['scope']: (r['available'], r['debt'], r['consumed']) for r in accounts})
        source, mapping = fixture()
        source['redisBalances'].pop()
        with self.assertRaisesRegex(ValueError, 'Redis真实余额'):
            self.prepare(source, mapping)
        source, mapping = fixture()
        del source['redisBalances'][0]['totalRequests']
        with self.assertRaisesRegex(ValueError, '总请求次数'):
            self.prepare(source, mapping)

    def test_private_membership_and_primary_are_preserved_only_for_confirmed_owner(self):
        source, mapping = fixture()
        source['tables']['users'][0]['primary_org'] = 'PRIVATE_9'
        prepared = self.prepare(source, mapping)
        relations = [row for table, row in prepared.groups['users', '9'] if table == 'ps_member_space']
        self.assertEqual(2, len(relations))
        self.assertEqual([migration.identifier('organization_tags', 'PRIVATE_9')], [row['space_id'] for row in relations if row['primary_flag']])
        source['tables']['users'][0]['primary_org'] = 'MISSING'
        with self.assertRaisesRegex(ValueError, '主空间不属于成员'):
            self.prepare(source, mapping)
        source, mapping = fixture()
        source['tables']['users'].append({'id': 99, 'username': 'reader', 'org_tags': 'PRIVATE_9'})
        mapping['users'].append({'oldId': 99, 'oldUsername': 'reader', 'targetUserId': 3, 'confirmed': True})
        with self.assertRaisesRegex(ValueError, '不能批量强占'):
            self.prepare(source, mapping)

    def test_unknown_table_and_unapproved_user_are_rejected(self):
        source, mapping = fixture()
        source['tables']['unreviewed_table'] = []
        with self.assertRaises(ValueError):
            self.prepare(source, mapping)
        source, mapping = fixture()
        mapping['users'][0]['confirmed'] = False
        with self.assertRaises(ValueError):
            self.prepare(source, mapping)
        source, mapping = fixture()
        source['tables']['users'][0]['unrecognized_secret_column'] = 'value'
        with self.assertRaises(ValueError):
            self.prepare(source, mapping)

    def test_object_ownership_and_incomplete_upload_are_not_guessed(self):
        source, mapping = fixture()
        source['objectManifest'][0]['targetKey'] = 'fashion/source.txt'
        with self.assertRaises(ValueError):
            self.prepare(source, mapping)
        source, mapping = fixture()
        source['tables']['file_upload'][0]['status'] = 0
        with self.assertRaisesRegex(ValueError, '未完成上传'):
            self.prepare(source, mapping)
        source, mapping = fixture()
        duplicated = copy.deepcopy(source['tables']['file_upload'][0])
        duplicated['id'] = 111
        source['tables']['file_upload'].append(duplicated)
        object_copy = copy.deepcopy(source['objectManifest'][0])
        object_copy.update(fileUploadId=111, targetKey='paismart/documents/' + migration.identifier('file_upload', 111) + '/source.txt')
        source['objectManifest'].append(object_copy)
        with self.assertRaisesRegex(ValueError, '人工消重'):
            self.prepare(source, mapping)

    def test_incomplete_upload_keeps_checked_manifest_and_never_fakes_a_document(self):
        source, mapping = fixture()
        source['tables']['file_upload'][0]['status'] = 2
        source['tables']['document_vectors'] = []
        source['tables']['document_conflicts'] = []
        source['tables']['conversations'][0]['reference_mappings_json'] = '{}'
        source['tables']['chunk_info'][0]['storage_path'] = 'chunks/' + 'a' * 32 + '/0'
        upload = migration.identifier('file_upload', 11)
        source['objectManifest'][0] = {'fileUploadId': 11, 'kind': 'UPLOAD', 'fullFileHashConfirmed': True, 'verified': True, 'sha256': 'c' * 64, 'byteSize': 4, 'chunkSize': 65536, 'totalChunks': 1, 'expiresAt': '2026-10-06T10:00:00+08:00', 'chunks': [{'chunkInfoId': 12, 'chunkNo': 0, 'verified': True, 'byteSize': 4, 'sha256': 'd' * 64, 'sourceKey': 'chunks/' + 'a' * 32 + '/0', 'targetKey': 'paismart/uploads/' + upload + '/0'}]}
        prepared = self.prepare(source, mapping)
        manifest, sql, checks = prepared.artifacts()
        self.assertEqual(1, manifest['targetCounts']['ps_upload'])
        self.assertEqual(1, manifest['targetCounts']['ps_upload_chunk'])
        self.assertEqual(0, manifest['targetCounts'].get('ps_document', 0))
        self.assertEqual(0, manifest['targetCounts'].get('ps_job', 0))
        self.assertEqual(upload, manifest['objectOperations'][0]['uploadId'])
        self.assertEqual('chunks/' + 'a' * 32 + '/0', manifest['objectOperations'][0]['legacySourceKey'])
        self.assertNotIn('终止未完成', str(manifest['warnings']))
        complete = copy.deepcopy(source['objectManifest'][0]['chunks'])
        source['objectManifest'][0]['chunks'] = []
        with self.assertRaisesRegex(ValueError, '完整覆盖旧已上传分片'):
            self.prepare(source, mapping)
        source['objectManifest'][0]['chunks'] = complete
        source['objectManifest'][0]['chunks'][0]['targetKey'] = 'paismart/uploads/another/0'
        with self.assertRaisesRegex(ValueError, '精确来源目标'):
            self.prepare(source, mapping)

    def test_timezone_sql_guards_and_export_are_reviewable(self):
        prepared = self.prepare()
        user = next(row for table, row in prepared.groups['users', '9'] if table == 'ps_member')
        self.assertEqual('2026-10-01 00:00:00.000000', user['created_at'])
        _, sql, checks = prepared.artifacts()
        self.assertIn('CHECK(check_ok=1)', sql)
        self.assertIn('DATABASE()', sql)
        self.assertIn('source_hash<>', sql)
        self.assertIn('NOT EXISTS(SELECT 1 FROM ps_legacy_migration_row', sql)
        self.assertNotIn('UPDATE `', sql)
        self.assertNotIn('DELETE FROM', sql)
        self.assertIn('SELECT state,COUNT(*)', checks)
        export = migration.export_template()
        self.assertIn('TRANSACTION READ ONLY', export)
        self.assertNotIn('`password`', export)
        self.assertNotIn('`api_key_ciphertext`', export)
        source, mapping = fixture()
        del mapping['sourceUtcOffset']
        with self.assertRaisesRegex(ValueError, '时区'):
            self.prepare(source, mapping)

    def test_cross_user_reference_requires_manual_mapping(self):
        source, mapping = fixture()
        source['tables']['users'].append({'id': 99, 'username': 'reader', 'org_tags': 'ROOT'})
        mapping['users'].append({'oldId': 99, 'oldUsername': 'reader', 'targetUserId': 3, 'confirmed': True})
        for scope in ('LLM', 'EMBEDDING'):
            source['redisBalances'].append({'oldUserId': 99, 'scope': scope, 'availableTokens': 0, 'totalRequests': 0, 'snapshotId': 'freeze-1'})
        source['tables']['conversation_sessions'][0]['user_id'] = 99
        source['tables']['conversations'][0]['user_id'] = 99
        with self.assertRaisesRegex(ValueError, 'referenceDocuments'):
            self.prepare(source, mapping)
        mapping['referenceDocuments'] = {'14:1': 11}
        manifest, _, _ = self.prepare(source, mapping).artifacts()
        self.assertEqual(1, manifest['targetCounts']['ps_reference'])

    def test_real_human_review_sidecar_and_citation_fields_are_preserved(self):
        source, mapping = fixture()
        source['humanReviews'] = [{'runId': 'run-1', 'reportFile': 'eval-report.json', 'updatedAt': '2026-10-01T10:00:00Z', 'reviews': {'case-1': {'caseId': 'case-1', 'reviewer': 'legacy-user', 'correct': True, 'score': 5, 'note': '核对完成', 'reviewedAt': '2026-10-01T10:00:00Z'}}, 'agreement': {'reviewedCases': 1}}]
        source['tables']['conversations'][0]['reference_mappings_json'] = json.dumps({'1': {'fileMd5': 'a' * 32, 'chunkId': 0, 'matchedChunkText': '真实引用原文', 'citationStatus': 'SUPPORTED', 'citationReason': '证据支持'}})
        prepared = self.prepare(source, mapping)
        review = next(row for rows in prepared.groups.values() for table, row in rows if table == 'ps_evaluation_review')
        self.assertEqual(5, review['score'])
        self.assertEqual('2026-10-01 10:00:00.000000', review['created_at'])
        reference = next(row for rows in prepared.groups.values() for table, row in rows if table == 'ps_reference')
        self.assertEqual('真实引用原文', reference['evidence_text'])
        self.assertEqual('SUPPORTED', json.loads(reference['verification_json'])['citationStatus'])
        manifest, _, _ = prepared.artifacts()
        self.assertEqual(1, manifest['targetCounts']['ps_evaluation_review'])
        self.assertIn(('humanReviewSidecars', 'run-1'), prepared.raw_rows)


if __name__ == '__main__':
    unittest.main()
