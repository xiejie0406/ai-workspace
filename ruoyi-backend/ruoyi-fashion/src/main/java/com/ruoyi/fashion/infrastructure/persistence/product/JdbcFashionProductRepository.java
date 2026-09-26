package com.ruoyi.fashion.infrastructure.persistence.product;

import com.ruoyi.fashion.configuration.FashionModuleEnabled;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.ruoyi.fashion.application.product.port.FashionProductRepository;
import com.ruoyi.fashion.application.product.port.ProductSearchCriteria;
import com.ruoyi.fashion.domain.product.FashionProduct;
import com.ruoyi.fashion.domain.product.FashionProductImage;
import com.ruoyi.fashion.domain.product.FashionProductStatus;
import com.ruoyi.fashion.infrastructure.persistence.foundation.FashionJsonCodec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@FashionModuleEnabled
@Repository
public class JdbcFashionProductRepository implements FashionProductRepository {
    private static final String COLUMNS = """
            id,source_code,sku_code,style_code,name,category_code,color_code,color_name,size_code,size_system,unit,
            sale_price,currency,tax_mode,price_as_of,last_price_import_batch_id,brand,material,season,tags_json,
            main_image_key,images_json,visual_version,last_ai_run_id,attributes_confirmed,attributes_confirmed_by,
            attributes_confirmed_at,jd_item_id,jd_url,last_product_import_batch_id,status,create_by,create_time,
            update_by,update_time,row_version,attributes_json
            """;
    private static final Map<String, String> EDITABLE_COLUMNS = Map.ofEntries(
            Map.entry("attributes", "attributes_json"),
            Map.entry("name", "name"),
            Map.entry("categoryCode", "category_code"),
            Map.entry("colorCode", "color_code"),
            Map.entry("colorName", "color_name"),
            Map.entry("sizeCode", "size_code"),
            Map.entry("sizeSystem", "size_system"),
            Map.entry("unit", "unit"),
            Map.entry("brand", "brand"),
            Map.entry("material", "material"),
            Map.entry("season", "season"),
            Map.entry("tags", "tags_json"),
            Map.entry("jdItemId", "jd_item_id"),
            Map.entry("jdUrl", "jd_url"),
            Map.entry("lastAiRunId", "last_ai_run_id"),
            Map.entry("attributesConfirmed", "attributes_confirmed"));

    private final NamedParameterJdbcTemplate jdbc;
    private final FashionJsonCodec json;
    private final ObjectMapper objectMapper;
    private final RowMapper<FashionProduct> rowMapper = this::map;

