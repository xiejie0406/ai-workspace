package com.ruoyi.fashion.application.importing;

import com.ruoyi.fashion.configuration.FashionModuleEnabled;

import com.ruoyi.fashion.domain.product.ProductAttributeFields;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.domain.shared.FashionFieldWhitelistGuard;
import com.ruoyi.fashion.infrastructure.storage.FashionUploadPolicy;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/** 固定 v1 商品模板解析器；只产出白名单资料字段，不读取价格、库存或公式结果。 */
@FashionModuleEnabled
@Component
public final class FashionProductImportParser {
    public static final String TEMPLATE_CODE = "fashion-product";
    public static final String TEMPLATE_VERSION = "2.0";
    /** 与 P0 容量约束一致；文件字节上限仍由 FashionUploadPolicy 独立约束。 */
    public static final int MAX_ROWS = 100_000;
    private static final Set<String> BASE_FIELDS = Set.of(
            "sourceCode", "skuCode", "styleCode", "name", "categoryCode", "colorCode", "colorName",
            "sizeCode", "sizeSystem", "unit", "brand", "material", "season", "jdItemId", "jdUrl");
    private static final Set<String> REQUIRED = Set.of(
            "sourceCode", "skuCode", "styleCode", "name", "categoryCode", "colorCode", "colorName",
            "sizeCode", "sizeSystem", "unit", "season");
    private static final Set<String> TEXT_ONLY_CODES = Set.of(
            "sourceCode", "skuCode", "styleCode", "categoryCode", "colorCode", "sizeCode");
    private static final Map<String, String> BASE_HEADERS = Map.ofEntries(
            Map.entry("sourceCode", "来源编码"), Map.entry("skuCode", "SKU编码"),
            Map.entry("styleCode", "款号"), Map.entry("name", "商品名称"),
            Map.entry("categoryCode", "品类编码"), Map.entry("colorCode", "颜色编码"),
            Map.entry("colorName", "颜色名称"), Map.entry("sizeCode", "尺码"),
            Map.entry("sizeSystem", "尺码制式"), Map.entry("unit", "单位"),
            Map.entry("brand", "品牌"), Map.entry("material", "材质"),
            Map.entry("season", "季节"), Map.entry("jdItemId", "京东商品ID"),
            Map.entry("jdUrl", "京东链接"));

    public static final Set<String> FIELDS;
    private static final Map<String, String> DEFAULT_HEADERS;
    static {
        Map<String, String> headers = new LinkedHashMap<>();
        List.of("sourceCode","skuCode","styleCode","name","categoryCode","colorCode","colorName","sizeCode","sizeSystem","unit","brand","material","season","jdItemId","jdUrl")
                .forEach(key -> headers.put(key, BASE_HEADERS.get(key)));
        ProductAttributeFields.DEFINITIONS.forEach(field -> headers.put(field.key(), field.label()));
        DEFAULT_HEADERS = Collections.unmodifiableMap(headers);
        FIELDS = DEFAULT_HEADERS.keySet();
    }

    public List<ParsedProductRow> parse(String filename, byte[] content, Map<String, String> requestedMapping) {
        FashionUploadPolicy.validateImport(filename, content);
        Map<String, String> mapping = mapping(requestedMapping);
        return "csv".equals(FashionUploadPolicy.extension(filename))
                ? parseCsv(content, mapping)
                : parseXlsx(content, mapping);
    }

    public Map<String, String> defaultMapping() {
        return DEFAULT_HEADERS;
    }

    public Map<String, String> resolvedMapping(Map<String, String> requested) {
        return mapping(requested);
    }

