package com.ruoyi.paismart.infrastructure;

import com.ruoyi.paismart.configuration.PaismartEnabled;
import java.sql.DatabaseMetaData;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 只读取当前连接的表与列元数据；不执行DDL、初始化账户或切换数据库。 */
@Component
@PaismartEnabled
public class SchemaReadiness {
    private final JdbcTemplate jdbc;
    // 与审核后的V001—V005保持同步。字段检查不能代替约束、数据和外部服务验证。
    private static final Map<String,List<String>> REQUIRED = required();
    public SchemaReadiness(@Qualifier("paismartJdbc") JdbcTemplate jdbc) {this.jdbc=jdbc;}

    /** 缺表返回表名，缺列返回table.column；额外表/字段允许共存。 */
    public List<String> missing() {
        return jdbc.execute((ConnectionCallback<List<String>>) connection -> {
            String catalog=connection.getCatalog(),schema=connection.getSchema();
            DatabaseMetaData metadata=connection.getMetaData();
            Set<String> tables=new HashSet<>();
            Map<String,Set<String>> columns=new HashMap<>();
            try(var result=metadata.getTables(catalog,schema,"%",new String[]{"TABLE"})) {
                while(result.next()) tables.add(normalize(result.getString("TABLE_NAME")));
            }
            // 一次读取当前schema列元数据，避免把下划线当JDBC通配符扩大逐表匹配范围。
            try(var result=metadata.getColumns(catalog,schema,"%","%")) {
                while(result.next()) {
                    String table=normalize(result.getString("TABLE_NAME"));
                    if(REQUIRED.containsKey(table)) columns.computeIfAbsent(table,key -> new HashSet<>())
                        .add(normalize(result.getString("COLUMN_NAME")));
                }
            }
            List<String> absent=new ArrayList<>();
            REQUIRED.forEach((table,fields) -> {
                if(!tables.contains(table)) absent.add(table);
                else for(String field:fields) if(!columns.getOrDefault(table,Set.of()).contains(field))
                    absent.add(table+"."+field);
            });
            if(tables.contains("paismart_schema_history") && columns.getOrDefault("paismart_schema_history",Set.of()).contains("version_no")) {
                Set<Integer> installed=new HashSet<>();
                try(var statement=connection.prepareStatement("SELECT version_no FROM paismart_schema_history WHERE version_no BETWEEN 1 AND 5");var versions=statement.executeQuery()) {
                    while(versions.next()) installed.add(versions.getInt(1));
                }
                for(int version=1;version<=5;version++) if(!installed.contains(version)) absent.add("paismart_schema_history.version:"+version);
            }
            return List.copyOf(absent);
        });
    }
    private static String normalize(String name) {return name.toLowerCase(Locale.ROOT);}
    static Map<String,List<String>> required() {
        Map<String,List<String>> result=new LinkedHashMap<>();
        String specification="""
            ps_member user_id status membership_version created_at updated_at
            ps_invite id code space_id max_uses used_count expires_at enabled initial_llm_tokens initial_embedding_tokens row_version created_by created_at
            ps_invite_redemption id invite_id user_id created_at
            ps_mutation_request user_id kind request_key input_json response_json created_at
            ps_legacy_identity_map source_user_id user_id migration_batch_id source_hash created_by created_at
            ps_token_account user_id scope available frozen consumed debt row_version
            ps_token_reservation id user_id scope operation_id mode reserved_tokens actual_tokens state business_day created_at updated_at
            ps_token_ledger id user_id scope operation_key action tokens available_after frozen_after debt_after reason operator_id created_at
            ps_daily_usage user_id business_day scope used_tokens reserved_tokens request_count
            ps_quota_policy user_id mode daily_llm_tokens daily_embedding_tokens row_version
            ps_rate_limit_policy id chat_per_minute upload_per_minute daily_chat_tokens max_file_bytes rules_json row_version
            ps_model_budget_window budget_kind window_seconds window_start used_tokens reserved_tokens
            ps_model_budget_reservation reservation_id budget_kind minute_seconds minute_start day_seconds day_start reserved_tokens actual_tokens state
            ps_recharge_package id name amount_fen llm_tokens embedding_tokens description benefit enabled sort_order deleted row_version created_at
            ps_recharge_order id user_id request_key trade_no package_id snapshot_json amount_fen llm_tokens embedding_tokens state code_url transaction_id error_message expires_at paid_at created_at
            ps_payment_event id event_id trade_no transaction_id amount_fen payload_hash created_at
            ps_job id user_id document_id type state payload_json result_json request_key retries max_retries next_retry_at lease_token lease_until error_message row_version created_at updated_at
            ps_job_checkpoint job_id step payload_json updated_at
            ps_outbox id job_id topic payload_json state attempts next_attempt_at created_at
            ps_consumer_receipt event_id job_id created_at
            ps_operation_plan id type state payload_json manifest_hash reason created_by approved_by job_id row_version created_at
            ps_audit_event id user_id action resource_id reason created_at
            paismart_schema_history version_no description installed_at
            ps_space id parent_id name description system_key type dept_id owner_id upload_limit upload_max_size_bytes row_version created_at updated_at
            ps_member_space user_id space_id primary_flag upload_allowed manager_allowed created_at
            ps_document id user_id space_id visibility file_name sha256 legacy_md5 object_key byte_size mime_type status version content_text error_message row_version estimated_tokens actual_tokens page_count process_job_id deleted_at created_at updated_at
            ps_upload id user_id space_id visibility file_name byte_size sha256 legacy_md5 chunk_size total_chunks status document_id request_key created_at expires_at
            ps_upload_chunk upload_id chunk_no byte_size sha256 object_key created_at
            ps_upload_request user_id request_key upload_id payload_hash created_at
            ps_intelligence_lock id owner fence_revision lease_until
            ps_document_chunk id document_id content_version chunk_no page_no text_content context_text anchor_text token_count vector_json embedding_revision embedding_input_hash created_at
            ps_provider id scope name endpoint model dimension secret_ref enabled active revision config_json updated_at
            ps_model_call id user_id operation_id scope request_hash status reservation_id response_json actual_tokens estimated reason created_at updated_at
            ps_index_revision id index_name provider_snapshot state checkpoint_document processed_count source_watermark validation_json error_message row_version created_at updated_at
            ps_index_switch id from_revision to_revision state error_message created_at updated_at
            ps_document_conflict id new_document_id old_document_id new_version old_version new_chunk_id old_chunk_id pair_key reason_json status action resolved_by note row_version created_at resolved_at
            ps_conversation id user_id title archived space_id row_version created_at updated_at
            ps_chat_message id conversation_id role content references_json state created_at
            ps_chat_generation id conversation_id user_id assistant_message_id client_request_id state execution_attempt next_sequence stop_requested request_json error_message created_at updated_at
            ps_chat_event generation_id sequence_no event_json created_at
            ps_ws_ticket ticket_hash user_id origin platform_session expires_at consumed_at created_at
            ps_reference id message_id document_id content_version chunk_id reference_no page_no anchor_text evidence_text metadata_json verification_json created_at
            ps_tool_feedback id user_id conversation_id rating note created_at
            ps_ocr_ticket token_hash job_id document_id content_version lease_token max_uses used_count expires_at created_at
            ps_ocr_call id user_id operation_id job_id document_id content_version image_hash image_bytes state actual_pages provider_request_id response_json reason created_at updated_at
            ps_evaluation id user_id label state config_json dataset_json report_json job_id row_version created_at updated_at
            ps_evaluation_case evaluation_id case_id result_json
            ps_evaluation_review id evaluation_id case_id reviewer_id correct score reason revision created_at
            ps_evaluation_dataset id user_id label object_key sha256 byte_size case_count created_at
            ps_operation_item plan_id item_key payload_hash state result_json updated_at
            ps_legacy_migration_batch id manifest_hash source_snapshot source_hash created_at
            ps_legacy_migration_row source_table source_id source_hash target_table target_id migration_batch_id created_at
            ps_legacy_archive source_table source_id sanitized_json migration_batch_id
            ps_bootstrap_document logical_key user_id space_id document_id file_name sha256 row_version updated_at
            """;
        for(String line:specification.strip().split("\\R")) {
            String[] parts=line.trim().split("\\s+");
            result.put(parts[0],List.copyOf(Arrays.asList(parts).subList(1,parts.length)));
        }
        return Collections.unmodifiableMap(result);
    }
}
