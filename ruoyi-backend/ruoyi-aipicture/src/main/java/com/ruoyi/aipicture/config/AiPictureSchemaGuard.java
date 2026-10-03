package com.ruoyi.aipicture.config;

import java.util.*;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import com.ruoyi.aipicture.persistence.AiPictureRepository;
import com.ruoyi.aipicture.domain.Json;

/** 只读核对本项目必需表，不执行 migrate/baseline/clean 或建库。 */
@Component @AiPictureEnabled
public class AiPictureSchemaGuard implements SmartInitializingSingleton {
    private final AiPictureRepository db;
    public AiPictureSchemaGuard(AiPictureRepository db) {this.db=db;}
    @Override public void afterSingletonsInstantiated() {
        var expected=Map.ofEntries(Map.entry("aip_asset",List.of("user_id","object_key","sha256","run_id","attempt")),Map.entry("aip_session",List.of("user_id","document","revision","row_version","history_floor","active_run_id")),Map.entry("aip_session_asset",List.of("user_id","latest_document","document_version")),Map.entry("aip_history",List.of("seq","before_document","after_document")),Map.entry("aip_selection",List.of("revision","mask_asset_id","expires_at")),Map.entry("aip_run",List.of("input_snapshot","input_version","attempt","lease_token","lease_expires_at","event_seq")),Map.entry("aip_dispatch",List.of("run_id","status","available_at")),Map.entry("aip_run_event",List.of("run_id","event_seq","payload")),Map.entry("aip_agent_turn",List.of("base_version","plan_hash","steps")),Map.entry("aip_plan_step",List.of("dependencies","run_id","status")),Map.entry("aip_batch",List.of("pipeline_hash","formats")),Map.entry("aip_batch_item",List.of("output_ids","attempt")),Map.entry("aip_export",List.of("manifest","sha256")),Map.entry("aip_evaluation",List.of("dataset_hash","metrics")),Map.entry("aip_import_map",List.of("source_id","target_id")),Map.entry("aip_worker_nonce",List.of("service_id","nonce","created_at")),Map.entry("aip_command",List.of("user_id","command","request_hash","result")));
        for(var table:expected.entrySet()) {
            var columns=db.query("SELECT COLUMN_NAME AS column_name FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=:table",Json.of("table",table.getKey()));
            var names=new HashSet<String>();for(var column:columns) names.add(Json.string(column.get("column_name")));
            if(!names.containsAll(table.getValue())) throw new IllegalStateException("图片业务表未准备或不兼容："+table.getKey()+"；需在获准现有MySQL应用候选DDL，不自动迁移");
        }
    }
}
