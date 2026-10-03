package com.ruoyi.fashion.infrastructure.persistence.customer;

import com.ruoyi.fashion.configuration.FashionModuleEnabled;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.ruoyi.fashion.application.customer.port.FashionCustomerRepository;
import com.ruoyi.fashion.domain.customer.FashionCustomer;
import com.ruoyi.fashion.infrastructure.persistence.foundation.FashionJsonCodec;
import com.ruoyi.fashion.infrastructure.crypto.FashionCustomerContactCipher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@FashionModuleEnabled
@Repository
public class JdbcFashionCustomerRepository implements FashionCustomerRepository {
    @Override
    public Map<Long,String> salespersonNames(java.util.Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long,String> result = new java.util.LinkedHashMap<>();
        jdbc.query("select user_id,coalesce(nullif(nick_name,''),user_name) display_name from sys_user where user_id in (:ids)",
                Map.of("ids",ids),(org.springframework.jdbc.core.RowCallbackHandler)rs -> result.put(rs.getLong("user_id"),rs.getString("display_name")));
        return result;
    }
    private static final String COLUMNS = "id,code,name,customer_type,contact_name,contact_phone,region,"
            + "salesperson_id,collaborator_ids,internal_note,status,create_by,create_time,update_by,update_time,row_version";

    private final NamedParameterJdbcTemplate jdbc;
    private final FashionJsonCodec json;
    private final ObjectMapper objectMapper;
    private final FashionCustomerContactCipher contactCipher;
    private final RowMapper<FashionCustomer> mapper = this::map;

    public JdbcFashionCustomerRepository(
            @Qualifier("fashionJdbcTemplate") NamedParameterJdbcTemplate jdbc,
            FashionJsonCodec json,
            ObjectMapper objectMapper,
            FashionCustomerContactCipher contactCipher) {
        this.jdbc = jdbc;
        this.json = json;
        this.objectMapper = objectMapper;
        this.contactCipher = contactCipher;
    }

    @Override
    public List<FashionCustomer> search(
            long userId, boolean administrator, String status, String keyword, int offset, int limit) {
        Query query = query(userId, administrator, status, keyword);
        query.parameters().addValue("offset", offset).addValue("limit", limit);
        return jdbc.query("select " + COLUMNS + " from fq_customer " + query.where()
                + " order by update_time desc,id desc limit :limit offset :offset", query.parameters(), mapper);
    }

    @Override
    public long count(long userId, boolean administrator, String status, String keyword) {
        Query query = query(userId, administrator, status, keyword);
        Long value = jdbc.queryForObject(
                "select count(*) from fq_customer " + query.where(), query.parameters(), Long.class);
        return value == null ? 0 : value;
    }

    @Override
    public Optional<FashionCustomer> findById(long id) {
        return jdbc.query("select " + COLUMNS + " from fq_customer where id=:id", Map.of("id", id), mapper)
                .stream().findFirst();
    }

    @Override
    public Optional<FashionCustomer> findByCode(String code) {
        return jdbc.query("select " + COLUMNS + " from fq_customer where code=:code", Map.of("code", code), mapper)
                .stream().findFirst();
    }

    @Override
    public void insert(FashionCustomer c) {
        int changed = jdbc.update("""
                insert into fq_customer (
                    id,code,name,customer_type,contact_name,contact_phone,region,salesperson_id,collaborator_ids,
                    internal_note,status,create_by,create_time,update_by,update_time,row_version)
                values (:id,:code,:name,:type,:contactName,:contactPhone,:region,:salesperson,:collaborators,
                    :note,:status,:createBy,:createTime,:updateBy,:updateTime,:rowVersion)
                """, parameters(c));
        if (changed != 1) {
            throw new IllegalStateException("新增客户未写入一行");
        }
    }

    @Override
    public boolean update(FashionCustomer c, long expectedRowVersion) {
        return jdbc.update("""
                update fq_customer set name=:name,customer_type=:type,contact_name=:contactName,
                    contact_phone=:contactPhone,region=:region,salesperson_id=:salesperson,
                    collaborator_ids=:collaborators,internal_note=:note,update_by=:updateBy,update_time=:updateTime,
                    row_version=row_version+1
                 where id=:id and row_version=:expectedVersion
                """, parameters(c).addValue("expectedVersion", expectedRowVersion)) == 1;
    }

    @Override
    public boolean updateStatus(long id, String status, long expectedRowVersion, long operatorId, Instant now) {
        return jdbc.update("""
                update fq_customer set status=:status,update_by=:operator,update_time=:now,row_version=row_version+1
                 where id=:id and row_version=:version
                """, new MapSqlParameterSource().addValue("id", id).addValue("status", status)
                .addValue("operator", operatorId).addValue("now", Timestamp.from(now))
                .addValue("version", expectedRowVersion)) == 1;
    }

    private Query query(long userId, boolean administrator, String status, String keyword) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        java.util.ArrayList<String> clauses = new java.util.ArrayList<>();
        if (!administrator) {
            clauses.add("(salesperson_id=:userId or json_contains(collaborator_ids,cast(:userIdJson as json),'$'))");
            parameters.addValue("userId", userId).addValue("userIdJson", Long.toString(userId));
        }
        if (status != null && !status.isBlank()) {
            clauses.add("status=:status");
            parameters.addValue("status", status.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            clauses.add("(code like :keyword or name like :keyword)");
            parameters.addValue("keyword", "%" + keyword.trim() + "%");
        }
        return new Query(clauses.isEmpty() ? "" : "where " + String.join(" and ", clauses), parameters);
    }

    private MapSqlParameterSource parameters(FashionCustomer c) {
        return new MapSqlParameterSource().addValue("id", c.id()).addValue("code", c.code())
                .addValue("name", c.name()).addValue("type", c.customerType())
                .addValue("contactName", c.contactName()).addValue("contactPhone", contactCipher.encrypt(c.id(), c.contactPhone()))
                .addValue("region", c.region()).addValue("salesperson", c.salespersonId())
                .addValue("collaborators", json.write(c.collaboratorIds())).addValue("note", c.internalNote())
                .addValue("status", c.status()).addValue("createBy", c.createBy())
                .addValue("createTime", Timestamp.from(c.createTime())).addValue("updateBy", c.updateBy())
                .addValue("updateTime", Timestamp.from(c.updateTime())).addValue("rowVersion", c.rowVersion());
    }

    private FashionCustomer map(ResultSet rs, int rowNum) throws SQLException {
        return new FashionCustomer(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getString("customer_type"), rs.getString("contact_name"),
                contactCipher.decrypt(rs.getLong("id"), rs.getString("contact_phone")),
                rs.getString("region"), rs.getLong("salesperson_id"), collaborators(rs.getString("collaborator_ids")),
                rs.getString("internal_note"), rs.getString("status"), rs.getLong("create_by"),
                rs.getTimestamp("create_time").toInstant(), rs.getLong("update_by"),
                rs.getTimestamp("update_time").toInstant(), rs.getLong("row_version"));
    }

    private List<Long> collaborators(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<List<Long>>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("客户协作者 JSON 无效", exception);
        }
    }

    private record Query(String where, MapSqlParameterSource parameters) {
    }
}
