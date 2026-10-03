package com.ruoyi.fashion.migration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import com.ruoyi.fashion.configuration.persistence.FashionDatabasePreconditions;
import com.ruoyi.fashion.configuration.persistence.FashionFlywayFactory;
import org.junit.jupiter.api.Test;

/** 只模拟information_schema，不连接或写入任何数据库。 */
class FashionDatabasePreconditionsTest {
    @Test void acceptsLegalVersionSchemasBeforeMigration() throws Exception {
        for(int version=1;version<=4;version++) { var db=fixture(version);assertDoesNotThrow(()->FashionDatabasePreconditions.inspect(db.source,"ry-vue")); }
    }
    @Test void verifiesAllTwentyTablesAfterV4AndOnRestart() throws Exception {
        var db=fixture(4);assertEquals(20,db.columns.size());assertEquals("4",FashionFlywayFactory.EXPECTED_VERSION);
        assertDoesNotThrow(()->FashionDatabasePreconditions.verifyCurrentSchema(db.source,"ry-vue"));
        assertDoesNotThrow(()->FashionDatabasePreconditions.inspect(db.source,"ry-vue"));
    }
    @Test void rejectsOldVersionForBusinessReadiness() throws Exception {
        var db=fixture(1);assertThrows(IllegalStateException.class,()->FashionDatabasePreconditions.verifyCurrentSchema(db.source,"ry-vue"));
    }
    @Test void rejectsMissingColumnsConstraintsAndUnknownTables() throws Exception {
        var columns=fixture(4);columns.columns.get("fq_product").remove("attributes_json");
        assertThrows(IllegalStateException.class,()->FashionDatabasePreconditions.inspect(columns.source,"ry-vue"));
        var constraints=fixture(4);constraints.constraints.get("fq_proposal_delivery").remove("fk_fq_proposal_delivery_pool");
        assertThrows(IllegalStateException.class,()->FashionDatabasePreconditions.inspect(constraints.source,"ry-vue"));
        var unknown=fixture(4);unknown.columns.put("fq_surprise",new HashSet<>());
        assertThrows(IllegalStateException.class,()->FashionDatabasePreconditions.inspect(unknown.source,"ry-vue"));
    }
    @Test void rejectsWrongDatabaseFailedHistoryAndHalfMigration() throws Exception {
        var wrong=fixture(4);assertThrows(IllegalStateException.class,()->FashionDatabasePreconditions.inspect(wrong.source,"another"));
        var failed=fixture(4);failed.failed=true;assertThrows(IllegalStateException.class,()->FashionDatabasePreconditions.inspect(failed.source,"ry-vue"));
        var half=fixture(1);half.columns.get("fq_product").add("attributes_json");assertThrows(IllegalStateException.class,()->FashionDatabasePreconditions.inspect(half.source,"ry-vue"));
        var halfPrice=fixture(2);halfPrice.columns.get("fq_product").add("group_price");assertThrows(IllegalStateException.class,()->FashionDatabasePreconditions.inspect(halfPrice.source,"ry-vue"));
        var halfCosts=fixture(3);halfCosts.columns.get("fq_proposal_image_task").add("actual_cost");assertThrows(IllegalStateException.class,()->FashionDatabasePreconditions.inspect(halfCosts.source,"ry-vue"));
        var nineteen=fixture(4);nineteen.columns.remove("fq_proposal_delivery");assertThrows(IllegalStateException.class,()->FashionDatabasePreconditions.inspect(nineteen.source,"ry-vue"));
        var gap=fixture(4);gap.gap=true;assertThrows(IllegalStateException.class,()->FashionDatabasePreconditions.inspect(gap.source,"ry-vue"));
    }

