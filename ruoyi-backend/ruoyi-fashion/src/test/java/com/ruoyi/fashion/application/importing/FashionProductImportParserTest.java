package com.ruoyi.fashion.application.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.ruoyi.common.exception.ServiceException;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class FashionProductImportParserTest {
    private final FashionProductImportParser parser = new FashionProductImportParser();

    @Test
    void optionalClothingAttributesValidateWithoutChangingPriceOrStock() {
        var fields=com.ruoyi.fashion.domain.product.ProductAttributeFields.normalize(Map.of("platformSkuId","000123","fabricComposition","棉100%","observedPrice","0.00"));
        assertEquals("000123",fields.get("platformSkuId"));
        assertThrows(ServiceException.class,()->com.ruoyi.fashion.domain.product.ProductAttributeFields.normalize(Map.of("availableQty","8")));
        assertThrows(ServiceException.class,()->com.ruoyi.fashion.domain.product.ProductAttributeFields.normalize(Map.of("observedPrice","-1")));
        assertThrows(ServiceException.class,()->com.ruoyi.fashion.domain.product.ProductAttributeFields.normalize(Map.of("sourceUrl","https://user:password@example.com/a")));
        assertThrows(ServiceException.class,()->com.ruoyi.fashion.domain.product.ProductAttributeFields.normalize(Map.of("collectedAt","2026-09-26")));
    }

    @Test
    void legacyXlsxCanOmitEveryExtensionColumn() throws Exception {
        byte[] content;
        try(var book=new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("商品");var header=sheet.createRow(0);var row=sheet.createRow(1);
            String[] fields={"sourceCode","skuCode","styleCode","name","categoryCode","colorCode","colorName","sizeCode","sizeSystem","unit","brand","material","season","jdItemId","jdUrl"};
            String[] values={"INTERNAL","000123","STYLE001","商品","TOP","BLACK","黑色","M","LETTER","件","","","四季","",""};
            for(int i=0;i<fields.length;i++){header.createCell(i).setCellValue(parser.defaultMapping().get(fields[i]));row.createCell(i).setCellValue(values[i]);}
            book.write(output);content=output.toByteArray();
        }
        var rows=parser.parse("legacy.xlsx",content,Map.of());
        assertTrue(rows.get(0).errors().isEmpty());assertEquals("000123",rows.get(0).values().get("skuCode"));
    }

    @Test
    void csvPreservesTextLeadingZerosBomQuotesAndNewlines() {
        String csv = "\ufeff来源编码,SKU编码,款号,商品名称,品类编码,颜色编码,颜色名称,尺码,尺码制式,单位,品牌,材质,季节,京东商品ID,京东链接\r\n"
                + "INTERNAL,000123,STYLE001,\"两行\n名称\",TOP,BLACK,黑色,M,LETTER,件,品牌,棉,四季,,\r\n";

        var rows = parser.parse("products.csv", csv.getBytes(StandardCharsets.UTF_8), Map.of());

        assertEquals(1, rows.size());
        assertEquals("000123", rows.get(0).values().get("skuCode"));
        assertEquals("两行\n名称", rows.get(0).values().get("name"));
        assertTrue(rows.get(0).errors().isEmpty());
    }

    @Test
    void csvRejectsDuplicateHeadersAndFormulaPrefixes() {
        String duplicate = "来源编码,来源编码\nA,B\n";
        assertThrows(ServiceException.class,
                () -> parser.parse("products.csv", duplicate.getBytes(StandardCharsets.UTF_8), Map.of()));

        String formula = "来源编码,SKU编码,款号,商品名称,品类编码,颜色编码,颜色名称,尺码,尺码制式,单位,品牌,材质,季节,京东商品ID,京东链接\n"
                + "INTERNAL,000123,STYLE001,=HYPERLINK(\"x\"),TOP,BLACK,黑色,M,LETTER,件,,,四季,,\n";
        assertTrue(parser.parse("products.csv", formula.getBytes(StandardCharsets.UTF_8), Map.of())
                .get(0).errors().stream().anyMatch(error -> error.code().equals("csv_formula_prefix")));
    }

    @Test
    void xlsxPreservesTextCodeAndFlagsFormulaCell() throws Exception {
        byte[] content;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("商品");
            var header = sheet.createRow(0);
            int column = 0;
            for (String name : parser.defaultMapping().values()) {
                header.createCell(column++).setCellValue(name);
            }
            var row = sheet.createRow(1);
            column = 0;
            for (String field : parser.defaultMapping().keySet()) {
                var cell = row.createCell(column++);
                String value = switch (field) {
                    case "sourceCode" -> "INTERNAL";
                    case "skuCode" -> "000123";
                    case "styleCode" -> "STYLE001";
                    case "name" -> "商品";
                    case "categoryCode" -> "TOP";
                    case "colorCode" -> "BLACK";
                    case "colorName" -> "黑色";
                    case "sizeCode" -> "M";
                    case "sizeSystem" -> "LETTER";
                    case "unit" -> "件";
                    case "season" -> "四季";
                    default -> "";
                };
                cell.setCellValue(value);
                if (field.equals("brand")) {
                    cell.setCellFormula("1+1");
                }
            }
            var numericCodeRow = sheet.createRow(2);
            column = 0;
            for (String field : parser.defaultMapping().keySet()) {
                var cell = numericCodeRow.createCell(column++);
                if (field.equals("skuCode")) {
                    cell.setCellValue(123);
                } else {
                    String value = switch (field) {
                        case "sourceCode" -> "INTERNAL";
                        case "styleCode" -> "STYLE002";
                        case "name" -> "数字编码商品";
                        case "categoryCode" -> "TOP";
                        case "colorCode" -> "BLACK";
                        case "colorName" -> "黑色";
                        case "sizeCode" -> "L";
                        case "sizeSystem" -> "LETTER";
                        case "unit" -> "件";
                        case "season" -> "四季";
                        default -> "";
                    };
                    cell.setCellValue(value);
                }
            }
            workbook.write(output);
            content = output.toByteArray();
        }

        var rows = parser.parse("products.xlsx", content, Map.of());
        var row = rows.get(0);
        assertEquals("000123", row.values().get("skuCode"));
        assertTrue(row.errors().stream().anyMatch(error -> error.code().equals("formula_not_allowed")));
        assertTrue(rows.get(1).errors().stream().anyMatch(error -> error.code().equals("code_must_be_text")));
    }

    @Test
    void xlsxRejectsMultipleWorksheets() throws Exception {
        byte[] content;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("商品");
            workbook.createSheet("额外页");
            workbook.write(output);
            content = output.toByteArray();
        }

        assertThrows(ServiceException.class, () -> parser.parse("products.xlsx", content, Map.of()));
    }
}