    public JdbcFashionProductRepository(
            @Qualifier("fashionJdbcTemplate") NamedParameterJdbcTemplate jdbc,
            FashionJsonCodec json,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.json = json;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<FashionProduct> search(ProductSearchCriteria criteria) {
        Query query = query(criteria, false);
        return jdbc.query("select " + COLUMNS + " from fq_product " + query.sql()
                + " order by source_code,style_code,color_code,size_system,size_code,id limit :limit offset :offset",
                query.parameters(), rowMapper);
    }

    @Override
    public long count(ProductSearchCriteria criteria) {
        Query query = query(criteria, true);
        Long value = jdbc.queryForObject("select count(*) from fq_product " + query.sql(), query.parameters(), Long.class);
        return value == null ? 0L : value;
    }

    @Override
    public Optional<FashionProduct> findById(long id) {
        List<FashionProduct> values = jdbc.query(
                "select " + COLUMNS + " from fq_product where id=:id",
                Map.of("id", id), rowMapper);
        return values.stream().findFirst();
    }

    @Override
    public Map<String, FashionProduct> findByBusinessKeys(Set<String> businessKeys) {
        if (businessKeys.isEmpty()) {
            return Map.of();
        }
        List<FashionProduct> rows = jdbc.query(
                "select " + COLUMNS + " from fq_product where concat(source_code,':',sku_code) in (:keys)",
                Map.of("keys", businessKeys), rowMapper);
        Map<String, FashionProduct> result = new LinkedHashMap<>();
        rows.forEach(product -> result.put(product.businessKey(), product));
        return result;
    }

    @Override
    public List<FashionProduct> findByStyleColor(String sourceCode, String styleCode, String colorCode) {
        return jdbc.query("select " + COLUMNS + " from fq_product"
                        + " where source_code=:source and style_code=:style and color_code=:color order by size_code,id",
                Map.of("source", sourceCode, "style", styleCode, "color", colorCode), rowMapper);
    }

    @Override
    public List<FashionProduct> findActiveForScope(String sourceCode, String categoryCode) {
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("source", sourceCode);
        String categoryClause = "";
        if (categoryCode != null && !categoryCode.isBlank()) {
            categoryClause = " and category_code=:category";
            parameters.addValue("category", categoryCode);
        }
        return jdbc.query("select " + COLUMNS + " from fq_product"
                        + " where source_code=:source and status='active'" + categoryClause
                        + " order by sku_code,id",
                parameters, rowMapper);
    }

    @Override
    public void insert(FashionProduct p) {
        int changed = jdbc.update("""
                insert into fq_product (
                    id,source_code,sku_code,style_code,name,category_code,color_code,color_name,size_code,size_system,
                    unit,sale_price,currency,tax_mode,price_as_of,last_price_import_batch_id,brand,material,season,
                    tags_json,main_image_key,images_json,visual_version,last_ai_run_id,attributes_confirmed,
                    attributes_confirmed_by,attributes_confirmed_at,jd_item_id,jd_url,last_product_import_batch_id,
                    status,create_by,create_time,update_by,update_time,row_version,attributes_json)
                values (
                    :id,:source,:sku,:style,:name,:category,:color,:colorName,:size,:sizeSystem,:unit,:price,:currency,
                    :taxMode,:priceAsOf,:priceBatch,:brand,:material,:season,:tags,:mainImage,:images,:visualVersion,
                    :lastAiRun,:confirmed,:confirmedBy,:confirmedAt,:jdItemId,:jdUrl,:productBatch,:status,
                    :createBy,:createTime,:updateBy,:updateTime,:rowVersion,:attributes)
                """, parameters(p));
        if (changed != 1) {
            throw new IllegalStateException("新增商品未写入一行");
        }
    }

    @Override
    public boolean updateImported(FashionProduct p, long expectedRowVersion) {
        MapSqlParameterSource parameters = parameters(p).addValue("expectedVersion", expectedRowVersion);
        return jdbc.update("""
                update fq_product set
                    style_code=:style,name=:name,category_code=:category,color_code=:color,color_name=:colorName,
                    size_code=:size,size_system=:sizeSystem,unit=:unit,brand=:brand,material=:material,season=:season,
                    jd_item_id=:jdItemId,jd_url=:jdUrl,last_product_import_batch_id=:productBatch,attributes_json=:attributes,
                    update_by=:updateBy,update_time=:updateTime,row_version=row_version+1
                 where id=:id and row_version=:expectedVersion
                """, parameters) == 1;
    }

    @Override
    public boolean updateFields(long id, Map<String, Object> fields, long expectedRowVersion, long operatorId) {
        if (fields.isEmpty()) {
            return true;
        }
        List<String> assignments = new ArrayList<>();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", id).addValue("expectedVersion", expectedRowVersion)
                .addValue("operator", operatorId).addValue("now", Timestamp.from(Instant.now()));
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String column = EDITABLE_COLUMNS.get(entry.getKey());
            if (column == null) {
                throw new IllegalArgumentException("不允许修改商品字段：" + entry.getKey());
            }
            assignments.add(column + "=:f_" + entry.getKey());
            Object value = entry.getValue();
            if ("tags".equals(entry.getKey()) || "attributes".equals(entry.getKey())) {
                value = json.write(objectMapper.valueToTree(value));
            }
            if ("attributesConfirmed".equals(entry.getKey())) {
                boolean confirmed = Boolean.TRUE.equals(value);
                value = confirmed ? 1 : 0;
                assignments.add("attributes_confirmed_by=" + (confirmed ? ":operator" : "null"));
                assignments.add("attributes_confirmed_at=" + (confirmed ? ":now" : "null"));
            }
            parameters.addValue("f_" + entry.getKey(), value);
        }
        assignments.add("update_by=:operator");
        assignments.add("update_time=:now");
        assignments.add("row_version=row_version+1");
        return jdbc.update("update fq_product set " + String.join(",", assignments)
                + " where id=:id and row_version=:expectedVersion", parameters) == 1;
    }

