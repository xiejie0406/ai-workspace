package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.paiflow.domain.DataRecord;
import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** 沿用原字段模板、测试/正式数据环境和类型默认值；POI 不计算上传公式。 */
@Service @ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class DataSpreadsheetService {
    public static final String MIME="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final List<String> HEAD=List.of("字段名*","数据类型*","描述*","默认值","是否必填*");
    private static final List<String> EN_HEAD=List.of("Field Name*","Data Type*","Description*","Default Value","Required*");
    private final DataResourceService data;
    public DataSpreadsheetService(DataResourceService data){this.data=data;}
    private static Workbook open(MultipartFile file){
        if(file==null || file.isEmpty() || file.getSize()>20*1024*1024)throw DataResourceService.fail("Excel文件为空或超过20MiB",400);
        String name=file.getOriginalFilename();if(name==null || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw DataResourceService.fail("只接受XLSX模板",400);
        try{return WorkbookFactory.create(file.getInputStream());}catch(IOException|RuntimeException ex){throw DataResourceService.fail("无法读取XLSX文件",400);}
    }
    private static String cell(Row row,int col){
        if(row==null)return "";Cell c=row.getCell(col,Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);if(c==null)return "";
        if(c.getCellType()==CellType.FORMULA || c.getCellType()==CellType.ERROR)throw DataResourceService.fail("导入文件不能包含公式或错误单元格",400);
        if(c.getCellType()==CellType.NUMERIC && DateUtil.isCellDateFormatted(c))return c.getLocalDateTimeCellValue().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return new DataFormatter(Locale.ROOT).formatCellValue(c);
    }
    private static List<String> headers(Sheet sheet){Row row=sheet.getRow(0);if(row==null || row.getLastCellNum()<1 || row.getLastCellNum()>20)throw DataResourceService.fail("Excel表头无效",400);var values=new ArrayList<String>();for(int i=0;i<row.getLastCellNum();i++)values.add(cell(row,i));if(new HashSet<>(values).size()!=values.size())throw DataResourceService.fail("Excel表头重复",400);return values;}
    public List<JSONObject> importFields(MultipartFile file){
        try(var book=open(file)){
            var sheet=book.getSheetAt(0);var header=headers(sheet);if(!HEAD.equals(header) && !EN_HEAD.equals(header))throw DataResourceService.fail("字段模板表头与原模板不符",400);
            if(sheet.getLastRowNum()>17)throw DataResourceService.fail("字段模板最多17个自定义字段",400);
            var fields=new ArrayList<JSONObject>();var names=new HashSet<String>();
            for(int n=1;n<=sheet.getLastRowNum();n++){var row=sheet.getRow(n);if(row==null)continue;String name=DataResourceService.identifier(cell(row,0));if(DataResourceService.reservedFields().contains(name) || !names.add(name))throw DataResourceService.fail("字段名保留或重复",400);
                String required=cell(row,4);if(!Set.of("是","否","Yes","No","true","false").contains(required))throw DataResourceService.fail("是否必填须为是/否或Yes/No",400);
                var field=JSONObject.of("id",n,"name",name,"type",DataResourceService.text(cell(row,1),16,"数据类型"),"description",DataResourceService.text(cell(row,2),2000,"描述"),"defaultValue",cell(row,3),"isRequired",Set.of("是","Yes","true").contains(required),"isSystem",false,"operateType",1);
                if(!Set.of("String","Integer","Time","Number","Boolean").contains(field.getString("type")))throw DataResourceService.fail("字段类型无效",400);
                if(!cell(row,3).isBlank())DataResourceService.convert(cell(row,3),field);fields.add(field);
            }
            if(fields.isEmpty())throw DataResourceService.fail("字段模板无有效字段",400);return fields;
        }catch(IOException ex){throw DataResourceService.fail("读取字段模板失败",400);}
    }
    @Transactional(rollbackFor=Exception.class)
    public int importRows(DefaultPersonalSpaceService.Selection scope,long tableId,int env,MultipartFile file){
        var table=data.table(scope,tableId,true);DataResourceService.environment(env);var fields=JSON.parseArray(table.getFieldsJson());var expected=new LinkedHashMap<String,JSONObject>();for(Object raw:fields){var f=(JSONObject)raw;if(!DataResourceService.reservedFields().contains(f.getString("name")))expected.put(f.getString("name"),f);}
        try(var book=open(file)){
            var sheet=book.getSheetAt(0);var header=headers(sheet);if(!new HashSet<>(header).equals(expected.keySet()))throw DataResourceService.fail("数据模板表头与表列不符",400);
            if(sheet.getLastRowNum()>DataResourceService.MAX_ROWS)throw DataResourceService.fail("数据模板超过10000行",413);
            var staged=new ArrayList<JSONObject>();for(int n=1;n<=sheet.getLastRowNum();n++){var row=sheet.getRow(n);if(row==null)continue;var value=new JSONObject();for(int i=0;i<header.size();i++){var field=expected.get(header.get(i));var raw=cell(row,i);value.put(header.get(i),raw.isBlank()?DataResourceService.defaultValue(field,true):DataResourceService.convert(raw,field));}staged.add(value);}
            if(staged.isEmpty())throw DataResourceService.fail("数据模板无有效数据",400);
            for(var row:staged)data.insert(table,env,row,scope.ownerUserId());return staged.size();
        }catch(IOException ex){throw DataResourceService.fail("读取数据模板失败",400);}
    }
    public byte[] template(DefaultPersonalSpaceService.Selection scope,long id){var table=data.table(scope,id,false);return write(table,List.of(),false);}
    public byte[] export(DefaultPersonalSpaceService.Selection scope,long id,int env,JSONArray ids){
        var table=data.table(scope,id,false);var rows=data.loadRows(id,DataResourceService.environment(env));
        if(ids!=null && !ids.isEmpty()){if(ids.size()>1000)throw DataResourceService.fail("最多导出1000个指定行",400);var wanted=new HashSet<Long>();for(Object raw:ids)wanted.add(DataResourceService.id(raw));rows=rows.stream().filter(r->wanted.contains(DataResourceService.id(r.get("id")))).toList();}else rows=rows.stream().limit(1000).toList();return write(table,rows,true);
    }
    public static byte[] fieldTemplate(){var fields=new JSONArray();for(int i=0;i<HEAD.size();i++)fields.add(JSONObject.of("name",HEAD.get(i)));var table=new DataRecord();table.setFieldsJson(fields.toJSONString());return write(table,List.of(),true);}
    private static byte[] write(DataRecord table,List<JSONObject> rows,boolean includeSystem){
        try(var book=new XSSFWorkbook();var output=new ByteArrayOutputStream()){
            var columns=new ArrayList<String>();for(Object raw:JSON.parseArray(table.getFieldsJson())){String name=((JSONObject)raw).getString("name");if(includeSystem || !DataResourceService.reservedFields().contains(name))columns.add(name);}
            var sheet=book.createSheet("数据");var header=sheet.createRow(0);for(int col=0;col<columns.size();col++){header.createCell(col).setCellValue(columns.get(col));sheet.setColumnWidth(col,6000);}
            int n=1;for(var value:rows){var row=sheet.createRow(n++);for(int col=0;col<columns.size();col++){Object v=value.get(columns.get(col));if(v!=null)row.createCell(col).setCellValue(String.valueOf(v));}}
            book.write(output);return output.toByteArray();
        }catch(IOException ex){throw DataResourceService.fail("生成Excel失败",500);}
    }
}