    private Fixture fixture(int version) throws Exception {
        var db=new Fixture(version);
        Path directory=Path.of("src/main/resources/db/fashion-migration");
        var create=Pattern.compile("(?is)create table (fq_[a-z_]+)\\s*\\((.*?)\\) engine");
        var alter=Pattern.compile("(?is)alter table (fq_[a-z_]+)(.*?);");
        var col=Pattern.compile("(?m)^    ([a-z][a-z0-9_]*)\\s+(?!key)");
        var constraint=Pattern.compile("(?i)(?:constraint|unique key) ([a-z][a-z0-9_]*)");
        try(var files=Files.list(directory)) {
            for(Path file:files.sorted().toList()) {
                int number=Integer.parseInt(file.getFileName().toString().substring(1,2));if(number>version)continue;
                String sql=Files.readString(file);var tables=create.matcher(sql);
                while(tables.find()) {String table=tables.group(1);var names=new HashSet<String>();var cols=col.matcher(tables.group(2));while(cols.find())if(!Set.of("primary","unique","key","constraint").contains(cols.group(1)))names.add(cols.group(1));db.columns.put(table,names);var checks=new HashSet<String>();var cs=constraint.matcher(tables.group(2));while(cs.find())checks.add(cs.group(1));db.constraints.put(table,checks);}
                var alterations=alter.matcher(sql);while(alterations.find()){String table=alterations.group(1);var added=Pattern.compile("(?i)add column ([a-z_]+)").matcher(alterations.group(2));while(added.find())db.columns.get(table).add(added.group(1));var checks=constraint.matcher(alterations.group(2));while(checks.find())db.constraints.get(table).add(checks.group(1));}
            }
        }
        Connection connection=mock(Connection.class);db.source=mock(DataSource.class);when(db.source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(call->{String sql=call.getArgument(0);PreparedStatement stmt=mock(PreparedStatement.class);Map<Integer,String> params=new HashMap<>();doAnswer(c->{params.put(c.getArgument(0),c.getArgument(1));return null;}).when(stmt).setString(anyInt(),anyString());when(stmt.executeQuery()).thenAnswer(c->{List<Map<String,Object>> rows=new ArrayList<>();
            if(sql.equals("select database()"))rows.add(Map.of("value","ry-vue"));
            else if(sql.startsWith("select count(*)"))rows.add(Map.of("value",1));
            else if(sql.contains("select table_name"))db.columns.keySet().forEach(x->rows.add(Map.of("value",x)));
            else if(sql.contains("select column_name"))db.columns.getOrDefault(params.get(2),Set.of()).forEach(x->rows.add(Map.of("value",x)));
            else if(sql.contains("select constraint_name"))db.constraints.getOrDefault(params.get(2),Set.of()).forEach(x->rows.add(Map.of("value",x)));
            else if(sql.contains(FashionFlywayFactory.HISTORY_TABLE)) {rows.add(Map.of("version","0","type","BASELINE","success",true));for(int v=1;v<=db.version;v++)if(!db.gap||v!=2)rows.add(Map.of("version",String.valueOf(v),"type","SQL","success",!db.failed));}
            else throw new AssertionError(sql);
            return results(rows);});return stmt;});return db;
    }
    private ResultSet results(List<Map<String,Object>> rows) throws SQLException {
        ResultSet result=mock(ResultSet.class);AtomicInteger position=new AtomicInteger(-1);when(result.next()).thenAnswer(c->position.incrementAndGet()<rows.size());
        when(result.getString(anyInt())).thenAnswer(c->rows.get(position.get()).values().iterator().next().toString());
        when(result.getString(anyString())).thenAnswer(c->rows.get(position.get()).get(c.getArgument(0)).toString());
        when(result.getInt(anyInt())).thenAnswer(c->((Number)rows.get(position.get()).values().iterator().next()).intValue());
        when(result.getBoolean(anyString())).thenAnswer(c->rows.get(position.get()).get(c.getArgument(0)));return result;
    }
    private static class Fixture { final int version;boolean failed,gap;DataSource source;Map<String,Set<String>> columns=new HashMap<>(),constraints=new HashMap<>();Fixture(int version){this.version=version;} }
}
