package com.ruoyi.fashion.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import com.alibaba.druid.pool.DruidDataSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.ruoyi.fashion.application.delivery.DeliveryQuoteSnapshot;
import com.ruoyi.fashion.application.delivery.GeneratedDeliveryArtifact;
import com.ruoyi.fashion.application.importing.CatalogImportType;
import com.ruoyi.fashion.application.importing.FashionCatalogValueImportParser;
import com.ruoyi.fashion.application.material.port.FashionObjectStoragePort;
import com.ruoyi.fashion.application.material.port.StoredFashionObject;
import com.ruoyi.fashion.application.product.port.ProductSearchCriteria;
import com.ruoyi.fashion.configuration.persistence.FashionDatabasePreconditions;
import com.ruoyi.fashion.configuration.persistence.FashionFlywayFactory;
import com.ruoyi.fashion.infrastructure.files.FashionDeliveryMedia;
import com.ruoyi.fashion.infrastructure.files.pptx.FashionQuotePptxRenderer;
import com.ruoyi.fashion.infrastructure.persistence.foundation.FashionJsonCodec;
import com.ruoyi.fashion.infrastructure.persistence.product.JdbcFashionProductRepository;
import com.ruoyi.fashion.infrastructure.storage.FashionHashing;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** 只在显式提供隔离数据库时运行的生产候选容量基准，不连接共享或非隔离数据库。 */
@EnabledIfSystemProperty(named = "fashion.capacity.mysql.url", matches = "jdbc:mysql:.*")
class FashionProductionCandidateCapacityTest {
    private static final int ROWS = 100_000;
    private static DruidDataSource pooledDataSource;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepareIsolatedDatabase() throws Exception {
        String url = System.getProperty("fashion.capacity.mysql.url");
        String username = System.getProperty("fashion.capacity.mysql.username", "root");
        String password = System.getProperty("fashion.capacity.mysql.password", "");
        String database = System.getProperty("fashion.capacity.mysql.database", "fashion_capacity");
        pooledDataSource = new DruidDataSource();
        pooledDataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        pooledDataSource.setUrl(url);
        pooledDataSource.setUsername(username);
        pooledDataSource.setPassword(password);
        pooledDataSource.setInitialSize(5);
        pooledDataSource.setMinIdle(10);
        pooledDataSource.setMaxActive(20);
        pooledDataSource.setMaxWait(60_000L);
        pooledDataSource.setConnectTimeout(30_000);
        pooledDataSource.setSocketTimeout(60_000);
        pooledDataSource.init();
        dataSource = pooledDataSource;
        ResourceDatabasePopulator baseline = new ResourceDatabasePopulator();
        baseline.setSqlScriptEncoding("UTF-8");
        baseline.addScript(new FileSystemResource(workspacePath("ruoyi-backend", "sql", "ry_20260417.sql")));
        baseline.execute(dataSource);
        FashionDatabasePreconditions.Inspection inspection = FashionDatabasePreconditions.inspect(dataSource, database);
        assertTrue(inspection.businessSchemaEmpty());
        Flyway flyway = FashionFlywayFactory.create(dataSource);
        flyway.baseline();
        assertEquals(3, flyway.migrate().migrationsExecuted);
        FashionDatabasePreconditions.verifyCurrentSchema(dataSource, database);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void closeDataSource() {
        if (pooledDataSource != null) {
            pooledDataSource.close();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void validatesOneHundredThousandRowsTwentyConcurrentQueriesAndFortyImagePptx() throws Exception {
        long productLoadMillis = timed(this::loadProducts);
        long importDetailLoadMillis = timed(this::loadImportDetails);

        byte[] priceCsv = priceCsv();
        FashionCatalogValueImportParser parser = new FashionCatalogValueImportParser();
        long importValidationStart = System.nanoTime();
        var parsed = parser.parse(CatalogImportType.PRICE, "capacity-price.csv", priceCsv);
        long importValidationMillis = elapsedMillis(importValidationStart);
        assertThat(parsed).hasSize(ROWS);
        assertThat(parsed).allMatch(row -> row.errors().isEmpty());
        assertThat(importValidationMillis).isLessThan(300_000L);
        long statisticsRefreshMillis = timed(() -> jdbc.execute("analyze table fq_product"));

        ObjectMapper mapper = new ObjectMapper();
        JdbcFashionProductRepository products = new JdbcFashionProductRepository(
                new NamedParameterJdbcTemplate(dataSource), new FashionJsonCodec(mapper), mapper);
        ProductSearchCriteria criteria = new ProductSearchCriteria("CAPACITY", "TOP", "active", "SKU-000", 0, 50);
        for (int warmup = 0; warmup < 10; warmup++) {
            products.search(criteria);
            products.count(criteria);
        }
        List<Long> sequential = new ArrayList<>();
        for (int sample = 0; sample < 100; sample++) {
            long start = System.nanoTime();
            assertThat(products.search(criteria)).hasSize(50);
            assertThat(products.count(criteria)).isEqualTo(334L);
            sequential.add(elapsedMillis(start));
        }
        long queryP95Millis = percentile95(sequential);

        warmConnectionPool();
        List<QueryTiming> concurrent = twentyConcurrentQueries(products, criteria);
        long concurrentP95Millis = percentile95(concurrent.stream().map(QueryTiming::totalMillis).toList());
        long concurrentSearchP95Millis = percentile95(concurrent.stream().map(QueryTiming::searchMillis).toList());
        long concurrentCountP95Millis = percentile95(concurrent.stream().map(QueryTiming::countMillis).toList());
        assertThat(concurrent).hasSize(20);

        long pptStart = System.nanoTime();
        GeneratedDeliveryArtifact pptx = capacityPptx();
        long pptMillis = elapsedMillis(pptStart);
        int slideCount;
        long pictureShapeCount;
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(pptx.content()))) {
            slideCount = ppt.getSlides().size();
            pictureShapeCount = ppt.getSlides().stream().flatMap(slide -> slide.getShapes().stream())
                    .filter(XSLFPictureShape.class::isInstance).count();
        }
        assertThat(jdbc.queryForObject("select count(*) from fq_product where source_code='CAPACITY'", Integer.class))
                .isEqualTo(ROWS);
        assertThat(jdbc.queryForObject("select count(*) from fq_import_detail where batch_id=7800000001", Integer.class))
                .isEqualTo(ROWS);
        assertThat(jdbc.queryForObject("select count(*) from fq_product where source_code='CAPACITY' "
                + "and main_image_key is not null", Integer.class)).isEqualTo(40);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables "
                + "where table_schema=database() and left(table_name,3)='fq_'", Integer.class)).isEqualTo(19);

