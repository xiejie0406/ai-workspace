package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.DataRecord;
import com.ruoyi.paiflow.domain.SpaceRecord;
import com.ruoyi.paiflow.adapter.legacy.LegacyDataController;
import com.ruoyi.paiflow.mapper.DataMapper;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 原数据行为与输入边界：不连接业务数据库、不写任何真实业务行。 */
class DataMigrationTest {
    private static final Set<String> COLUMNS=Set.of("id","name","score","active");
    @Test void customSqlSupportsReadWriteInsteadOfBlanketReject(){
        var select=DataSqlService.parse("SELECT name, score + 1 AS next FROM students WHERE score >= 5 AND name LIKE '%张%' ORDER BY score DESC LIMIT 3 OFFSET 1;");
        assertEquals(3,select.mode());assertEquals("students",select.table());assertEquals(3,select.limit());assertEquals(1,select.offset());
        assertEquals(true,DataSqlService.eval(select.where(),JSONObject.of("name","张三","score",10),COLUMNS));
        assertEquals(false,DataSqlService.eval(select.where(),JSONObject.of("name","李四","score",10),COLUMNS));
        assertEquals("11",DataSqlService.eval(select.projection().get(1).getExpr(),JSONObject.of("score",10),COLUMNS).toString());
        assertEquals(1,DataSqlService.parse("INSERT INTO students(name,score) VALUES ('张三',5),('李四',8)").mode());
        var update=DataSqlService.parse("UPDATE students SET score=score+1 WHERE id IN (1,2)");assertEquals(2,update.mode());assertEquals(true,DataSqlService.eval(update.where(),JSONObject.of("id",1L),COLUMNS));
        assertEquals(4,DataSqlService.parse("DELETE FROM students WHERE score BETWEEN 1 AND 3").mode());
    }
    @Test void sqlPreservesThreeValuedNullAndLiteralSemicolon(){
        var p=DataSqlService.parse("SELECT * FROM students WHERE name='a;b--c' OR score IS NULL");assertEquals(true,DataSqlService.eval(p.where(),JSONObject.of("name","other","score",null),COLUMNS));
        var unknown=DataSqlService.parse("SELECT * FROM students WHERE score != 1");assertNull(DataSqlService.eval(unknown.where(),JSONObject.of("score",null),COLUMNS));
        assertFalse(DataSqlService.like("abX","ab\\_"));assertTrue(DataSqlService.like("ab_","ab\\_"));
    }
    @Test void customVariablesCannotInjectNewPredicateOrChangeFixedUpdate(){assertTrue(DataSqlService.matchesTemplate("SELECT name FROM students WHERE name='{{name}}'","SELECT name FROM students WHERE name='张三'"));assertFalse(DataSqlService.matchesTemplate("SELECT name FROM students WHERE name='{{name}}'","SELECT name FROM students WHERE name='张三' OR true OR name='李四'"));assertTrue(DataSqlService.matchesTemplate("UPDATE students SET score={{score}} WHERE id=1","UPDATE students SET score=10 WHERE id=1"));assertFalse(DataSqlService.matchesTemplate("UPDATE students SET score={{score}} WHERE id=1","UPDATE students SET score=score+10 WHERE id=1"));assertFalse(DataSqlService.matchesTemplate("UPDATE students SET score={{score}} WHERE id=1","UPDATE students SET score=10 WHERE id=2"));}
    @Test void userLiteralCannotBecomeAnInternalTemplateMarker(){assertFalse(DataSqlService.matchesTemplate("SELECT name FROM students WHERE name='__paiflow_slot_0__'","SELECT name FROM students WHERE name='changed'"));assertFalse(DataSqlService.matchesTemplate("SELECT name FROM students WHERE name='__paiflow_slot_0__' AND score={{score}}","SELECT name FROM students WHERE name='changed' AND score=10"));assertTrue(DataSqlService.matchesTemplate("SELECT name FROM students WHERE name='__paiflow_slot_0__' AND score={{score}}","SELECT name FROM students WHERE name='__paiflow_slot_0__' AND score=10"));}
    @Test void sqlRejectsUncontrolledSourcesBeforeEmptyDataCanHideThem(){
        for(String sql:List.of("SELECT * FROM sys_user; DELETE FROM sys_user","SELECT * FROM mysql.user","SELECT * FROM students JOIN secrets ON students.id=secrets.id","DROP TABLE students","SELECT * FROM students --x","SELECT * FROM students WHERE id IN (SELECT id FROM secrets)","SELECT * FROM students INTO OUTFILE '/tmp/leak'")){
            assertThrows(ServiceException.class,()->{var p=DataSqlService.parse(sql);DataSqlService.validate(p.where(),COLUMNS);});
        }
        var p=DataSqlService.parse("SELECT * FROM students WHERE true OR password='secret'");assertThrows(ServiceException.class,()->DataSqlService.validate(p.where(),COLUMNS));
        var function=DataSqlService.parse("SELECT pg_sleep(10) FROM students");assertThrows(ServiceException.class,()->DataSqlService.validate(function.projection().get(0).getExpr(),COLUMNS));
    }
    @Test void fieldTemplateRoundTripsAndRejectsFormula(){
        var sheets=new DataSpreadsheetService(mock(DataResourceService.class));
        var file=excel("字段名*","数据类型*","描述*","默认值","是否必填*","name","String","姓名","","是");
        var result=sheets.importFields(file);assertEquals(1,result.size());assertEquals("name",result.get(0).getString("name"));assertTrue(result.get(0).getBooleanValue("isRequired"));
        assertThrows(ServiceException.class,()->sheets.importFields(new MockMultipartFile("file","bad.xlsx",DataSpreadsheetService.MIME,new byte[]{1,2,3})));
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){var sheet=book.createSheet();var row=sheet.createRow(0);for(int i=0;i<5;i++)row.createCell(i).setCellValue(List.of("字段名*","数据类型*","描述*","默认值","是否必填*").get(i));var data=sheet.createRow(1);data.createCell(0).setCellFormula("1+1");book.write(out);var formula=new MockMultipartFile("file","formula.xlsx",DataSpreadsheetService.MIME,out.toByteArray());assertThrows(ServiceException.class,()->sheets.importFields(formula));}catch(IOException ex){throw new AssertionError(ex);}
    }
    @Test void importedRowsUseChosenEnvironmentAndOriginalTypeDefaults(){
        var service=mock(DataResourceService.class);var table=new DataRecord();table.setId(7L);table.setFieldsJson("[{\"name\":\"id\",\"type\":\"Integer\",\"isSystem\":true},{\"name\":\"name\",\"type\":\"String\",\"isRequired\":true},{\"name\":\"score\",\"type\":\"Integer\",\"isRequired\":true}]");var selection=new DefaultPersonalSpaceService.Selection(1,2L,false);when(service.table(selection,7,true)).thenReturn(table);
        var file=excel("score","name","","张三");assertEquals(1,new DataSpreadsheetService(service).importRows(selection,7,2,file));verify(service).insert(eq(table),eq(2),argThat(v->Long.valueOf(0).equals(v.get("score"))&&"张三".equals(v.get("name"))),eq(1L));verify(service,never()).insert(eq(table),eq(1),any(),anyLong());
    }
    @Test void createAndEditRowsKeepSystemIdsAndEnvironmentIsolated(){
        var mapper=mock(DataMapper.class);var service=new DataResourceService(mapper,mock(DefaultPersonalSpaceService.class),mock(ResourceAccessService.class));var table=new DataRecord();table.setId(7L);table.setFieldsJson("[{\"name\":\"id\",\"type\":\"Integer\",\"isSystem\":true},{\"name\":\"uid\",\"type\":\"String\",\"isSystem\":true},{\"name\":\"create_time\",\"type\":\"Time\",\"isSystem\":true},{\"name\":\"name\",\"type\":\"String\",\"isRequired\":true}]");
        when(mapper.insertRow(any())).thenAnswer(inv->{Map<String,Object> record=inv.getArgument(0);record.put("id",42L);return 1;});when(mapper.updateRow(eq(7L),eq(2),eq(42L),anyString())).thenReturn(1);
        var row=service.insert(table,2,JSONObject.of("name","张三"),1);assertEquals(42L,row.getLong("id"));assertEquals("ruoyi:1",row.getString("uid"));verify(mapper).updateRow(eq(7L),eq(2),eq(42L),contains("张三"));verify(mapper,never()).updateRow(anyLong(),eq(1),anyLong(),anyString());
        assertThrows(ServiceException.class,()->service.insert(table,2,JSONObject.of("name","李四","id",9),1));assertThrows(ServiceException.class,()->service.insert(table,2,JSONObject.of("name","李四","password","x"),1));
    }
    @Test void reservedCatalogueAndExcelTemplateShareOnePolicyAndEnforceReadScope() throws Exception {
        var mapper=mock(DataMapper.class);var personal=mock(DefaultPersonalSpaceService.class);var access=mock(ResourceAccessService.class);var data=new DataResourceService(mapper,personal,access);var sheets=new DataSpreadsheetService(data);var controller=new LegacyDataController(data,sheets);var selection=new DefaultPersonalSpaceService.Selection(1,2L,false);when(personal.select("2",null,DataResourceService.QUERY)).thenReturn(selection);when(personal.existing(selection,DataResourceService.QUERY)).thenReturn(2L);var space=new SpaceRecord();space.setId(2L);when(access.requireSpace(2,DataResourceService.QUERY,3,false)).thenReturn(space);
        var response=controller.reservedFields("2");assertEquals(0,response.code());assertEquals("id,uid,create_time",response.data().getString("value"));assertEquals(String.join(",",DataResourceService.reservedFields()),response.data().getString("value"));assertThrows(UnsupportedOperationException.class,()->DataResourceService.reservedFields().add("another"));
        var database=new DataRecord();database.setId(5L);database.setSpaceId(2L);database.setDeleted(0);when(mapper.database(5)).thenReturn(database);var table=new DataRecord();table.setId(7L);table.setDbId(5L);table.setDeleted(0);var fields=new JSONArray();for(String name:DataResourceService.reservedFields())fields.add(JSONObject.of("name",name));fields.add(JSONObject.of("name","business_name"));table.setFieldsJson(fields.toJSONString());when(mapper.table(7)).thenReturn(table);
        try(var book=WorkbookFactory.create(new ByteArrayInputStream(sheets.template(selection,7)))){assertEquals(1,book.getSheetAt(0).getRow(0).getLastCellNum());assertEquals("business_name",book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());}
        var denied=new DefaultPersonalSpaceService.Selection(1,3L,false);when(personal.select("3",null,DataResourceService.QUERY)).thenReturn(denied);when(personal.existing(denied,DataResourceService.QUERY)).thenReturn(3L);when(access.requireSpace(3,DataResourceService.QUERY,3,false)).thenThrow(new ServiceException("非当前成员",403));assertThrows(ServiceException.class,()->controller.reservedFields("3"));verify(personal,never()).materialize(any(),anyString());verify(mapper,never()).insertDatabase(any());
    }
    private static MockMultipartFile excel(String... values){try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){var sheet=book.createSheet();int columns=values.length==10?5:2;for(int i=0;i<values.length;i++)sheet.getRow(i/columns);for(int i=0;i<values.length;i++){var row=sheet.getRow(i/columns);if(row==null)row=sheet.createRow(i/columns);row.createCell(i%columns).setCellValue(values[i]);}book.write(out);return new MockMultipartFile("file","input.xlsx",DataSpreadsheetService.MIME,out.toByteArray());}catch(IOException ex){throw new AssertionError(ex);}}
}
