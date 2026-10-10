package com.ruoyi.paiflow.service;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.fastjson2.JSONObject;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** CUSTOM SQL解析成固定逻辑表上的操作，绝不将用户SQL交给JDBC。 */
public final class DataSqlService {
    public record Plan(int mode,String table,SQLExpr where,List<SQLSelectItem> projection,List<SQLSelectOrderByItem> order,int limit,int offset,LinkedHashMap<String,SQLExpr> assignments,List<List<SQLExpr>> inserts){}
    private DataSqlService(){}
    /** 变量只能替换常量，不能插入新的逻辑、列、语句或函数。保留原模板固定SQL结构。 */
    public static boolean matchesTemplate(String template,String actual){
        var matcher=Pattern.compile("\\{\\{[\\p{L}\\p{N}_]+\\}\\}").matcher(template);String marker="__paiflow_slot_";while(template.contains(marker))marker+="x";var marked=new StringBuilder();int prior=0,index=0;boolean quoted=false;
        while(matcher.find()){String segment=template.substring(prior,matcher.start());marked.append(segment);for(int i=0;i<segment.length();i++)if(segment.charAt(i)=='\''){if(quoted&&i+1<segment.length()&&segment.charAt(i+1)=='\'')i++;else quoted=!quoted;}String token=marker+(index++)+"__";marked.append(quoted?token:"'"+token+"'");prior=matcher.end();}marked.append(template.substring(prior));
        var before=parse(marked.toString());var after=parse(actual);if(before.mode()!=after.mode()||!before.table().equals(after.table())||before.limit()!=after.limit()||before.offset()!=after.offset()||!before.assignments().keySet().equals(after.assignments().keySet())||before.projection().size()!=after.projection().size()||before.order().size()!=after.order().size()||before.inserts().size()!=after.inserts().size()||!same(before.where(),after.where(),marker))return false;
        for(var e:before.assignments().entrySet())if(!same(e.getValue(),after.assignments().get(e.getKey()),marker))return false;
        for(int i=0;i<before.projection().size();i++){var a=before.projection().get(i);var b=after.projection().get(i);if(!Objects.equals(a.getAlias(),b.getAlias())||!same(a.getExpr(),b.getExpr(),marker))return false;}
        for(int i=0;i<before.order().size();i++){var a=before.order().get(i);var b=after.order().get(i);if(a.getType()!=b.getType()||!same(a.getExpr(),b.getExpr(),marker))return false;}
        for(int i=0;i<before.inserts().size();i++){var a=before.inserts().get(i);var b=after.inserts().get(i);if(a.size()!=b.size())return false;for(int j=0;j<a.size();j++)if(!same(a.get(j),b.get(j),marker))return false;}return true;
    }
    private static boolean same(SQLExpr before,SQLExpr after,String marker){
        if(before==null||after==null)return before==after;
        if(before instanceof SQLCharExpr literal && literal.getText().contains(marker)){
            if(!(after instanceof SQLCharExpr||after instanceof SQLIntegerExpr||after instanceof SQLNumberExpr||after instanceof SQLNullExpr||after instanceof SQLBooleanExpr))return false;
            String text=literal.getText();if(text.matches(Pattern.quote(marker)+"[0-9]+__"))return true;if(!(after instanceof SQLCharExpr value))return false;
            var slot=Pattern.compile(Pattern.quote(marker)+"[0-9]+__").matcher(text);StringBuilder regex=new StringBuilder("\\A");int previous=0;while(slot.find()){regex.append(Pattern.quote(text.substring(previous,slot.start()))).append("[\\s\\S]*");previous=slot.end();}return value.getText().matches(regex.append(Pattern.quote(text.substring(previous))).append("\\z").toString());
        }
        if(before.getClass()!=after.getClass())return false;
        if(before instanceof SQLBinaryOpExpr a&&after instanceof SQLBinaryOpExpr b)return a.getOperator()==b.getOperator()&&same(a.getLeft(),b.getLeft(),marker)&&same(a.getRight(),b.getRight(),marker);
        if(before instanceof SQLInListExpr a&&after instanceof SQLInListExpr b){
            if(a.isNot()!=b.isNot()||!same(a.getExpr(),b.getExpr(),marker))return false;
            // 原tuple占位符展开为IN常量列表；不允许展开成列、函数、逻辑表达式或子查询。
            if(a.getTargetList().size()==1 && a.getTargetList().get(0) instanceof SQLCharExpr slot && slot.getText().matches(Pattern.quote(marker)+"[0-9]+__"))
                return !b.getTargetList().isEmpty() && b.getTargetList().size()<=1000 && b.getTargetList().stream().allMatch(DataSqlService::constant);
            if(a.getTargetList().size()!=b.getTargetList().size())return false;for(int i=0;i<a.getTargetList().size();i++)if(!same(a.getTargetList().get(i),b.getTargetList().get(i),marker))return false;return true;
        }
        if(before instanceof SQLBetweenExpr a&&after instanceof SQLBetweenExpr b)return a.isNot()==b.isNot()&&same(a.getTestExpr(),b.getTestExpr(),marker)&&same(a.getBeginExpr(),b.getBeginExpr(),marker)&&same(a.getEndExpr(),b.getEndExpr(),marker);
        if(before instanceof SQLUnaryExpr a&&after instanceof SQLUnaryExpr b)return a.getOperator()==b.getOperator()&&same(a.getExpr(),b.getExpr(),marker);
        return before.toString().equals(after.toString());
    }
    private static boolean constant(SQLExpr value){return value instanceof SQLCharExpr || value instanceof SQLIntegerExpr || value instanceof SQLNumberExpr || value instanceof SQLNullExpr || value instanceof SQLBooleanExpr || value instanceof SQLUnaryExpr unary && (unary.getOperator()==SQLUnaryOperator.Negative || unary.getOperator()==SQLUnaryOperator.Plus) && (unary.getExpr() instanceof SQLIntegerExpr || unary.getExpr() instanceof SQLNumberExpr);}
    public static Plan parse(String sql){
        if(sql==null || sql.isBlank() || sql.length()>65536)throw denied("SQL为空或过长");single(sql);
        final List<SQLStatement> statements;try{statements=SQLUtils.parseStatements(sql,DbType.postgresql);}catch(RuntimeException ex){throw denied("SQL语法无效");}
        if(statements.size()!=1)throw denied("只能执行一条SQL");var stmt=statements.get(0);
        if(stmt instanceof SQLSelectStatement s){
            if(s.getSelect().getWithSubQuery()!=null || !(s.getSelect().getQuery() instanceof SQLSelectQueryBlock q))throw denied("查询必须使用固定单表");
            if(q.getInto()!=null || q.isForUpdate() || q.getGroupBy()!=null || q.getDistionOption()!=0 || q.getQualify()!=null || q.getConnectBy()!=null || q.getStartWith()!=null || q.getWindows()!=null&&!q.getWindows().isEmpty())throw denied("该查询扩展不在逻辑表授权范围");
            int limit=1000,offset=0;if(q.getLimit()!=null){limit=integer(q.getLimit().getRowCount(),10000);if(q.getLimit().getOffset()!=null)offset=integer(q.getLimit().getOffset(),10000);}if(q.getOffset()!=null)offset=integer(q.getOffset(),10000);if(q.getFirst()!=null)limit=integer(q.getFirst(),10000);
            return new Plan(3,table(q.getFrom()),q.getWhere(),q.getSelectList(),q.getOrderBy()==null?List.of():q.getOrderBy().getItems(),limit,offset,new LinkedHashMap<>(),List.of());
        }
        if(stmt instanceof SQLInsertStatement s){
            if(s instanceof com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGInsertStatement pg && (pg.getReturning()!=null || pg.isOnConflictDoNothing() || pg.getOnConflictTarget()!=null&&!pg.getOnConflictTarget().isEmpty() || pg.getOnConflictUpdateSetItems()!=null&&!pg.getOnConflictUpdateSetItems().isEmpty() || pg.getOnConflictConstraint()!=null))throw denied("INSERT扩展必须明确迁移，不能忽略冲突或返回语义");
            if(s.getQuery()!=null || s.getWith()!=null || s.getColumns().isEmpty() || s.getValuesList().isEmpty() || s.getValuesList().size()>1000)throw denied("INSERT须为显式列和不超过1000个VALUES");
            var values=new LinkedHashMap<String,SQLExpr>();for(var c:s.getColumns()){String name=column(c);if(values.containsKey(name))throw denied("INSERT列重复");values.put(name,null);}var rows=new ArrayList<List<SQLExpr>>();for(var row:s.getValuesList()){if(row.getValues().size()!=values.size())throw denied("INSERT列和值数量不同");rows.add(row.getValues());}
            return new Plan(1,table(s.getTableSource()),null,List.of(),List.of(),1000,0,values,rows);
        }
        if(stmt instanceof SQLUpdateStatement s){var values=new LinkedHashMap<String,SQLExpr>();for(var item:s.getItems()){var name=column(item.getColumn());if(values.put(name,item.getValue())!=null)throw denied("UPDATE列重复");}if(values.isEmpty() || s.getFrom()!=null || s.getWith()!=null || s.getLimit()!=null || s.getOrderBy()!=null || !s.getReturning().isEmpty())throw denied("UPDATE须为固定单表更新");return new Plan(2,table(s.getTableSource()),s.getWhere(),List.of(),List.of(),10000,0,values,List.of());}
        if(stmt instanceof SQLDeleteStatement s){if(s.getUsing()!=null || s.getWith()!=null || s.getFrom()!=null || s instanceof com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGDeleteStatement pg && pg.isReturning())throw denied("DELETE须为固定单表删除");return new Plan(4,table(s.getTableSource()),s.getWhere(),List.of(),List.of(),10000,0,new LinkedHashMap<>(),List.of());}
        throw denied("CUSTOM只允许SELECT/INSERT/UPDATE/DELETE");
    }
    private static String table(SQLTableSource source){if(!(source instanceof SQLExprTableSource t) || !(t.getExpr() instanceof SQLIdentifierExpr))throw denied("不可访问物理库、联表或子查询");return column(t.getExpr());}
    public static String column(SQLExpr expr){if(!(expr instanceof SQLIdentifierExpr i))throw denied("须使用本逻辑表的列名");String value=i.getName();if(value.startsWith("\"") && value.endsWith("\""))value=value.substring(1,value.length()-1);return DataResourceService.identifier(value);}
    private static int integer(SQLExpr expr,int max){Object raw=eval(expr,new JSONObject(),Set.of());if(!(raw instanceof Number))throw denied("LIMIT/OFFSET须为整数");try{int n=new BigDecimal(raw.toString()).intValueExact();if(n<0 || n>max)throw denied("LIMIT/OFFSET越界");return n;}catch(ArithmeticException ex){throw denied("LIMIT/OFFSET须为整数");}}
    /** 先遍历所有表达式分支，避免短路或空表掩盖未授权字段。 */
    public static void validate(SQLExpr expr,Set<String> columns){if(expr==null)return;if(expr instanceof SQLIdentifierExpr){if(!columns.contains(column(expr)))throw denied("SQL列未获授权");return;}if(expr instanceof SQLCharExpr || expr instanceof SQLIntegerExpr || expr instanceof SQLNumberExpr || expr instanceof SQLNullExpr || expr instanceof SQLBooleanExpr || expr instanceof SQLAllColumnExpr)return;
        if(expr instanceof SQLBinaryOpExpr b){if(!Set.of("=","<>","!=",">",">=","<","<=","AND","OR","IS","IS NOT","LIKE","NOT LIKE","+","-","*","/","%").contains(b.getOperator().name.toUpperCase(Locale.ROOT)))throw denied("SQL运算未获授权");validate(b.getLeft(),columns);validate(b.getRight(),columns);return;}
        if(expr instanceof SQLInListExpr in){validate(in.getExpr(),columns);if(in.getTargetList().size()>1000)throw denied("IN列表过长");in.getTargetList().forEach(e->validate(e,columns));return;}
        if(expr instanceof SQLBetweenExpr b){validate(b.getTestExpr(),columns);validate(b.getBeginExpr(),columns);validate(b.getEndExpr(),columns);return;}
        if(expr instanceof SQLUnaryExpr u && Set.of("Not","Negative","Plus").contains(u.getOperator().name())){validate(u.getExpr(),columns);return;}
        if(expr instanceof SQLAggregateExpr a && "COUNT".equalsIgnoreCase(a.getMethodName()) && a.getArguments().size()==1 && a.getArguments().get(0) instanceof SQLAllColumnExpr && a.getOption()==null)return;
        throw denied("SQL表达式或函数不在逻辑表授权范围");
    }
    public static Object eval(SQLExpr expr,JSONObject row,Set<String> columns){
        validate(expr,columns);if(expr==null)return true;if(expr instanceof SQLIdentifierExpr)return row.get(column(expr));if(expr instanceof SQLCharExpr c)return c.getText();if(expr instanceof SQLIntegerExpr n)return n.getNumber();if(expr instanceof SQLNumberExpr n)return n.getNumber();if(expr instanceof SQLNullExpr)return null;if(expr instanceof SQLBooleanExpr b)return b.getValue();
        if(expr instanceof SQLUnaryExpr u){Object value=eval(u.getExpr(),row,columns);return switch(u.getOperator().name()){case "Not"->value==null?null:!Boolean.TRUE.equals(value);case "Negative"->value==null?null:new BigDecimal(value.toString()).negate();default->value;};}
        if(expr instanceof SQLInListExpr in){Object value=eval(in.getExpr(),row,columns);if(value==null)return null;boolean unknown=false;for(var target:in.getTargetList()){Object other=eval(target,row,columns);if(other==null)unknown=true;else if(compare(value,other)==0)return !in.isNot();}return unknown?null:in.isNot();}
        if(expr instanceof SQLBetweenExpr b){Object value=eval(b.getTestExpr(),row,columns),lo=eval(b.getBeginExpr(),row,columns),hi=eval(b.getEndExpr(),row,columns);if(value==null||lo==null||hi==null)return null;boolean matched=compare(value,lo)>=0&&compare(value,hi)<=0;return b.isNot()?!matched:matched;}
        if(expr instanceof SQLBinaryOpExpr b){Object left=eval(b.getLeft(),row,columns),right=eval(b.getRight(),row,columns);String op=b.getOperator().name.toUpperCase(Locale.ROOT);
            if("AND".equals(op))return Boolean.FALSE.equals(left)||Boolean.FALSE.equals(right)?false:left==null||right==null?null:Boolean.TRUE.equals(left)&&Boolean.TRUE.equals(right);
            if("OR".equals(op))return Boolean.TRUE.equals(left)||Boolean.TRUE.equals(right)?true:left==null||right==null?null:false;
            if("IS".equals(op) || "IS NOT".equals(op)){boolean same=Objects.equals(left,right);return "IS".equals(op)?same:!same;}
            if(left==null||right==null)return null;return switch(op){case "="->compare(left,right)==0;case "<>","!="->compare(left,right)!=0;case ">"->compare(left,right)>0;case ">="->compare(left,right)>=0;case "<"->compare(left,right)<0;case "<="->compare(left,right)<=0;case "LIKE"->like(left,right);case "NOT LIKE"->!like(left,right);case "+"->number(left).add(number(right));case "-"->number(left).subtract(number(right));case "*"->number(left).multiply(number(right));case "/"->number(left).divide(number(right),java.math.MathContext.DECIMAL128);case "%"->number(left).remainder(number(right));default->throw denied("SQL操作未支持");};}
        throw denied("SQL表达式不能求值");
    }
    private static BigDecimal number(Object v){try{return new BigDecimal(v.toString());}catch(RuntimeException ex){throw denied("SQL数值类型不匹配");}}
    public static int compare(Object a,Object b){if(a instanceof Number && b instanceof Number)return number(a).compareTo(number(b));if(a instanceof Boolean && b instanceof Boolean)return Boolean.compare((Boolean)a,(Boolean)b);return String.valueOf(a).compareTo(String.valueOf(b));}
    public static boolean like(Object left,Object right){String source=String.valueOf(right);StringBuilder pattern=new StringBuilder("(?s)^");boolean escape=false;for(char c:source.toCharArray()){if(escape){pattern.append(Pattern.quote(String.valueOf(c)));escape=false;}else if(c=='\\')escape=true;else if(c=='%')pattern.append(".*");else if(c=='_')pattern.append('.');else pattern.append(Pattern.quote(String.valueOf(c)));}if(escape)pattern.append(Pattern.quote("\\"));return String.valueOf(left).matches(pattern.append('$').toString());}
    private static void single(String sql){boolean quote=false,dquoted=false;for(int i=0;i<sql.length();i++){char c=sql.charAt(i);if(c=='\''&&!dquoted){if(quote&&i+1<sql.length()&&sql.charAt(i+1)=='\''){i++;continue;}quote=!quote;}else if(c=='"'&&!quote)dquoted=!dquoted;else if(!quote&&!dquoted){if(c=='#'||c=='\\'||(i+1<sql.length()&&((c=='-'&&sql.charAt(i+1)=='-')||(c=='/'&&sql.charAt(i+1)=='*'))))throw denied("SQL不能包含注释或转义语法");if(c==';'&&!sql.substring(i+1).isBlank())throw denied("SQL不能包含多语句");}}if(quote||dquoted)throw denied("SQL引号未闭合");}
    private static com.ruoyi.common.exception.ServiceException denied(String message){return DataResourceService.fail(message,400);}
}
