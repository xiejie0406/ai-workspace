package com.ruoyi.paicli.migration;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import com.ruoyi.paicli.domain.PcTable;

/** 应用只读核对既有MySQL库和已授权迁移版本，不建库、不自动迁移。 */
public final class PcSchemaGuard {
    private PcSchemaGuard(){}
    public static void verify(DataSource source,String expected)throws SQLException{
        try(Connection c=source.getConnection()){
            if(!"MySQL".equalsIgnoreCase(c.getMetaData().getDatabaseProductName())||!expected.equals(c.getCatalog()))throw new IllegalStateException("PaiCLI连接不是明确批准的MySQL数据库");
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT version FROM pc_flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")){if(!r.next()||!"5".equals(r.getString(1)))throw new IllegalStateException("PaiCLI迁移版本不完整，请先通过显式迁移流程核对");}
            for(PcTable t:PcTable.values())try(PreparedStatement s=c.prepareStatement("SELECT id FROM "+t.getSqlName()+" WHERE 1=0")){s.executeQuery().close();}
            try(var in=PcSchemaGuard.class.getResourceAsStream("/paicli/functional-schema-v4.json")){
                if(in==null)throw new IllegalStateException("功能字段清单缺失");Map<?,?> manifest=new tools.jackson.databind.ObjectMapper().readValue(in,Map.class);Map<?,?> columns=(Map<?,?>)manifest.get("columns");for(var entry:columns.entrySet()){Set<String> actual=new HashSet<>();try(PreparedStatement s=c.prepareStatement("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND EXTRA LIKE '%GENERATED%' AND GENERATION_EXPRESSION IS NOT NULL")){s.setString(1,expected);s.setString(2,"pc_"+entry.getKey());try(ResultSet r=s.executeQuery()){while(r.next())actual.add(r.getString(1));}}for(Object value:(java.util.List<?>)entry.getValue())if(!actual.contains(((java.util.List<?>)value).get(0).toString()))throw new IllegalStateException("PaiCLI功能生成字段缺失：pc_"+entry.getKey()+"."+((java.util.List<?>)value).get(0));}
            }catch(java.io.IOException e){throw new IllegalStateException("功能字段清单无法读取",e);}

        }
    }
}