    private List<ParsedProductRow> parseCsv(byte[] content, Map<String, String> mapping) {
        String text = FashionUploadPolicy.decodeUtf8(content);
        if (text.indexOf('\0') >= 0) {
            throw new ServiceException("CSV 包含 NUL 字节");
        }
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(false).setTrim(false).get();
        try (CSVParser parser = format.parse(new StringReader(text))) {
            requireHeaders(parser.getHeaderNames(), mapping);
            List<ParsedProductRow> result = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (result.size() >= MAX_ROWS) {
                    throw new ServiceException("商品导入最多 " + MAX_ROWS + " 行");
                }
                Map<String, String> values = new LinkedHashMap<>();
                mapping.forEach((field, header) -> values.put(field, normalize(record.isMapped(header) ? record.get(header) : null)));
                if (values.values().stream().allMatch(value -> value == null || value.isBlank())) {
                    continue;
                }
                List<ImportRowError> errors = new ArrayList<>(requiredErrors(values));
                values.forEach((field, value) -> {
                    if (looksLikeSpreadsheetFormula(value)) {
                        errors.add(new ImportRowError(field, "csv_formula_prefix", "[formula-like]",
                                "CSV 单元格不能以公式控制字符开头"));
                    }
                });
                result.add(new ParsedProductRow((int) record.getRecordNumber() + 1,
                        Collections.unmodifiableMap(new LinkedHashMap<>(values)),
                        List.copyOf(errors)));
            }
            requireRows(result);
            return List.copyOf(result);
        } catch (IOException | IllegalArgumentException exception) {
            throw new ServiceException("CSV 结构无效：" + exception.getMessage());
        }
    }

    private List<ParsedProductRow> parseXlsx(byte[] content, Map<String, String> mapping) {
        try {
            List<StreamingXlsxReader.RowValue> rows = StreamingXlsxReader.read(content, MAX_ROWS + 1);
            if (rows.isEmpty()) {
                throw new ServiceException("XLSX 缺少表头");
            }
            StreamingXlsxReader.RowValue headerRow = rows.get(0);
            Map<String, Integer> headers = headers(headerRow);
            requireHeaders(new ArrayList<>(headers.keySet()), mapping);
            List<ParsedProductRow> result = new ArrayList<>();
            for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
                StreamingXlsxReader.RowValue row = rows.get(rowIndex);
                if (result.size() >= MAX_ROWS) {
                    throw new ServiceException("商品导入最多 " + MAX_ROWS + " 行");
                }
                Map<String, String> values = new LinkedHashMap<>();
                List<ImportRowError> errors = new ArrayList<>();
                for (Map.Entry<String, String> entry : mapping.entrySet()) {
                    Integer columnIndex = headers.get(entry.getValue());
                    StreamingXlsxReader.CellValue cell = columnIndex == null ? null : row.cells().get(columnIndex);
                    if (cell != null && cell.formula()) {
                        errors.add(new ImportRowError(entry.getKey(), "formula_not_allowed", "[formula]",
                                "不接受公式单元格，请粘贴为文本值"));
                        values.put(entry.getKey(), null);
                    } else {
                        if (cell != null && (TEXT_ONLY_CODES.contains(entry.getKey()) || Set.of("platformProductId", "platformSkuId", "barcode", "jdItemId").contains(entry.getKey())) && cell.numeric()) {
                            errors.add(new ImportRowError(entry.getKey(), "code_must_be_text", "[numeric]",
                                    "编码字段必须设置为文本，避免前导零丢失"));
                        }
                        values.put(entry.getKey(), normalize(cell == null ? null : cell.value()));
                    }
                }
                if (values.values().stream().allMatch(value -> value == null || value.isBlank()) && errors.isEmpty()) {
                    continue;
                }
                errors.addAll(requiredErrors(values));
                result.add(new ParsedProductRow(row.rowNumber(),
                        Collections.unmodifiableMap(new LinkedHashMap<>(values)), List.copyOf(errors)));
            }
            requireRows(result);
            return List.copyOf(result);
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("XLSX 损坏、加密或结构无效");
        }
    }

    private static Map<String, Integer> headers(StreamingXlsxReader.RowValue row) {
        Map<String, Integer> result = new LinkedHashMap<>();
        int maximumColumn = row.cells().keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        for (int index = 0; index <= maximumColumn; index++) {
            StreamingXlsxReader.CellValue cell = row.cells().get(index);
            if (cell == null || cell.formula()) {
                throw new ServiceException("表头不能为空或使用公式");
            }
            String value = normalize(cell.value());
            if (value == null || result.putIfAbsent(value, index) != null) {
                throw new ServiceException("XLSX 存在空白或重复表头：" + value);
            }
        }
        return result;
    }

    private static Map<String, String> mapping(Map<String, String> requested) {
        if (requested == null || requested.isEmpty()) {
            return DEFAULT_HEADERS;
        }
        try {
            FashionFieldWhitelistGuard.requireOnlyAllowed(requested, FIELDS);
        } catch (IllegalArgumentException exception) {
            throw new ServiceException(exception.getMessage());
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String field : FIELDS) {
            String header = requested.getOrDefault(field, DEFAULT_HEADERS.get(field));
            if (header == null || header.isBlank() || header.length() > 100) {
                throw new ServiceException("字段映射无效：" + field);
            }
            result.put(field, header.trim());
        }
        if (new HashSet<>(result.values()).size() != result.size()) {
            throw new ServiceException("一个文件列不能映射到多个商品字段");
        }
        return Map.copyOf(result);
    }

    private static void requireHeaders(List<String> actual, Map<String, String> mapping) {
        Set<String> duplicates = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (String header : actual) {
            if (!seen.add(header)) {
                duplicates.add(header);
            }
        }
        if (!duplicates.isEmpty()) {
            throw new ServiceException("导入文件存在重复表头：" + duplicates);
        }
        Set<String> missing = new HashSet<>();
        BASE_FIELDS.forEach(field -> missing.add(mapping.get(field)));
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            throw new ServiceException("导入文件缺少映射列：" + missing);
        }
    }

    private static List<ImportRowError> requiredErrors(Map<String, String> values) {
        List<ImportRowError> result = new ArrayList<>();
        for (String field : REQUIRED) {
            if (values.get(field) == null || values.get(field).isBlank()) {
                result.add(new ImportRowError(field, "required", "[empty]", field + " 不能为空"));
            }
        }
        return List.copyOf(result);
    }

    private static void requireRows(List<ParsedProductRow> rows) {
        if (rows.isEmpty()) {
            throw new ServiceException("导入文件没有数据行");
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean looksLikeSpreadsheetFormula(String value) {
        return value != null && !value.isEmpty()
                && (value.charAt(0) == '=' || value.charAt(0) == '+' || value.charAt(0) == '-'
                || value.charAt(0) == '@' || value.charAt(0) == '\t' || value.charAt(0) == '\r');
    }
}
