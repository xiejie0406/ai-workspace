package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.configuration.AipptProperties;
import com.ruoyi.aippt.domain.AipptRules;
import com.ruoyi.aippt.infrastructure.persistence.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.mybatis.spring.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 本批定向回归的隔离MyBatis/H2夹具，不加载Spring应用或外部配置。 */
public final class AipptPersistenceFixture {
    final AipptRepository db;
    final AipptMapper mapper;
    final TransactionTemplate tx;
    final AipptProperties props=new AipptProperties();
    final AipptService service;
    final CatalogService catalog;
    final String project;
    final long owner=42;
    public static Timestamp utc(int precision) { return Timestamp.from(Instant.now()); }
    public static String extract(String body,String path) {
        Object value=JSON.parseObject(body).get(path.substring(2));
        return value==null?null:JSON.toJSONString(value,JSONWriter.Feature.WriteMapNullValue);
    }
    public static String unquote(String json) { return json==null?null:String.valueOf(JSON.parse(json)); }

    AipptPersistenceFixture() throws Exception {
        JdbcDataSource ds=new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:parity-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try(Connection connection=ds.getConnection();Statement ddl=connection.createStatement()) {
            ddl.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.aippt.application.AipptPersistenceFixture.utc'");
            ddl.execute("CREATE ALIAS JSON_EXTRACT FOR 'com.ruoyi.aippt.application.AipptPersistenceFixture.extract'");
            ddl.execute("CREATE ALIAS JSON_UNQUOTE FOR 'com.ruoyi.aippt.application.AipptPersistenceFixture.unquote'");
            ddl.execute("CREATE TABLE aippt_dispatch_gate(id INT PRIMARY KEY)");
            ddl.execute("INSERT INTO aippt_dispatch_gate VALUES(1)");
            for(String table:List.of("project","source","source_section","outline","slide","asset","audit","task","proposal","export","snapshot","event","outbox","idempotency")) {
                ddl.execute("CREATE TABLE aippt_"+table+"(id VARCHAR(36) PRIMARY KEY,project_id VARCHAR(36),owner_user_id BIGINT,status VARCHAR(32),revision BIGINT,body VARCHAR(1000000),created_at TIMESTAMP,updated_at TIMESTAMP,event_seq BIGINT DEFAULT 0)");
            }
        }
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        var config=new org.apache.ibatis.session.Configuration();
        config.addMapper(AipptMapper.class);
        factory.setConfiguration(config);
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(AipptMapper.class);
        db=new AipptRepository(mapper);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        props.getWorker().setEnabled(true);
        props.getProvider().setEnabled(true);
        catalog=new CatalogService(props);
        service=new AipptService(db,tx,props,catalog);
        project=service.create(owner,1,"create",JSONObject.of("title","原功能回归","page_count",5)).getString("id");
    }
    JSONObject project() { return db.get("project",project); }
    JSONObject source() {
        JSONObject source=service.record(project,owner,"ready");
        String ref=UUID.randomUUID().toString();
        source.putAll(JSONObject.of("kind","text","parse_status","ready","parser_version","fixture","char_count",8,"sections",JSONArray.of(JSONObject.of("id",ref,"ref",ref,"heading","材料","text","真实业务材料","level",0))));
        db.insert("source",source);
        return source;
    }
    JSONArray pages(String ref) {
        JSONArray pages=new JSONArray();
        for(int i=0;i<5;i++)pages.add(JSONObject.of("id",UUID.randomUUID().toString(),"title","页面"+i,"objective","核对业务事实","key_points",JSONArray.of("第一要点","第二要点"),"source_refs",JSONArray.of(ref),"layout_id","bullets","page_role","content","visual",null));
        return pages;
    }
    JSONObject outline(String status) {
        JSONObject source=source(),outline=service.record(project,owner,status);
        outline.put("pages",pages(source.getJSONArray("sections").getJSONObject(0).getString("id")));
        outline.put("input_signature",AipptRules.fingerprint(project(),List.of(source)));
        db.insert("outline",outline);
        return outline;
    }
    JSONObject slide(JSONArray blocks) {
        JSONObject slide=service.record(project,owner,"ready");
        slide.putAll(AipptRules.blank());
        slide.put("blocks",blocks);
        slide.put("layout_tree",AipptRules.column(blocks));
        slide.put("position",1);
        db.insert("slide",slide);
        return slide;
    }
    TaskLeaseService leases() { return new TaskLeaseService(db,mapper,tx,service,props); }
}
