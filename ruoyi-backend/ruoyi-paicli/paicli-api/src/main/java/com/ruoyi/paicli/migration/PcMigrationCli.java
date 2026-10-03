package com.ruoyi.paicli.migration;

import org.flywaydb.core.Flyway;

/** 运维显式调用；clean永远关闭，只在独立确认参数下登记既有库，不自动baseline或创建库。 */
public final class PcMigrationCli {
    private PcMigrationCli(){}
    public static void main(String[] args){
        if(args.length!=2||!java.util.Set.of("validate","migrate","info","baseline-existing").contains(args[0])||!args[1].matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("用法：validate|migrate|info|baseline-existing <已批准数据库名>；凭证通过PAICLI_DB_URL/USER/PASSWORD环境传入");
        String url=System.getenv("PAICLI_DB_URL"),user=System.getenv("PAICLI_DB_USER"),password=System.getenv("PAICLI_DB_PASSWORD");
        if(url==null||user==null||password==null||!url.startsWith("jdbc:mysql://"))throw new IllegalStateException("缺少明确的MySQL连接参数");
        try(var c=java.sql.DriverManager.getConnection(url,user,password)){if(!args[1].equals(c.getCatalog()))throw new IllegalStateException("连接数据库与批准目标不符");}catch(java.sql.SQLException e){throw new IllegalStateException("无法核对迁移目标，未执行迁移");}
        Flyway f=Flyway.configure().dataSource(url,user,password).locations("classpath:db/paicli-migration").table("pc_flyway_schema_history").baselineVersion("0").baselineOnMigrate(false).cleanDisabled(true).load();
        // 数据库名只用于JDBC目录相等检查，不插入SQL；保留原库ry-vue的连字符。
        // 既有若依库登记baseline(0)必须单独指定操作并提供对应确认值。
        switch(args[0]){case "validate"->f.validate();case "migrate"->f.migrate();case "info"->System.out.println("PaiCLI migration count: "+f.info().all().length);case "baseline-existing"->{if(!("BASELINE0:"+args[1]).equals(System.getenv("PAICLI_BASELINE_CONFIRM")))throw new IllegalStateException("既有库首次登记须明确BASELINE0数据库批准值");f.baseline();}default->throw new IllegalStateException();}
    }
}
