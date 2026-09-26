package com.ruoyi.fashion.infrastructure.persistence.stock;

import java.util.*;
import java.time.*;
import java.sql.Timestamp;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import com.ruoyi.system.service.ISysConfigService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@FashionModuleEnabled
@Repository
public class FashionStockQuery {
    private final NamedParameterJdbcTemplate jdbc;
    private final ISysConfigService config;
    public FashionStockQuery(@Qualifier("fashionJdbcTemplate") NamedParameterJdbcTemplate jdbc, ISysConfigService config){this.jdbc=jdbc;this.config=config;}
    public Map<String,Object> list(String warehouse,String source,String keyword,String mode,int page,int pageSize) {
        if(warehouse==null||warehouse.isBlank()) throw new ServiceException("请选择库存仓库");
        if(!Set.of("all","positive","zero","missing","stale").contains(mode)) throw new ServiceException("库存筛选无效");
        int hours=24;
        try{int value=Integer.parseInt(config.selectConfigByKey("fashion.stock.freshnessHours"));if(value>=1&&value<=168)hours=value;}catch(NumberFormatException ignored){}
        int size=Math.min(200,Math.max(1,pageSize)),current=Math.max(1,page);
        MapSqlParameterSource params=new MapSqlParameterSource().addValue("warehouse",warehouse.trim()).addValue("size",size).addValue("offset",(current-1)*size)
                .addValue("cutoff",Timestamp.from(Instant.now().minus(Duration.ofHours(hours))));
        String where=" where 1=1";
        if(source!=null&&!source.isBlank()){where+=" and p.source_code=:source";params.addValue("source",source.trim());}
        if(keyword!=null&&!keyword.isBlank()){where+=" and (p.name like :keyword or p.sku_code like :keyword or p.style_code like :keyword)";params.addValue("keyword","%"+keyword.trim()+"%");}
        where+=switch(mode){case "positive"->" and s.available_qty>0";case "zero"->" and s.available_qty=0";case "missing"->" and s.id is null";case "stale"->" and s.as_of<:cutoff";default->"";};
        String from=" from fq_product p left join fq_stock s on s.product_id=p.id and s.warehouse_code=:warehouse"+where;
        List<Map<String,Object>> items=jdbc.query("select p.id,p.source_code,p.sku_code,p.style_code,p.name,p.color_code,p.color_name,p.size_code,p.size_system,p.status,s.id stock_id,s.available_qty,s.as_of,s.last_import_batch_id"+from+" order by p.source_code,p.style_code,p.color_code,p.size_system,p.size_code,p.id limit :size offset :offset",params,(rs,n)->{
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("productId",Long.toString(rs.getLong("id")));row.put("sourceCode",rs.getString("source_code"));row.put("skuCode",rs.getString("sku_code"));row.put("styleCode",rs.getString("style_code"));row.put("name",rs.getString("name"));
            row.put("colorCode",rs.getString("color_code"));row.put("colorName",rs.getString("color_name"));row.put("sizeCode",rs.getString("size_code"));row.put("sizeSystem",rs.getString("size_system"));row.put("status",rs.getString("status"));row.put("warehouseCode",warehouse);
            row.put("availableQty",rs.getObject("stock_id")==null?null:rs.getInt("available_qty"));
            Timestamp asOf=rs.getTimestamp("as_of");row.put("asOf",asOf==null?null:asOf.toInstant());
            row.put("stale",asOf!=null&&asOf.before((Timestamp)params.getValue("cutoff")));
            Object batch=rs.getObject("last_import_batch_id");row.put("batchId",batch==null?null:batch.toString());return row;
        });
        Long total=jdbc.queryForObject("select count(*)"+from,params,Long.class);
        return Map.of("items",items,"total",total==null?0:total,"page",current,"pageSize",size,"freshnessHours",hours);
    }
}
