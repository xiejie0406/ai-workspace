package com.ruoyi.paiflow.service;

import com.ruoyi.paiflow.mapper.DataMapper;
import java.io.InputStream;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 真实MyBatis XML和隔离H2行事务；不使用项目MySQL或执行026正式迁移。 */
class DataMapperPersistenceTest {
    SqlSessionFactory factory() throws Exception {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:data"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        try(var connection=source.getConnection();var statement=connection.createStatement()){
            statement.execute("CREATE TABLE paiflow_data_database(id BIGINT PRIMARY KEY,space_id BIGINT,owner_user_id BIGINT,name VARCHAR(128),description VARCHAR(2000),avatar_icon VARCHAR(1024),avatar_color VARCHAR(128),revision BIGINT,deleted INT,create_time TIMESTAMP,update_time TIMESTAMP)");
            statement.execute("CREATE TABLE paiflow_data_table(id BIGINT PRIMARY KEY,db_id BIGINT,name VARCHAR(63),description VARCHAR(2000),fields_json VARCHAR(10000),revision BIGINT,deleted INT,create_time TIMESTAMP,update_time TIMESTAMP)");
            statement.execute("CREATE TABLE paiflow_data_row(id BIGINT AUTO_INCREMENT PRIMARY KEY,table_id BIGINT,exec_env INT,row_json VARCHAR(10000))");
            statement.execute("CREATE TABLE paiflow_data_request(run_id VARCHAR(36),attempt INT,request_key_hash VARCHAR(64),request_hash VARCHAR(64),response_json VARCHAR(10000),PRIMARY KEY(run_id,attempt,request_key_hash))");
            String completionSql=java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("paiflow.moduleSql","sql"),"031-paiflow-data-completion.sql"));
            statement.execute(completionSql.replaceAll("(?i) CHARACTER SET ascii COLLATE ascii_bin","").replaceAll("(?is)\\s*ENGINE=InnoDB.*;\\s*$",";"));
            statement.execute("INSERT INTO paiflow_data_database VALUES(5,2,1,'测试库','','','',4,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO paiflow_data_table VALUES(7,5,'people','','[]',3,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        }
        var configuration=new Configuration(new Environment("local",new JdbcTransactionFactory(),source));configuration.setMapUnderscoreToCamelCase(false);
        String path="mapper/paiflow/DataMapper.xml";try(InputStream stream=DataMapperPersistenceTest.class.getClassLoader().getResourceAsStream(path)){assertNotNull(stream);new XMLMapperBuilder(stream,configuration,path,configuration.getSqlFragments()).parse();}
        return new SqlSessionFactoryBuilder().build(configuration);
    }
    @Test void explicitColumnsMapWithoutGlobalUnderscoreOptionAndRowsStayInTheirEnvironment() throws Exception {
        var factory=factory();try(var session=factory.openSession()){var mapper=session.getMapper(DataMapper.class);var db=mapper.database(5);assertEquals(2L,db.getSpaceId());assertEquals(1L,db.getOwnerUserId());assertEquals(4L,db.getRevision());assertNotNull(db.getCreateTime());var table=mapper.table(7);assertEquals(5L,table.getDbId());assertEquals("[]",table.getFieldsJson());
            var one=new HashMap<String,Object>();one.put("table",7L);one.put("env",1);one.put("json","{\"name\":\"测试\"}");assertEquals(1,mapper.insertRow(one));assertNotNull(one.get("id"));var two=new HashMap<String,Object>();two.put("table",7L);two.put("env",2);two.put("json","{\"name\":\"正式\"}");assertEquals(1,mapper.insertRow(two));
            assertEquals(1,mapper.countRows(7,1));assertEquals("{\"name\":\"测试\"}",mapper.rows(7,1,10).get(0).get("rowJson"));assertEquals("{\"name\":\"正式\"}",mapper.rows(7,2,10).get(0).get("rowJson"));assertEquals(0,mapper.deleteRow(7,1,((Number)two.get("id")).longValue()));session.rollback();}
        try(var session=factory.openSession()){assertEquals(0,session.getMapper(DataMapper.class).countRows(7,1));assertEquals(0,session.getMapper(DataMapper.class).countRows(7,2));}
    }
    @Test void receiptAndRowsRollBackTogetherAndReceiptRoundTrips() throws Exception {
        var factory=factory();String run="33333333-3333-3333-3333-333333333333";try(var session=factory.openSession()){var mapper=session.getMapper(DataMapper.class);assertNull(mapper.request(run,1,"key"));assertEquals(1,mapper.record(run,1,"key","hash","{\"code\":0}"));assertEquals("hash",mapper.request(run,1,"key").get("requestHash"));assertEquals("{\"code\":0}",mapper.request(run,1,"key").get("responseJson"));session.rollback();}
        try(var session=factory.openSession()){assertNull(session.getMapper(DataMapper.class).request(run,1,"key"));assertEquals(1,session.getMapper(DataMapper.class).record(run,1,"key","hash","{\"code\":0}"));session.commit();}
        try(var session=factory.openSession()){assertEquals("hash",session.getMapper(DataMapper.class).request(run,1,"key").get("requestHash"));}
    }
}