    @Override
    public boolean updateStatus(
            long id, FashionProductStatus status, long expectedRowVersion, long operatorId) {
        return jdbc.update("""
                update fq_product set status=:status,update_by=:operator,update_time=:now,row_version=row_version+1
                 where id=:id and row_version=:version
                """, new MapSqlParameterSource()
                .addValue("status", status.code()).addValue("operator", operatorId)
                .addValue("now", Timestamp.from(Instant.now())).addValue("id", id)
                .addValue("version", expectedRowVersion)) == 1;
    }

    @Override
    public boolean updatePrice(
            long id,
            BigDecimal salePrice,
            String currency,
            String taxMode,
            Instant priceAsOf,
            Long batchId,
            long expectedRowVersion,
            long operatorId,
            Instant now) {
        return jdbc.update("""
                update fq_product set sale_price=:price,currency=:currency,tax_mode=:taxMode,
                    price_as_of=:priceAsOf,last_price_import_batch_id=:batchId,
                    update_by=:operator,update_time=:now,row_version=row_version+1
                 where id=:id and row_version=:version
                """, new MapSqlParameterSource()
                .addValue("price", salePrice).addValue("currency", currency).addValue("taxMode", taxMode)
                .addValue("priceAsOf", priceAsOf == null ? null : Timestamp.from(priceAsOf))
                .addValue("batchId", batchId).addValue("operator", operatorId)
                .addValue("now", Timestamp.from(now)).addValue("id", id).addValue("version", expectedRowVersion)) == 1;
    }

    @Override
    public boolean updateImages(
            long id,
            List<FashionProductImage> images,
            String mainImageKey,
            long expectedRowVersion,
            long operatorId) {
        return jdbc.update("""
                update fq_product set images_json=:images,main_image_key=:mainImage,
                    visual_version=visual_version+1,
                    update_by=:operator,update_time=:now,row_version=row_version+1
                 where id=:id and row_version=:version
                """, new MapSqlParameterSource()
                .addValue("images", json.write(images)).addValue("mainImage", mainImageKey)
                .addValue("operator", operatorId)
                .addValue("now", Timestamp.from(Instant.now())).addValue("id", id)
                .addValue("version", expectedRowVersion)) == 1;
    }

    private Query query(ProductSearchCriteria criteria, boolean count) {
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        addEquals(clauses, parameters, "source_code", "source", criteria.sourceCode());
        addEquals(clauses, parameters, "category_code", "category", criteria.categoryCode());
        addEquals(clauses, parameters, "status", "status", criteria.status());
        if (criteria.keyword() != null && !criteria.keyword().isBlank()) {
            clauses.add("(sku_code like :keyword or style_code like :keyword or name like :keyword)");
            parameters.addValue("keyword", "%" + criteria.keyword().trim() + "%");
        }
        if (criteria.attributeKey()!=null && criteria.attributeValue()!=null) {
            if(!com.ruoyi.fashion.domain.product.ProductAttributeFields.FIELDS.containsKey(criteria.attributeKey())) throw new IllegalArgumentException("属性筛选字段无效");
            clauses.add("json_unquote(json_extract(attributes_json,:attributePath)) like :attributeValue");
            parameters.addValue("attributePath", "$."+criteria.attributeKey()).addValue("attributeValue","%"+criteria.attributeValue()+"%");
        }
        if (!count) {
            parameters.addValue("limit", criteria.limit()).addValue("offset", criteria.offset());
        }
        return new Query(clauses.isEmpty() ? "" : "where " + String.join(" and ", clauses), parameters);
    }

