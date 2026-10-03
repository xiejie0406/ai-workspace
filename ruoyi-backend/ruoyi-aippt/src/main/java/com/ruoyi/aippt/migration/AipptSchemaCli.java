package com.ruoyi.aippt.migration;
import com.ruoyi.aippt.application.AssetService;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
/** 离线显式迁移入口，Spring 启动从不调用；不提供 clean、baseline 或创建数据库。 */
public final class AipptSchemaCli  {
    private AipptSchemaCli()  {
    }
    public static void main(String[] args) throws Exception  {
        byte[] script;
        try(var input=AipptSchemaCli.class.getResourceAsStream("/db/aippt-migration/V1__create_aippt_tables.sql"))  {
            if(input==null)throw new IllegalStateException("迁移脚本缺失");
            script=input.readAllBytes();
        }
        String digest=AssetService.bytesHash(script);
        if(!Arrays.asList(args).contains("--apply"))  {
            System.out.println("仅计划：MySQL 8 独立 aippt_schema_history，V1 sha256="+digest+"；未连接数据库或执行DDL。");
            return;
        }
        requireGate("AIPPT_MIGRATION_ENABLED");
        try(Connection connection=connect())  {
            String version=connection.getMetaData().getDatabaseProductVersion();
            if(!connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL")||Integer.parseInt(version.split("\\.")[0])<8) throw new IllegalStateException("当前DDL要求MySQL8，目标版本不兼容");
            boolean history=tableExists(connection,"aippt_schema_history");
            if(!history&&hasManagedTables(connection))throw new IllegalStateException("已有受管表但无独立历史，禁止自动baseline");
            if(history)try(PreparedStatement query=connection.prepareStatement("SELECT checksum,status FROM aippt_schema_history WHERE version=1"))  {
                try(ResultSet result=query.executeQuery())  {
                    if(result.next())  {
                        if(!digest.equals(result.getString(1))||!"applied".equals(result.getString(2)))throw new IllegalStateException("历史失败或checksum不符，需审核恢复后再执行");
                        System.out.println("V1 已应用且checksum一致，无写入。");
                        return;
                    }
                }
            }
            try(Statement statement=connection.createStatement())  {
                statement.execute("CREATE TABLE IF NOT EXISTS aippt_schema_history(version INT PRIMARY KEY,checksum CHAR(64) NOT NULL,status VARCHAR(16) NOT NULL,installed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)) ENGINE=InnoDB");
            }
            try(PreparedStatement insert=connection.prepareStatement("INSERT INTO aippt_schema_history(version,checksum,status) VALUES(1,?,'applying')"))  {
                insert.setString(1,digest);
                insert.executeUpdate();
            }
            try  {
                String text=new String(script,StandardCharsets.UTF_8).replaceAll("(?m)^\\s*--.*$","");
                for(String sql:text.split(";"))if(!sql.isBlank())try(Statement statement=connection.createStatement())  {
                    statement.execute(sql.trim());
                }
                setStatus(connection,"applied");
                System.out.println("V1 已应用，独立历史已记录。");
            }
            catch(Exception failure)  {
                setStatus(connection,"failed");
                throw new IllegalStateException("DDL部分执行失败；MySQL DDL不能整体回滚，已标记failed，请按备份和脚本审核恢复。",failure);
            }
        }
    }
    static void setStatus(Connection connection,String state)throws SQLException  {
        try(PreparedStatement update=connection.prepareStatement("UPDATE aippt_schema_history SET status=? WHERE version=1"))  {
            update.setString(1,state);
            update.executeUpdate();
        }
    }
    public static boolean tableExists(Connection connection,String table)throws SQLException  {
        try(ResultSet tables=connection.getMetaData().getTables(connection.getCatalog(),null,table,new String[]  {
            "TABLE"
        }
        ))  {
            return tables.next();
        }
    }
    public static boolean hasManagedTables(Connection connection)throws SQLException  {
        try(ResultSet tables=connection.getMetaData().getTables(connection.getCatalog(),null,"aippt_%",new String[]  {
            "TABLE"
        }
        ))  {
            while(tables.next()) if(!"aippt_schema_history".equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
            return false;
        }
    }
    public static void requireGate(String name)  {
        if(!"true".equalsIgnoreCase(System.getenv(name)))throw new IllegalStateException(name+" 必须显式为true；本入口不继承应用开关");
    }
    public static Connection connect()throws SQLException  {
        String url=required("AIPPT_JDBC_URL"),user=required("AIPPT_JDBC_USER"),password=required("AIPPT_JDBC_PASSWORD"),database=required("AIPPT_TARGET_DATABASE");
        if(!url.startsWith("jdbc:mysql:"))throw new IllegalStateException("只接受已批准的MySQL目标，不创建或克隆数据库");
        Connection connection=DriverManager.getConnection(url,user,password);
        if(!database.equals(connection.getCatalog()))  {
            connection.close();
            throw new IllegalStateException("目标数据库名称与显式批准目标不同");
        }
        return connection;
    }
    private static String required(String name)  {
        String value=System.getenv(name);
        if(value==null||value.isBlank())throw new IllegalStateException("缺少环境变量 "+name);
        return value;
    }
}