        ObjectNode metrics = mapper.createObjectNode();
        metrics.put("environment", "local-isolated-mysql");
        metrics.put("dataSource", "Druid");
        metrics.put("poolInitialSize", 5);
        metrics.put("poolMinIdle", 10);
        metrics.put("poolMaxActive", 20);
        metrics.put("mysqlVersion", jdbc.queryForObject("select version()", String.class));
        metrics.put("skuRows", ROWS);
        metrics.put("importRows", ROWS);
        metrics.put("priceCsvBytes", priceCsv.length);
        metrics.put("productLoadMillis", productLoadMillis);
        metrics.put("importDetailLoadMillis", importDetailLoadMillis);
        metrics.put("importValidationMillis", importValidationMillis);
        metrics.put("statisticsRefreshMillis", statisticsRefreshMillis);
        metrics.put("querySamples", sequential.size());
        metrics.put("queryP95Millis", queryP95Millis);
        metrics.put("concurrentUsers", concurrent.size());
        metrics.put("concurrentP95Millis", concurrentP95Millis);
        metrics.put("concurrentSearchP95Millis", concurrentSearchP95Millis);
        metrics.put("concurrentCountP95Millis", concurrentCountP95Millis);
        metrics.put("pptSlides", slideCount);
        metrics.put("pptPictureShapes", pictureShapeCount);
        metrics.put("pptBytes", pptx.content().length);
        metrics.put("pptMillis", pptMillis);
        metrics.put("businessTableCount", 19);
        metrics.put("objectReferenceRows", 40);
        Path output = Path.of("target", "fashion-candidate-validation", "capacity-metrics.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(metrics), StandardCharsets.UTF_8);
        System.out.println("FASHION_CAPACITY_METRICS=" + metrics);

        assertThat(queryP95Millis).isLessThanOrEqualTo(3_000L);
        assertThat(concurrentP95Millis).isLessThanOrEqualTo(3_000L);
        assertThat(slideCount).isGreaterThanOrEqualTo(20);
        assertThat(pictureShapeCount).isGreaterThanOrEqualTo(40);
        assertThat(pptMillis).isLessThan(120_000L);
    }

