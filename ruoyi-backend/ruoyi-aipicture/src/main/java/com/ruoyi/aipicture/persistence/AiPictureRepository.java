package com.ruoyi.aipicture.persistence;

import com.ruoyi.aipicture.config.AiPictureEnabled;
import com.ruoyi.aipicture.domain.*;
import java.sql.ResultSet;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 沿用选品模块显式 JDBC / 事务设施；表名由代码固定，数据永远参数化。 */
@Repository
@AiPictureEnabled
public class AiPictureRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public AiPictureRepository(@Qualifier("aipictureJdbcTemplate") NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    public int execute(String sql,Map<String,?> p) { return jdbc.update(sql,p); }
    public List<Map<String,Object>> query(String sql,Map<String,?> p) {
        return jdbc.query(sql,p,(rs,n)->row(rs));
    }
    public Map<String,Object> one(String sql,Map<String,?> p) {
        var rows=query(sql,p); return rows.isEmpty()?null:rows.get(0);
    }
    public Map<String,Object> owned(String table,String id,long owner,boolean lock) {
        if(!Set.of("aip_asset","aip_session","aip_run","aip_batch","aip_export","aip_evaluation").contains(table)) throw new IllegalArgumentException("未知业务表");
        var row=one("SELECT * FROM "+table+" WHERE id=:id AND user_id=:owner"+(lock?" FOR UPDATE":""),Json.of("id",Json.uuid(id),"owner",owner));
        if(row==null) throw AiPictureException.missing(); return row;
    }
    public long count(String sql,Map<String,?> p) { return jdbc.queryForObject(sql,p,Long.class); }
    private Map<String,Object> row(ResultSet rs) throws java.sql.SQLException {
        var result=new LinkedHashMap<String,Object>(); var metadata=rs.getMetaData();
        for(int i=1;i<=metadata.getColumnCount();i++) {
            String key=metadata.getColumnLabel(i); Object value=rs.getObject(i);
            if(value instanceof byte[] bytes && "JSON".equalsIgnoreCase(metadata.getColumnTypeName(i))) value=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
            if(value!=null && ("JSON".equalsIgnoreCase(metadata.getColumnTypeName(i))||Set.of("document","latest_document","params","result","before_document","after_document","markers","strokes","input_snapshot","pipeline","formats","output_ids","manifest","steps","provider","payload","metrics").contains(key))) {
                if(value instanceof String s && !s.isBlank()) value=com.alibaba.fastjson2.JSON.parse(s);
            }
            result.put(key,value);
        }
        return result;
    }
}
