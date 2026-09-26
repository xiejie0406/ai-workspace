package com.ruoyi.fashion.infrastructure.persistence.importing;

import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.*;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;

@FashionModuleEnabled
@Repository
public class FashionImportHistoryQuery {
    private final NamedParameterJdbcTemplate jdbc;
    public FashionImportHistoryQuery(@Qualifier("fashionJdbcTemplate") NamedParameterJdbcTemplate jdbc) {this.jdbc=jdbc;}
    public Map<String,Object> list(String kind, int page, int pageSize) {
        int size=Math.min(100,Math.max(1,pageSize)), current=Math.max(1,page);
        MapSqlParameterSource params=new MapSqlParameterSource().addValue("kind",kind).addValue("size",size).addValue("offset",(current-1)*size);
        List<Map<String,Object>> items=jdbc.query("select id,batch_no,source_code,warehouse_code,file_name,status,actual_count,error_count,as_of,create_time from fq_import_batch where import_type=:kind order by create_time desc,id desc limit :size offset :offset",params,(rs,row)-> {
            Map<String,Object> value=new LinkedHashMap<>();
            value.put("batchId",Long.toString(rs.getLong("id")));value.put("batchNo",rs.getString("batch_no"));
            value.put("sourceCode",rs.getString("source_code"));value.put("warehouseCode",rs.getString("warehouse_code"));
            value.put("fileName",rs.getString("file_name"));value.put("status",rs.getString("status"));
            value.put("actualCount",rs.getInt("actual_count"));value.put("errorCount",rs.getInt("error_count"));
            value.put("asOf",rs.getTimestamp("as_of").toInstant());value.put("createTime",rs.getTimestamp("create_time").toInstant());return value;
        });
        Long total=jdbc.queryForObject("select count(*) from fq_import_batch where import_type=:kind",params,Long.class);
        return Map.of("items",items,"total",total==null?0:total,"page",current,"pageSize",size);
    }
}
