-- 只读核对SQL；金额与Token必须和manifest独立复算。
SELECT source_table,COUNT(*) AS row_count FROM ps_legacy_migration_row WHERE migration_batch_id=CONVERT(X'666978747572652d636f6e76657273696f6e2d726576696577' USING utf8mb4) GROUP BY source_table;
SELECT user_id,scope,available,frozen,consumed,debt FROM ps_token_account ORDER BY user_id,scope;
SELECT state,COUNT(*) AS order_count,SUM(amount_fen) AS amount_fen,SUM(llm_tokens) AS llm_tokens,SUM(embedding_tokens) AS embedding_tokens FROM ps_recharge_order GROUP BY state;
SELECT id,object_key,sha256,byte_size,user_id,visibility FROM ps_document ORDER BY id;
SELECT COUNT(*) AS chunk_count FROM ps_document_chunk;
SELECT source_table,COUNT(*) AS archive_count FROM ps_legacy_archive GROUP BY source_table;
SELECT source_table,source_id,migration_batch_id,CASE WHEN JSON_VALID(sanitized_json) THEN JSON_UNQUOTE(JSON_EXTRACT(sanitized_json,'$.migration.conversionSha256')) ELSE NULL END AS conversion_sha256 FROM ps_legacy_archive ORDER BY source_table,source_id;