    private static void addEquals(
            List<String> clauses, MapSqlParameterSource parameters, String column, String parameter, String value) {
        if (value != null && !value.isBlank()) {
            clauses.add(column + "=:" + parameter);
            parameters.addValue(parameter, value.trim());
        }
    }

    private MapSqlParameterSource parameters(FashionProduct p) {
        return new MapSqlParameterSource()
                .addValue("id", p.id()).addValue("source", p.sourceCode()).addValue("sku", p.skuCode())
                .addValue("style", p.styleCode()).addValue("name", p.name()).addValue("category", p.categoryCode())
                .addValue("color", p.colorCode()).addValue("colorName", p.colorName()).addValue("size", p.sizeCode())
                .addValue("sizeSystem", p.sizeSystem()).addValue("unit", p.unit()).addValue("price", p.salePrice())
                .addValue("currency", p.currency()).addValue("taxMode", p.taxMode())
                .addValue("priceAsOf", timestamp(p.priceAsOf())).addValue("priceBatch", p.lastPriceImportBatchId())
                .addValue("brand", p.brand()).addValue("material", p.material()).addValue("season", p.season())
                .addValue("tags", json.write(p.tags())).addValue("mainImage", p.mainImageKey())
                .addValue("images", json.write(p.images())).addValue("visualVersion", p.visualVersion())
                .addValue("lastAiRun", p.lastAiRunId()).addValue("confirmed", p.attributesConfirmed() ? 1 : 0)
                .addValue("confirmedBy", p.attributesConfirmedBy()).addValue("confirmedAt", timestamp(p.attributesConfirmedAt()))
                .addValue("jdItemId", p.jdItemId()).addValue("jdUrl", p.jdUrl())
                .addValue("productBatch", p.lastProductImportBatchId()).addValue("status", p.status().code())
                .addValue("createBy", p.createBy()).addValue("createTime", Timestamp.from(p.createTime()))
                .addValue("updateBy", p.updateBy()).addValue("updateTime", Timestamp.from(p.updateTime()))
                .addValue("rowVersion", p.rowVersion()).addValue("attributes", json.write(p.attributes()));
    }

    private FashionProduct map(ResultSet rs, int rowNum) throws SQLException {
        return new FashionProduct(
                rs.getLong("id"), rs.getString("source_code"), rs.getString("sku_code"),
                rs.getString("style_code"), rs.getString("name"), rs.getString("category_code"),
                rs.getString("color_code"), rs.getString("color_name"), rs.getString("size_code"),
                rs.getString("size_system"), rs.getString("unit"), rs.getBigDecimal("sale_price"),
                rs.getString("currency"), rs.getString("tax_mode"), instant(rs, "price_as_of"),
                nullableLong(rs, "last_price_import_batch_id"), rs.getString("brand"), rs.getString("material"),
                rs.getString("season"), read(rs.getString("tags_json"), new TypeReference<List<String>>() {}),
                rs.getString("main_image_key"),
                read(rs.getString("images_json"), new TypeReference<List<FashionProductImage>>() {}),
                rs.getLong("visual_version"), nullableLong(rs, "last_ai_run_id"),
                rs.getInt("attributes_confirmed") == 1, nullableLong(rs, "attributes_confirmed_by"),
                instant(rs, "attributes_confirmed_at"), rs.getString("jd_item_id"), rs.getString("jd_url"),
                nullableLong(rs, "last_product_import_batch_id"), FashionProductStatus.fromCode(rs.getString("status")),
                rs.getLong("create_by"), instant(rs, "create_time"), rs.getLong("update_by"),
                instant(rs, "update_time"), rs.getLong("row_version"),
                rs.getString("attributes_json") == null ? Map.of() : read(rs.getString("attributes_json"), new TypeReference<Map<String, String>>() {}));
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("商品 JSON 数据无效", exception);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record Query(String sql, MapSqlParameterSource parameters) {
    }
}