    private static void warmConnectionPool() throws Exception {
        List<Connection> connections = new ArrayList<>();
        try {
            for (int index = 0; index < 20; index++) {
                connections.add(dataSource.getConnection());
            }
        } finally {
            for (Connection connection : connections) {
                connection.close();
            }
        }
    }

    private void loadProducts() throws Exception {
        String sql = """
                insert into fq_product
                    (id,source_code,sku_code,style_code,name,category_code,color_code,color_name,size_code,size_system,
                     unit,tags_json,main_image_key,images_json,status,create_by,create_time,update_by,update_time,row_version)
                values (?,?,?,?,?,?,?,?,?,?,?,json_array(),?,?,'active',1,?,1,?,1)
                """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            Timestamp now = Timestamp.from(Instant.parse("2026-09-13T02:00:00Z"));
            for (int index = 0; index < ROWS; index++) {
                String number = String.format("%06d", index);
                statement.setLong(1, 7_000_000_000L + index);
                statement.setString(2, "CAPACITY");
                statement.setString(3, "SKU-" + number);
                statement.setString(4, "STYLE-" + number);
                statement.setString(5, "容量商品" + number);
                statement.setString(6, index % 3 == 0 ? "TOP" : index % 3 == 1 ? "PANTS" : "SHOES");
                statement.setString(7, "C" + number);
                statement.setString(8, "蓝色");
                statement.setString(9, "S" + number);
                statement.setString(10, "LETTER");
                statement.setString(11, "件");
                String imageKey = index < 40 ? "capacity/source-" + index + ".png" : null;
                statement.setString(12, imageKey);
                statement.setString(13, imageKey == null ? "[]" : capacityImageJson(index, imageKey));
                statement.setTimestamp(14, now);
                statement.setTimestamp(15, now);
                statement.addBatch();
                if ((index + 1) % 1_000 == 0) statement.executeBatch();
            }
            connection.commit();
        }
    }

    private static String capacityImageJson(int index, String imageKey) {
        return """
                [{"imageId":"CAPACITY-%d","objectKey":"%s","sha256":"%s","usage":"main",
                  "sourceType":"upload","jdId":null,"sourceUrl":null,"capturedAt":"2026-09-13T02:00:00Z",
                  "allowInternal":true,"allowAi":true,"allowProposal":true,"allowEcommerce":true,
                  "status":"active","confirmedBy":1,"confirmedAt":"2026-09-13T02:00:00Z",
                  "width":1,"height":1,"originalFilename":"source-%d.png"}]
                """.formatted(index, imageKey, "a".repeat(64), index);
    }

    private void loadImportDetails() throws Exception {
        jdbc.update("""
                insert into fq_import_batch
                    (id,batch_no,template_code,template_version,import_type,operation_type,source_code,
                     mapping_snapshot,scope_json,as_of,request_key,expected_count,actual_count,error_count,status,
                     create_by,create_time,update_by,update_time,row_version)
                values (7800000001,'CAPACITY-IMPORT-100K','fashion-price','1.0','price','import','CAPACITY',
                        json_object(),json_object(),utc_timestamp(3),'capacity-import-100k',100000,100000,0,'validated',
                        1,utc_timestamp(3),1,utc_timestamp(3),1)
                """);
        String sql = """
                insert into fq_import_detail
                    (id,batch_id,detail_no,source_row_no,row_type,business_key,raw_data,normalized_data,status,
                     error_json,change_type,create_by,create_time,update_by,update_time,row_version)
                values (?,7800000001,?,?,'input',?,json_object('row',?),json_object('valid',true),'valid',
                        json_array(),'unchanged',1,?,1,?,1)
                """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            Timestamp now = Timestamp.from(Instant.parse("2026-09-13T02:05:00Z"));
            for (int index = 0; index < ROWS; index++) {
                int row = index + 1;
                statement.setLong(1, 7_100_000_000L + index);
                statement.setInt(2, row);
                statement.setInt(3, row + 1);
                statement.setString(4, "CAPACITY:SKU-" + String.format("%06d", index));
                statement.setInt(5, row);
                statement.setTimestamp(6, now);
                statement.setTimestamp(7, now);
                statement.addBatch();
                if (row % 1_000 == 0) statement.executeBatch();
            }
            connection.commit();
        }
    }

    private static byte[] priceCsv() {
        StringBuilder csv = new StringBuilder(8_000_000);
        csv.append("来源编码,SKU编码,销售单价,币种,含税口径,业务时间\n");
        for (int index = 0; index < ROWS; index++) {
            csv.append("CAPACITY,SKU-").append(String.format("%06d", index))
                    .append(",99.90,CNY,included,2026-09-13T02:00:00Z\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<QueryTiming> twentyConcurrentQueries(
            JdbcFashionProductRepository products, ProductSearchCriteria criteria) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<QueryTiming>> calls = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                calls.add(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    long before = System.nanoTime();
                    long searchStart = System.nanoTime();
                    assertThat(products.search(criteria)).hasSize(50);
                    long searchMillis = elapsedMillis(searchStart);
                    long countStart = System.nanoTime();
                    assertThat(products.count(criteria)).isEqualTo(334L);
                    long countMillis = elapsedMillis(countStart);
                    return new QueryTiming(elapsedMillis(before), searchMillis, countMillis);
                });
            }
            List<Future<QueryTiming>> futures = calls.stream().map(executor::submit).toList();
            start.countDown();
            List<QueryTiming> values = new ArrayList<>();
            for (Future<QueryTiming> future : futures) values.add(future.get(30, TimeUnit.SECONDS));
            return values;
        } finally {
            executor.shutdownNow();
        }
    }

    private static GeneratedDeliveryArtifact capacityPptx() throws Exception {
        byte[] image = com.ruoyi.fashion.application.delivery.DeliveryTestFixtures.png();
        String sha = FashionHashing.sha256(image);
        FashionObjectStoragePort storage = new FashionObjectStoragePort() {
            @Override public StoredFashionObject putIfAbsent(String key, byte[] content, String contentType) {
                return new StoredFashionObject(key, FashionHashing.sha256(content), content.length, contentType);
            }
            @Override public byte[] read(String objectKey) { return image; }
        };
        List<DeliveryQuoteSnapshot.Combo> combos = new ArrayList<>();
        int lineNo = 0;
        for (int combo = 1; combo <= 4; combo++) {
            List<DeliveryQuoteSnapshot.Line> lines = new ArrayList<>();
            for (int item = 1; item <= 10; item++) {
                lineNo++;
                lines.add(new DeliveryQuoteSnapshot.Line(lineNo, "slot-" + item, 8_000_000L + lineNo,
                        "PPT-SKU-" + lineNo, "STYLE-" + lineNo, "四十图容量商品" + lineNo,
                        "category-" + item, "蓝色", "M", "件", 10,
                        new BigDecimal("100.00"), new BigDecimal("100.00"), new BigDecimal("1000.00"),
                        200, Instant.parse("2026-09-13T02:00:00Z"), "images/source-" + lineNo + ".png", sha, null));
            }
            combos.add(new DeliveryQuoteSnapshot.Combo(8_100_000L + combo, "C" + combo, "容量组合" + combo,
                    combo, 100, new BigDecimal("10000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("10000.00"), new DeliveryQuoteSnapshot.ImageRef(
                            "images/adopted-" + combo + ".png", sha, "upload", "pass"), List.copyOf(lines)));
        }
        ObjectMapper mapper = new ObjectMapper();
        DeliveryQuoteSnapshot snapshot = new DeliveryQuoteSnapshot(8_200_001L, "FQ-CAPACITY", 1,
                "20 页 40 图生产候选容量方案", "容量测试客户", 100, "alternatives", "MAIN", "CNY",
                "included", BigDecimal.ZERO, "percent", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("40000.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("40000.00"),
                7, Instant.parse("2026-09-20T02:00:00Z"), "隔离容量测试", "d".repeat(64),
                Instant.parse("2026-09-13T02:00:00Z"), mapper.createObjectNode().put("scene", "容量验证"),
                mapper.createObjectNode().put("layout_version", "1.0"), List.copyOf(combos));
        return new FashionQuotePptxRenderer(new FashionDeliveryMedia(storage)).render(snapshot).get(0);
    }

    private static long timed(ThrowingRunnable work) throws Exception {
        long start = System.nanoTime();
        work.run();
        return elapsedMillis(start);
    }

    private static long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private static long percentile95(List<Long> values) {
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1));
    }

    private record QueryTiming(long totalMillis, long searchMillis, long countMillis) {
    }

    private static Path workspacePath(String... parts) {
        Path current = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && current != null; up++, current = current.getParent()) {
            Path candidate = current;
            for (String part : parts) candidate = candidate.resolve(part);
            if (Files.exists(candidate)) return candidate.normalize();
        }
        throw new IllegalStateException("无法定位 workspace 文件 " + String.join("/", parts));
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }
}
