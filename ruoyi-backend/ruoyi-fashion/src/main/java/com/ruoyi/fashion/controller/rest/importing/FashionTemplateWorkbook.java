package com.ruoyi.fashion.controller.rest.importing;

import java.io.*;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.*;

/** 下载空模板，所有编码保持文本，首行冻结且可筛选。 */
public final class FashionTemplateWorkbook {
    private FashionTemplateWorkbook() {}
    public static ResponseEntity<byte[]> response(String filename, Map<String, String> fields) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("导入数据");
            CellStyle text = workbook.createCellStyle();
            text.setDataFormat(workbook.createDataFormat().getFormat("@"));
            Row header = sheet.createRow(0);
            int index=0;
            for (String label : fields.values()) {
                sheet.setDefaultColumnStyle(index, text);
                sheet.setColumnWidth(index, 22 * 256);
                header.createCell(index++, CellType.STRING).setCellValue(label);
            }
            sheet.createFreezePane(0,1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,0,0,index-1));
            workbook.write(out);
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        }
    }
}
