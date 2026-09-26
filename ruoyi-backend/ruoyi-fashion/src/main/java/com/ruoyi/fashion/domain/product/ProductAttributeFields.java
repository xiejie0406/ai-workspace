package com.ruoyi.fashion.domain.product;

import java.util.*;
import java.net.URI;
import java.time.Instant;
import com.ruoyi.common.exception.ServiceException;

/** 可选来源和服装字段的权威白名单；不包含正式价格、库存或存储对象引用。 */
public final class ProductAttributeFields {
    private ProductAttributeFields() {}
    public record Field(String key, String label, String group, int maxLength) {}
    public static final List<Field> DEFINITIONS = List.of(
            new Field("mainSkuCode", "主SKU编号", "主SKU与分类", 128),
            new Field("itemNo", "品号", "主SKU与分类", 128),
            new Field("articleNo", "货号", "主SKU与分类", 128),
            new Field("categoryLeafCode", "三级分类编码", "主SKU与分类", 128),
            new Field("categoryLeafPath", "标准三级分类路径", "主SKU与分类", 255),
            new Field("platform", "平台", "来源", 80),
            new Field("shopName", "店铺", "来源", 120),
            new Field("supplierName", "供应商", "来源", 120),
            new Field("platformProductId", "平台商品ID", "来源", 128),
            new Field("platformSkuId", "平台SKU ID", "来源", 128),
            new Field("sourceUrl", "商品来源链接", "来源", 2048),
            new Field("collectedAt", "采集时间", "来源", 40),
            new Field("categoryPath", "平台品类路径", "来源", 255),
            new Field("listingYear", "上市年份", "基础", 4),
            new Field("barcode", "条码", "基础", 64),
            new Field("gender", "适用性别", "服装", 80),
            new Field("audience", "适用人群", "服装", 120),
            new Field("style", "风格", "服装", 120),
            new Field("scene", "适用场景", "服装", 255),
            new Field("fabricComposition", "面料成分及比例", "服装", 1000),
            new Field("lining", "里料", "服装", 255),
            new Field("thickness", "厚薄", "服装", 80),
            new Field("elasticity", "弹力", "服装", 80),
            new Field("fit", "版型", "服装", 80),
            new Field("collar", "领型", "服装", 80),
            new Field("sleeveType", "袖型", "服装", 80),
            new Field("sleeveLength", "袖长类型", "服装", 80),
            new Field("garmentLength", "衣长类型", "服装", 80),
            new Field("pattern", "图案", "服装", 255),
            new Field("craft", "工艺", "服装", 255),
            new Field("trouserFit", "裤型", "服装", 80),
            new Field("trouserLength", "裤长类型", "服装", 80),
            new Field("waistType", "腰型", "服装", 80),
            new Field("sizeName", "尺码名称", "尺码", 80),
            new Field("sizeMeasurements", "实测尺寸说明", "尺码", 4000),
            new Field("measurementUnit", "测量单位", "尺码", 16),
            new Field("sizeSource", "尺码来源说明", "尺码", 1000),
            new Field("sourceMainImageUrl", "来源主图链接", "图片来源", 2048),
            new Field("sourceColorImageUrl", "来源颜色图链接", "图片来源", 2048),
            new Field("sourceDetailImageUrls", "来源细节图链接", "图片来源", 12000),
            new Field("sourceSizeImageUrl", "来源尺码图链接", "图片来源", 2048),
            new Field("observedPrice", "电商观察价", "观察价格", 20),
            new Field("observedCurrency", "观察价币种", "观察价格", 3),
            new Field("observedAt", "观察价时间", "观察价格", 40));
    public static final Map<String, Field> FIELDS;
    static {
        Map<String, Field> fields = new LinkedHashMap<>();
        DEFINITIONS.forEach(field -> fields.put(field.key(), field));
        FIELDS = Collections.unmodifiableMap(fields);
    }
    public static Map<String, String> normalize(Map<?, ?> raw) {
        if (raw == null) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            Field field = FIELDS.get(key);
            if (field == null) throw new ServiceException("不允许的商品扩展字段：" + key);
            if (value != null && !(value instanceof String)) throw new ServiceException(field.label() + "必须为文本");
            String text = value == null ? null : ((String) value).trim();
            if (text == null || text.isBlank()) return;
            if (text.length() > field.maxLength() || text.indexOf('\0') >= 0) throw new ServiceException(field.label() + "长度或内容无效");
            if (Set.of("mainSkuCode", "itemNo", "articleNo", "categoryLeafCode").contains(field.key())
                    && text.chars().anyMatch(Character::isISOControl)) {
                throw new ServiceException(field.label() + "不能包含控制字符");
            }
            if (field.key().endsWith("Url")) requireHttps(text, field.label());
            if (field.key().equals("sourceDetailImageUrls")) {
                String[] urls = text.split("\\R");
                if (urls.length > 20) throw new ServiceException("细节图最多20条链接");
                for (String url : urls) requireHttps(url.trim(), field.label());
            }
            if (Set.of("collectedAt", "observedAt").contains(field.key())) {
                try { Instant.parse(text); } catch (RuntimeException e) { throw new ServiceException(field.label()+"必须带时区，例如 2026-09-26T02:00:00Z"); }
            }
            if (field.key().equals("observedPrice") && !text.matches("[0-9]{1,12}(\\.[0-9]{1,2})?")) throw new ServiceException("观察价必须为非负金额，最多两位小数");
            if (field.key().equals("observedCurrency") && !text.matches("[A-Z]{3}")) throw new ServiceException("币种必须为三位大写字母");
            if (field.key().equals("listingYear") && !text.matches("[12][0-9]{3}")) throw new ServiceException("上市年份必须为四位年份");
            result.put(field.key(), text);
        });
        return Collections.unmodifiableMap(result);
    }
    private static void requireHttps(String text, String label) {
        try {
            URI uri = URI.create(text);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || text.length()>2048) throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new ServiceException(label+"必须为不含账号信息的 HTTPS 链接"); }
    }
}
