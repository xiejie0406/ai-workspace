package com.ruoyi.fashion.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.application.importing.CatalogImportView;
import com.ruoyi.fashion.application.delivery.DeliveryArtifact;
import com.ruoyi.fashion.application.delivery.DeliveryFileTask;
import com.ruoyi.fashion.application.delivery.DeliveryQuoteSnapshot;
import com.ruoyi.fashion.application.image.QuoteImageTask;
import com.ruoyi.fashion.application.agent.AgentVersionDraft;
import com.ruoyi.fashion.application.agent.AgentVersionView;
import com.ruoyi.fashion.application.agent.run.RunView;
import com.ruoyi.fashion.application.agent.run.RunWorkItem;
import com.ruoyi.fashion.application.importing.FashionCatalogValueImportParser;
import com.ruoyi.fashion.application.importing.FashionCatalogValueImportService;
import com.ruoyi.fashion.application.importing.FashionProductImportParser;
import com.ruoyi.fashion.application.importing.FashionProductImportService;
import com.ruoyi.fashion.application.importing.ProductImportView;
import com.ruoyi.fashion.application.material.FashionProductMaterialService;
import com.ruoyi.fashion.application.material.ImageMapping;
import com.ruoyi.fashion.application.material.MaterialFile;
import com.ruoyi.fashion.application.material.MaterialImportView;
import com.ruoyi.fashion.application.material.port.FashionObjectStoragePort;
import com.ruoyi.fashion.application.material.port.StoredFashionObject;
import com.ruoyi.fashion.application.product.FashionProductService;
import com.ruoyi.fashion.application.product.ProductCreate;
import com.ruoyi.fashion.application.product.ProductPatch;
import com.ruoyi.fashion.application.product.ProductView;
import com.ruoyi.fashion.application.security.FashionDictionaryGuard;
import com.ruoyi.fashion.application.selection.SelectionComboWrite;
import com.ruoyi.fashion.application.selection.SelectionDetailWrite;
import com.ruoyi.fashion.application.quote.FashionQuoteDraftService;
import com.ruoyi.fashion.application.quote.pricing.FashionQuotePricingService;
import com.ruoyi.fashion.application.quote.pricing.QuoteConfirmCommand;
import com.ruoyi.fashion.application.quote.pricing.QuotePricingCalculator;
import com.ruoyi.fashion.application.quote.pricing.QuotePricingWorkspace;
import com.ruoyi.fashion.configuration.persistence.FashionDatabasePreconditions;
import com.ruoyi.fashion.configuration.persistence.FashionFlywayFactory;
import com.ruoyi.fashion.domain.product.FashionProduct;
import com.ruoyi.fashion.domain.product.FashionProductStatus;
import com.ruoyi.fashion.domain.customer.FashionCustomer;
import com.ruoyi.fashion.domain.quote.FashionQuote;
import com.ruoyi.fashion.domain.shared.FashionIdGenerator;
import com.ruoyi.fashion.domain.shared.FashionTimeSource;
import com.ruoyi.fashion.infrastructure.persistence.FashionCatalogWriteLock;
import com.ruoyi.fashion.infrastructure.persistence.FashionCatalogWriteLockTimeoutException;
import com.ruoyi.fashion.infrastructure.persistence.agent.JdbcFashionAgentRepository;
import com.ruoyi.fashion.infrastructure.persistence.agent.JdbcFashionRunRepository;
import com.ruoyi.fashion.infrastructure.persistence.customer.JdbcFashionCustomerRepository;
import com.ruoyi.fashion.infrastructure.persistence.foundation.FashionJsonCodec;
import com.ruoyi.fashion.infrastructure.persistence.importing.JdbcFashionImportRepository;
import com.ruoyi.fashion.infrastructure.persistence.product.JdbcFashionProductRepository;
import com.ruoyi.fashion.infrastructure.persistence.quote.JdbcFashionQuoteRepository;
import com.ruoyi.fashion.infrastructure.persistence.quote.JdbcFashionSelectionRepository;
import com.ruoyi.fashion.infrastructure.persistence.quote.JdbcFashionQuotePricingRepository;
import com.ruoyi.fashion.infrastructure.persistence.image.JdbcFashionQuoteImageRepository;
import com.ruoyi.fashion.infrastructure.persistence.delivery.JdbcFashionDeliveryRepository;
import com.ruoyi.fashion.infrastructure.persistence.operations.JdbcFashionOperationsRepository;
import com.ruoyi.fashion.infrastructure.persistence.stock.JdbcFashionStockRepository;
import com.ruoyi.fashion.infrastructure.storage.FashionHashing;
import com.ruoyi.fashion.infrastructure.crypto.AesGcmFashionCustomerContactCipher;
import javax.imageio.ImageIO;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.ruoyi.system.service.ISysConfigService;

/** 由调用方提供隔离 MySQL URL 时执行；默认不连接任何本机或共享数据库。 */
@EnabledIfSystemProperty(named = "fashion.test.mysql.url", matches = "jdbc:mysql:.*")
class FashionProvidedMySqlMigrationTest {
    @Test
    void poolCatalogUsesRealJsonClassificationDeduplicatesMastersAndWritesDualPricesWithCas() {
        var mapper = new ObjectMapper();
        var named = new NamedParameterJdbcTemplate(jdbc);
        var products = new JdbcFashionProductRepository(named, new FashionJsonCodec(mapper), mapper);
        var config = mock(ISysConfigService.class);
        when(config.selectConfigByKey("fashion.stock.freshnessHours")).thenReturn("24");
        var catalog = new com.ruoyi.fashion.infrastructure.persistence.pool.FashionPoolCatalog(
                named, products, new FashionCatalogWriteLock(dataSource), config,
                mock(com.ruoyi.fashion.application.stock.FashionWarehouseService.class));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        jdbc.update("""
                insert into fq_import_batch(id,batch_no,import_type,source_code,warehouse_code,
                    mapping_snapshot,scope_json,as_of,request_key,status,create_by,create_time,update_by,update_time)
                values(998300,'POOL-CATALOG-STOCK','stock','POOL-CATALOG','POOL-CATALOG-WH',
                    json_object(),json_object(),?,'pool-catalog-stock-request','success',1,?,1,?)
                """, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        // 两款四个真实 SKU：同款跨颜色尺码聚合；第二款其中一个 SKU 没有库存记录。
        for (int index = 0; index < 4; index++) {
            boolean firstMaster = index < 2;
            String suffix = firstMaster ? "A" : "B";
            String color = index % 2 == 0 ? "BLUE" : "RED";
            String size = index % 2 == 0 ? "S" : "M";
            var attributes = mapper.createObjectNode().put("mainSkuCode", "POOL-CATALOG-MAIN-" + suffix)
                    .put("categoryLeafCode", "POOL-CATALOG-LEAF-" + suffix)
                    .put("categoryLeafPath", "测试服装/测试上装/测试" + suffix)
                    .put("itemNo", "000CAT" + suffix).put("articleNo", "000ART" + suffix).put("gender", "unisex");
            jdbc.update("""
                    insert into fq_product(id,source_code,sku_code,style_code,name,category_code,color_code,
                        color_name,size_code,size_system,unit,brand,season,attributes_json,group_price,vip_price,
                        status,create_by,create_time,update_by,update_time)
                    values(?,'POOL-CATALOG',?,?,?,?,?, ?,?,'LETTER','件','POOL-CATALOG-BRAND','秋季',?,?,?,
                        'active',1,?,1,?)
                    """, 998301L + index, "POOL-CATALOG-SKU-" + index, "POOL-CATALOG-STYLE-" + suffix,
                    "选品测试款" + suffix, "TOP", color, color, size, mapper.writeValueAsString(attributes),
                    firstMaster ? new BigDecimal("100.00") : new BigDecimal("200.00"),
                    firstMaster ? new BigDecimal("90.00") : new BigDecimal("180.00"),
                    Timestamp.from(now), Timestamp.from(now));
            if (index != 3) jdbc.update("""
                    insert into fq_stock(id,product_id,warehouse_code,available_qty,as_of,confirmation_type,
                        last_import_batch_id,create_by,create_time,update_by,update_time)
                    values(?,?,'POOL-CATALOG-WH',?,?,'import',998300,1,?,1,?)
                    """, 998311L + index, 998301L + index, index == 1 ? 7 : 5,
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        }
        ObjectNode filter = mapper.createObjectNode().put("label", "测试分类并集")
                .put("warehouseCode", "POOL-CATALOG-WH").put("stockStatus", "all");
        filter.putArray("categoryLeafCodes").add("POOL-CATALOG-LEAF-A").add("POOL-CATALOG-LEAF-B");
        var all = catalog.find(mapper.treeToValue(filter, com.ruoyi.fashion.application.pool.PoolModels.GroupFilter.class));
        assertEquals(2, all.size());
        assertEquals(2, all.get(0).variants().size());
        assertEquals("000CATA", all.get(0).itemNo());
        assertEquals(12L, all.get(0).availableQty());
        assertTrue(!all.get(0).stockUnknown());
        assertNull(all.get(1).availableQty());
        assertTrue(all.get(1).stockUnknown());
        assertEquals(new BigDecimal("100.00"), all.get(0).groupPriceMin());
        assertEquals(new BigDecimal("90.00"), all.get(0).vipPriceMin());

        filter.put("stockStatus", "positive");
        assertEquals(List.of("POOL-CATALOG-MAIN-A"), catalog.find(mapper.treeToValue(filter,
                com.ruoyi.fashion.application.pool.PoolModels.GroupFilter.class)).stream().map(x -> x.mainSkuCode()).toList());
        filter.put("stockStatus", "unknown");
        assertEquals(List.of("POOL-CATALOG-MAIN-B"), catalog.find(mapper.treeToValue(filter,
                com.ruoyi.fashion.application.pool.PoolModels.GroupFilter.class)).stream().map(x -> x.mainSkuCode()).toList());
        filter.put("stockStatus", "all").put("groupPriceMin", new BigDecimal("150.00"));
        assertEquals(List.of("POOL-CATALOG-MAIN-B"), catalog.find(mapper.treeToValue(filter,
                com.ruoyi.fashion.application.pool.PoolModels.GroupFilter.class)).stream().map(x -> x.mainSkuCode()).toList());

        // 双价单独写回，不复制销售价，不改库存；旧版本写入不能覆盖新价格。
        var saved = transaction.execute(status -> catalog.price("998301", new BigDecimal("99.00"), null, 1, 88003));
        assertEquals(2L, saved.get("rowVersion"));
        assertEquals(new BigDecimal("99.00"), saved.get("groupPrice"));
        assertNull(saved.get("vipPrice"));
        assertThrows(ServiceException.class, () -> transaction.execute(status ->
                catalog.price("998301", new BigDecimal("1.00"), BigDecimal.ONE, 1, 88003)));
        assertEquals(new BigDecimal("99.00"), catalog.price("998301").get("groupPrice"));
        assertNull(products.findById(998301).orElseThrow().salePrice());
        assertEquals(5, jdbc.queryForObject("select available_qty from fq_stock where id=998311", Integer.class));
        assertEquals(1L, jdbc.queryForObject("select row_version from fq_stock where id=998311", Long.class));
    }
    @Test
    void poolSnapshotAndSelectionSurviveRepositoryReloadAndRejectStaleOrForeignWrites() {
        var named = new NamedParameterJdbcTemplate(jdbc);
        var mapper = new ObjectMapper();
        var repository = new com.ruoyi.fashion.infrastructure.persistence.pool.JdbcFashionPoolRepository(named, mapper);
        var catalog = mock(com.ruoyi.fashion.infrastructure.persistence.pool.FashionPoolCatalog.class);
        var service = new com.ruoyi.fashion.application.pool.FashionPoolService(repository, catalog);
        var request = new com.ruoyi.fashion.application.pool.PoolModels.Create("隔离库持久化测试", List.of(), "group", null, null);
        var main = new com.ruoyi.fashion.application.pool.PoolModels.MainSku(
                "MANUAL:MAIN-0001", "MAIN-0001", "主SKU测试", "MANUAL", "TEST-LEAF", "测试品牌",
                "000123", "000456", "999999", null, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ONE, BigDecimal.ONE, 0L, false, "CNY", "included", List.of());
        String poolId = java.util.UUID.randomUUID().toString();
        var pool = new com.ruoyi.fashion.application.pool.PoolModels.Pool(poolId, request.title(), request,
                List.of(new com.ruoyi.fashion.application.pool.PoolModels.Group("上装", List.of(main))),
                1, 1, Instant.now().toString());
        repository.insert(pool, 88001L);
        assertEquals("MAIN-0001", repository.get(poolId, 88001L).groups().get(0).items().get(0).mainSkuCode());
        String comboId = com.ruoyi.fashion.application.pool.PoolCombinations.enumerate(pool).get(0).id();
        var draft = new com.ruoyi.fashion.application.pool.PoolModels.Selection(poolId, 0, false,
                List.of(comboId), List.of(), Map.of(comboId, "秋季团建A"), List.of(comboId), Map.of());
        assertEquals(1L, service.save(poolId, 88001L, draft).rowVersion());
        var reloaded = new com.ruoyi.fashion.infrastructure.persistence.pool.JdbcFashionPoolRepository(named, mapper);
        assertEquals("秋季团建A", reloaded.selection(poolId, 88001L).names().get(comboId));
        assertThrows(ServiceException.class, () -> service.save(poolId, 88001L, draft));
        assertThrows(ServiceException.class, () -> service.get(poolId, 88002L));
        assertThrows(ServiceException.class, () -> service.requireExport(poolId, 88001L, 0));
        assertEquals(1, service.requireExport(poolId, 88001L, 1).combinations().size());
        assertEquals(2, jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='fq_product' and column_name in ('group_price','vip_price') and data_type='decimal' and numeric_precision=16 and numeric_scale=2", Integer.class));
    }
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static Flyway flyway;

    @BeforeAll
    static void migrateIsolatedDatabase() {
        String url = System.getProperty("fashion.test.mysql.url");
        String username = System.getProperty("fashion.test.mysql.username", "root");
        String password = System.getProperty("fashion.test.mysql.password", "");
        String database = System.getProperty("fashion.test.mysql.database", "fashion_test");
        dataSource = new DriverManagerDataSource(url, username, password);

        ResourceDatabasePopulator baseline = populator("ry_20260417.sql");
        baseline.execute(dataSource);
        FashionDatabasePreconditions.Inspection inspection =
                FashionDatabasePreconditions.inspect(dataSource, database);
        assertTrue(inspection.businessSchemaEmpty());
        flyway = FashionFlywayFactory.create(dataSource);
        flyway.baseline();
        assertEquals(3, flyway.migrate().migrationsExecuted);
        flyway.validate();
        FashionDatabasePreconditions.verifyCurrentSchema(dataSource, database);
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void schemaAndPlatformScriptsAreExactAndRepeatable() {
        assertEquals(19, jdbc.queryForObject("""
                select count(*) from information_schema.tables
                 where table_schema = database() and left(table_name, 3) = 'fq_'
                """, Integer.class));
        assertEquals(18, jdbc.queryForObject("""
                select count(*) from information_schema.columns
                 where table_schema = database() and left(table_name, 3) = 'fq_'
                   and column_name = 'row_version' and column_default = '1'
                """, Integer.class));
        assertEquals(40, jdbc.queryForObject("""
                select count(*) from information_schema.columns
                 where table_schema = database() and left(table_name, 3) = 'fq_'
                   and data_type = 'json'
                """, Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from information_schema.columns
                 where table_schema = database() and left(table_name, 3) = 'fq_'
                   and data_type = 'datetime' and datetime_precision <> 3
                """, Integer.class));
        assertTrue(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                 where table_schema = database() and left(table_name, 3) = 'fq_'
                   and data_type = 'decimal' and numeric_precision = 16
                """, Integer.class) >= 15);
        assertTrue(jdbc.queryForObject("""
                select count(distinct table_name, index_name) from information_schema.statistics
                 where table_schema = database() and left(table_name, 3) = 'fq_' and non_unique = 0
                """, Integer.class) >= 40);
        assertTrue(jdbc.queryForObject("""
                select count(*) from information_schema.table_constraints
                 where constraint_schema = database() and constraint_type = 'CHECK'
                   and left(table_name, 3) = 'fq_'
                """, Integer.class) >= 100);
        assertTrue(jdbc.queryForObject("""
                select count(*) from information_schema.referential_constraints
                 where constraint_schema = database()
                """, Integer.class) >= 30);

        for (String name : List.of(
                "ruoyi-fashion-permissions.sql", "ruoyi-fashion-dictionaries.sql", "ruoyi-fashion-config.sql")) {
            ResourceDatabasePopulator script = populator(name);
            script.execute(dataSource);
            script.execute(dataSource);
        }
        assertEquals(6, jdbc.queryForObject(
                "select count(*) from sys_dict_type where dict_type like 'fashion_%'", Integer.class));
        assertEquals(15, jdbc.queryForObject(
                "select count(*) from sys_config where config_key like 'fashion.%'", Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from sys_role_menu rm
                 join sys_menu m on m.menu_id = rm.menu_id
                where m.perms like 'fashion:%'
                """, Integer.class));
    }

    @Test
    void catalogLockSerializesConnectionsAndRollbackLeavesNoPartialBusinessWrite() throws Exception {
        FashionCatalogWriteLock lock = new FashionCatalogWriteLock(dataSource);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status ->
                lock.executeLocked(2, () -> {
                    acquired.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test lock holder timeout");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return null;
                })));
        assertTrue(acquired.await(5, TimeUnit.SECONDS));
        assertThrows(FashionCatalogWriteLockTimeoutException.class,
                () -> transaction.execute(status -> lock.executeLocked(0, () -> null)));
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThrows(IllegalStateException.class, () -> transaction.execute(status ->
                lock.executeLocked(2, () -> {
                    jdbc.update("""
                            insert into fq_customer
                                (id, code, name, customer_type, salesperson_id, collaborator_ids, status,
                                 create_by, create_time, update_by, update_time, row_version)
                            values (9001, 'ROLLBACK', '回滚测试', 'wholesale', 1, json_array(), 'active',
                                    1, utc_timestamp(3), 1, utc_timestamp(3), 1)
                            """);
                    throw new IllegalStateException("force rollback");
                })));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from fq_customer where id = 9001", Integer.class));
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> transaction.execute(status -> lock.executeLocked(2, () -> null)));
    }

    @Test
    void flywayIsRepeatableAndInvalidRowsAreRejected() {
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertThrows(Exception.class, () -> jdbc.update("""
                insert into fq_customer
                    (id, code, name, customer_type, salesperson_id, collaborator_ids, status,
                     create_by, create_time, update_by, update_time, row_version)
                values (9002, 'INVALID', '无效版本', 'wholesale', 1, json_array(), 'active',
                        1, utc_timestamp(3), 1, utc_timestamp(3), 0)
                """));
    }

    @Test
    void quoteImageRowPersistsLeasePartialReviewAdoptionAndStaleHistory() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);
        JdbcFashionQuoteImageRepository images = new JdbcFashionQuoteImageRepository(named, objectMapper);
        Instant now = Instant.parse("2026-09-13T01:00:00Z");
        String objectKey = "materials/imp07/original.png";
        String imageHash = "b".repeat(64);
        String imageJson = objectMapper.writeValueAsString(List.of(Map.ofEntries(
                Map.entry("imageId", "imp07-main"), Map.entry("objectKey", objectKey),
                Map.entry("sha256", imageHash), Map.entry("usage", "main"),
                Map.entry("sourceType", "manual"), Map.entry("capturedAt", "2026-09-13T00:00:00Z"),
                Map.entry("allowInternal", true), Map.entry("allowAi", true),
                Map.entry("allowProposal", true), Map.entry("allowEcommerce", true),
                Map.entry("status", "active"), Map.entry("confirmedBy", 1),
                Map.entry("confirmedAt", "2026-09-13T00:00:00Z"), Map.entry("width", 640),
                Map.entry("height", 800), Map.entry("originalFilename", "original.png"))));
        jdbc.update("""
                insert into fq_customer
                  (id,code,name,customer_type,salesperson_id,collaborator_ids,status,create_by,create_time,update_by,update_time)
                values (980001,'IMP07-C','图片客户','wholesale',1,json_array(),'active',1,?,1,?)
                """, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into fq_product
                  (id,source_code,sku_code,style_code,name,category_code,color_code,color_name,size_code,size_system,
                   unit,tags_json,main_image_key,images_json,visual_version,attributes_confirmed,status,
                   create_by,create_time,update_by,update_time)
                values (980002,'IMP07','SKU-1','STYLE-1','图片商品','TOP','WHITE','白色','M','LETTER',
                        '件',json_array(),?,?,2,1,'active',1,?,1,?)
                """, objectKey, imageJson, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into fq_quote
                  (id,quote_no,version_no,customer_id,customer_name,title,salesperson_id,requirement_json,
                   requirement_confirmed,requested_qty,budget_basis,quote_mode,progressive,combo_template_json,
                   warehouse_code,presentation_json,status,create_by,create_time,update_by,update_time,row_version)
                values (980003,'FQ-IMP07',1,980001,'图片客户','图片方案',1,json_object('schema_version','1.0'),
                        1,100,'total','alternatives',1,json_object('schema_version','1.0'),'MAIN',
                        json_object('schema_version','1.0'),'draft',1,?,1,?,3)
                """, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into fq_quote_combo
                  (id,quote_id,combo_no,name,category_count,set_qty,selected,sort_no,lock_json,visual_hash,
                   create_by,create_time,update_by,update_time,row_version)
                values (980004,980003,'C-IMP07','图片组合',1,100,1,0,json_array(),?,1,?,1,?,4)
                """, "a".repeat(64), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into fq_quote_detail
                  (id,combo_id,line_no,slot_code,product_id,source_code,sku_code,style_code,product_name,
                   category_code,color_code,color_name,size_code,size_system,unit,qty,image_key,image_hash,
                   image_version,create_by,create_time,update_by,update_time)
                values (980005,980004,1,'SLOT-1',980002,'IMP07','SKU-1','STYLE-1','图片商品','TOP','WHITE',
                        '白色','M','LETTER','件',100,?,?,2,1,?,1,?)
                """, objectKey, imageHash, Timestamp.from(now), Timestamp.from(now));

        ObjectNode input = objectMapper.createObjectNode();
        input.put("schema_version", "1.0"); input.put("combo_id", "980004");
        input.put("combo_visual_hash", "a".repeat(64)); input.set("slots", images.currentInputs(980004));
        String inputHash = FashionHashing.sha256(objectMapper.writeValueAsBytes(input));
        QuoteImageTask task = new QuoteImageTask(980006, 980003, 980004, null, null, "model", "sample",
                inputHash, input, objectMapper.createObjectNode(), 2, objectMapper.createArrayNode(), null, null,
                "image:imp07:sample", "queued", false, 0, now, null, null, null, null, "not_applicable",
                objectMapper.createArrayNode(), null, null, now, false, null, 1);
        images.insert(task, 1, now);
        QuoteImageTask claimed = images.claimNext("worker", now, now.plusSeconds(30)).orElseThrow();
        assertEquals(2, claimed.rowVersion());
        assertEquals("queued", claimed.status(), "领取后仍返回来源状态，便于区分首次提交与 UNKNOWN 回查");
        ArrayNode results = objectMapper.createArrayNode();
        ObjectNode result = results.addObject(); result.put("no", 1); result.put("status", "success");
        result.put("object_key", "quote-images/980006/1.png"); result.put("sha256", "c".repeat(64));
        result.put("width", 768); result.put("height", 1024); result.putNull("error");
        result.put("allow_proposal", true); result.put("allow_ecommerce", false); result.putArray("reviews");
        assertTrue(images.updateExecution(980006, claimed.rowVersion(), "partial", results, "fake", "job-1", 0,
                null, new BigDecimal("1.250000"), "settled", objectMapper.createArrayNode(), null, now, now));
        QuoteImageTask completed = images.findById(980006).orElseThrow();
        assertEquals("partial", completed.status());
        ObjectNode review = ((ObjectNode) completed.results().get(0)).withArray("reviews").addObject();
        review.put("decision", "pass"); review.put("reviewer", "1"); review.put("time", now.toString());
        review.putNull("reason"); review.set("checklist", objectMapper.valueToTree(List.of(
                "slot_count", "style", "color", "logo", "completeness", "pose", "quality")));
        review.put("input_hash", inputHash);
        assertTrue(images.updateReview(980006, completed.results(), completed.rowVersion(), 1, now));
        QuoteImageTask reviewed = images.findById(980006).orElseThrow();
        assertTrue(images.adopt(980004, 980006, 1, "a".repeat(64), reviewed.rowVersion(), 1, now));
        assertEquals(980006L, jdbc.queryForObject(
                "select selected_image_id from fq_quote_combo where id=980004", Long.class));

        jdbc.update("update fq_product set visual_version=3,images_json=json_set(images_json,'$[0].sha256',?) where id=980002",
                "d".repeat(64));
        assertEquals("d".repeat(64), images.currentInputs(980004).get(0).path("image_hash").asText());
        assertTrue(images.markStale(980006, reviewed.rowVersion(), 1, now.plusSeconds(1)));
        assertEquals(1, jdbc.queryForObject("select stale from fq_quote_image where id=980006", Integer.class));
        assertEquals(980006L, jdbc.queryForObject(
                "select selected_image_id from fq_quote_combo where id=980004", Long.class),
                "版本失效不得改写历史采用引用，后续交付应按 stale 阻断");
    }

    @Test
    void productAndImageImportsAreIdempotentAndDetectOptimisticConflicts() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        FashionJsonCodec json = new FashionJsonCodec(objectMapper);
        JdbcFashionProductRepository products = new JdbcFashionProductRepository(namedJdbc, json, objectMapper);
        JdbcFashionImportRepository imports = new JdbcFashionImportRepository(namedJdbc, json, objectMapper);
        FashionDictionaryGuard dictionaries = mock(FashionDictionaryGuard.class);
        doAnswer(invocation -> {
            if ("fashion_product_category".equals(invocation.getArgument(0))
                    && "UNKNOWN".equals(invocation.getArgument(1))) {
                throw new ServiceException("测试字典值不存在");
            }
            return null;
        }).when(dictionaries).requireActiveValue(anyString(), anyString());
        Map<String, byte[]> stored = new ConcurrentHashMap<>();
        FashionObjectStoragePort storage = (key, content, contentType) -> {
            stored.putIfAbsent(key, content.clone());
            return new StoredFashionObject(key, FashionHashing.sha256(content), content.length, contentType);
        };
        AtomicLong ids = new AtomicLong(910_000L);
        FashionIdGenerator idGenerator = ids::incrementAndGet;
        FashionTimeSource timeSource = new FashionTimeSource();
        FashionCatalogWriteLock lock = new FashionCatalogWriteLock(dataSource);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        FashionProductImportService productImport = new FashionProductImportService(
                new FashionProductImportParser(), imports, products, dictionaries, storage, idGenerator,
                timeSource, lock, transaction, objectMapper);
        FashionProductMaterialService materialImport = new FashionProductMaterialService(
                imports, products, dictionaries, storage, idGenerator, timeSource, lock, transaction, objectMapper);
        FashionProductService catalog = new FashionProductService(
                products, dictionaries, idGenerator, timeSource, lock, transaction);

        Instant productAsOf = Instant.now().minusSeconds(5);
        byte[] validCsv = productCsv("000123", "首版商品", "TOP");
        ProductImportView preview = productImport.preview(
                "products.csv", validCsv, Map.of(), Set.of(), "MANUAL", productAsOf, 1L);
        assertEquals("validated", preview.status());
        assertEquals("000123", preview.details().get(0).normalizedData().get("skuCode"));
        ProductImportView samePreview = productImport.preview(
                "products.csv", validCsv, Map.of(), Set.of(), "MANUAL", productAsOf, 1L);
        assertEquals(preview.batchId(), samePreview.batchId());

        ProductImportView published = productImport.publish(preview.batchId(), preview.rowVersion(), 1L);
        assertEquals("success", published.status());
        FashionProduct product = products.findByBusinessKeys(Set.of("MANUAL:000123")).get("MANUAL:000123");
        assertNotNull(product);
        assertEquals("000123", product.skuCode());
        assertNull(product.salePrice(), "商品资料导入不得夹带当前售价");
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from fq_stock s join fq_product p on p.id=s.product_id
                 where p.source_code='MANUAL' and p.sku_code='000123'
                """, Integer.class));
        // V2 真实 JDBC 回读：扩展属性保持来源字符串，不能改变销售价和库存。
        ProductView updatedAttributes = catalog.update(List.of(new ProductPatch(Long.toString(product.id()), product.rowVersion(),
                Map.of("attributes", Map.of("platformSkuId","00000999","fabricComposition","棉100%","observedPrice","19.90")))),1L).get(0);
        assertEquals("00000999",products.findById(product.id()).orElseThrow().attributes().get("platformSkuId"));
        assertNull(updatedAttributes.salePrice());
        Long productBatchId = product.lastProductImportBatchId();
        assertNotNull(productBatchId);

        byte[] invalidCsv = (new String(productCsv("DUPLICATE", "第一行", "UNKNOWN"), StandardCharsets.UTF_8)
                + "MANUAL,DUPLICATE,STYLE-X,第二行,UNKNOWN,BLACK,黑色,M,LETTER,件,品牌,棉,四季,,\n")
                .getBytes(StandardCharsets.UTF_8);
        ProductImportView invalid = productImport.preview(
                "invalid.csv", invalidCsv, Map.of(), Set.of(), "MANUAL", productAsOf, 1L);
        assertEquals("invalid", invalid.status());
        assertEquals(2, invalid.errorCount());
        assertThrows(ServiceException.class,
                () -> productImport.publish(invalid.batchId(), invalid.rowVersion(), 1L));

        Instant imageAsOf = Instant.now().minusSeconds(3);
        byte[] png = png(320, 240);
        ImageMapping mapping = new ImageMapping(
                "000123-main.png", "000123", null, null, "main", true, true, "manual",
                null, null, imageAsOf, true, true, false);
        MaterialImportView imagePreview = materialImport.preview(
                "MANUAL", List.of(new MaterialFile(mapping.filename(), png)), List.of(mapping), imageAsOf, 1L);
        assertEquals("validated", imagePreview.status());
        assertEquals(320, imagePreview.details().get(0).normalizedData().get("width"));
        MaterialImportView confirmed = materialImport.confirm(
                imagePreview.batchId(), imagePreview.rowVersion(), 1L);
        assertEquals("success", confirmed.status());
        FashionProduct withImage = products.findById(product.id()).orElseThrow();
        assertEquals(1, withImage.images().size());
        assertEquals(withImage.images().get(0).objectKey(), withImage.mainImageKey());
        assertEquals(productBatchId, withImage.lastProductImportBatchId(),
                "图片导入不得覆盖商品资料导入溯源");

        MaterialImportView repeatedImage = materialImport.preview(
                "MANUAL", List.of(new MaterialFile(mapping.filename(), png)), List.of(mapping), imageAsOf, 1L);
        assertEquals(imagePreview.batchId(), repeatedImage.batchId());
        assertEquals("success", materialImport.confirm(
                repeatedImage.batchId(), repeatedImage.rowVersion(), 1L).status());
        assertEquals(1, products.findById(product.id()).orElseThrow().images().size());

        ProductView manual = catalog.create(new ProductCreate(
                "MANUAL", "000124", "STYLE-Y", "手工商品", "TOP", "BLACK", "黑色",
                "L", "LETTER", "件", null, null, "四季", null, null), 1L);
        assertEquals("000124", manual.skuCode());
        assertEquals(1, catalog.search("MANUAL", "TOP", "draft", "000124", 1, 20).total());
        ProductView edited = catalog.update(List.of(new ProductPatch(
                manual.id(), manual.rowVersion(), Map.of("name", "手工已确认商品", "attributesConfirmed", true))),
                1L).get(0);
        assertEquals("手工已确认商品", edited.name());
        assertTrue(edited.attributesConfirmed());
        ProductView activated = catalog.changeStatus(manual.id(), "active", edited.rowVersion(), 1L);
        assertEquals("active", activated.status());
        assertThrows(ServiceException.class, () -> catalog.update(List.of(new ProductPatch(
                manual.id(), manual.rowVersion(), Map.of("salePrice", "1.00"))), 1L));

        ProductView whiteVariant = catalog.create(new ProductCreate(
                "MANUAL", "000125", "STYLE-X", "同款白色商品", "TOP", "WHITE", "白色",
                "M", "LETTER", "件", null, null, "四季", null, null), 1L);
        byte[] detailPng = png(321, 240);
        ImageMapping styleColorMapping = new ImageMapping(
                "style-x-black-detail.png", null, "STYLE-X", "BLACK", "detail", false, true, "manual",
                null, null, imageAsOf, true, true, false);
        MaterialImportView styleColorPreview = materialImport.preview(
                "MANUAL", List.of(new MaterialFile(styleColorMapping.filename(), detailPng)),
                List.of(styleColorMapping), imageAsOf, 1L);
        assertEquals("success", materialImport.confirm(
                styleColorPreview.batchId(), styleColorPreview.rowVersion(), 1L).status());
        assertEquals(2, products.findById(product.id()).orElseThrow().images().size());
        assertTrue(products.findById(Long.parseLong(whiteVariant.id())).orElseThrow().images().isEmpty(),
                "款号＋颜色映射不得把黑色图片挂到白色款");

        ImageMapping corruptMapping = new ImageMapping(
                "corrupt.png", "000125", null, null, "detail", false, true, "manual",
                null, null, imageAsOf, true, true, false);
        MaterialImportView corrupt = materialImport.preview(
                "MANUAL", List.of(new MaterialFile(corruptMapping.filename(), new byte[] {1, 2, 3})),
                List.of(corruptMapping), imageAsOf, 1L);
        assertEquals("invalid", corrupt.status());
        assertThrows(ServiceException.class,
                () -> materialImport.confirm(corrupt.batchId(), corrupt.rowVersion(), 1L));
        assertTrue(products.findById(Long.parseLong(whiteVariant.id())).orElseThrow().images().isEmpty());

        MaterialImportView missingMapping = materialImport.preview(
                "MANUAL", List.of(new MaterialFile("unmapped.png", png(322, 240))),
                List.of(), imageAsOf, 1L);
        assertEquals("invalid", missingMapping.status());
        assertTrue(missingMapping.details().get(0).errors().stream()
                .anyMatch(error -> "missing_mapping".equals(error.code())));
        assertEquals(2, products.findById(product.id()).orElseThrow().images().size(),
                "待匹配图片不得污染既有商品关联");

        byte[] changedCsv = productCsv("000123", "并发更新候选", "TOP");
        ProductImportView conflictPreview = productImport.preview(
                "changed.csv", changedCsv, Map.of(), Set.of(), "MANUAL", Instant.now().minusSeconds(1), 1L);
        assertEquals("validated", conflictPreview.status());
        jdbc.update("update fq_product set row_version=row_version+1 where id=?", product.id());
        assertThrows(ServiceException.class,
                () -> productImport.publish(conflictPreview.batchId(), conflictPreview.rowVersion(), 1L));
        assertEquals("conflict", jdbc.queryForObject(
                "select status from fq_import_batch where id=?", String.class,
                Long.parseLong(conflictPreview.batchId())));
        assertEquals("首版商品", products.findById(product.id()).orElseThrow().name());
        assertTrue(stored.size() >= 3, "原始导入文件、图片清单和图片对象应留存");
        assertTrue(jdbc.queryForObject("""
                select count(*) from fq_import_detail
                 where before_data is not null and after_data is not null and status='applied'
                """, Integer.class) >= 2);
    }

    @Test
    void strictPriceAndStockBatchesPublishRestoreAndRejectIncompleteOrOldData() {
        ObjectMapper objectMapper = new ObjectMapper();
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        FashionJsonCodec json = new FashionJsonCodec(objectMapper);
        JdbcFashionProductRepository products = new JdbcFashionProductRepository(namedJdbc, json, objectMapper);
        JdbcFashionImportRepository imports = new JdbcFashionImportRepository(namedJdbc, json, objectMapper);
        JdbcFashionStockRepository stocks = new JdbcFashionStockRepository(namedJdbc);
        FashionDictionaryGuard dictionaries = mock(FashionDictionaryGuard.class);
        Map<String, byte[]> stored = new ConcurrentHashMap<>();
        FashionObjectStoragePort storage = (key, content, contentType) -> {
            stored.putIfAbsent(key, content.clone());
            return new StoredFashionObject(key, FashionHashing.sha256(content), content.length, contentType);
        };
        AtomicLong idValues = new AtomicLong(930_000L);
        FashionIdGenerator idGenerator = idValues::incrementAndGet;
        FashionTimeSource timeSource = new FashionTimeSource();
        FashionCatalogWriteLock lock = new FashionCatalogWriteLock(dataSource);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        FashionProductService catalog = new FashionProductService(
                products, dictionaries, idGenerator, timeSource, lock, transaction);
        ProductView first = catalog.create(new ProductCreate(
                "IMP04", "000201", "PRICE-X", "价格库存一", "TOP", "BLACK", "黑色",
                "M", "LETTER", "件", null, null, "四季", null, null), 1L);
        first = catalog.changeStatus(first.id(), "active", first.rowVersion(), 1L);
        ProductView second = catalog.create(new ProductCreate(
                "IMP04", "000202", "PRICE-X", "价格库存二", "TOP", "WHITE", "白色",
                "L", "LETTER", "件", null, null, "四季", null, null), 1L);
        catalog.changeStatus(second.id(), "active", second.rowVersion(), 1L);

        FashionCatalogValueImportService service = new FashionCatalogValueImportService(
                new FashionCatalogValueImportParser(), imports, products, stocks, dictionaries, storage,
                idGenerator, timeSource, lock, transaction, objectMapper);
        Instant firstAsOf = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
        CatalogImportView missing = service.preview(
                "price", "missing-price.csv", priceCsv(firstAsOf, "000201,10.00,CNY,included"),
                "IMP04", null, null, firstAsOf, 1L);
        assertEquals("invalid", missing.status());
        assertEquals(2, missing.expectedCount());
        assertEquals(1, missing.actualCount());
        assertTrue(missing.details().stream().anyMatch(detail -> "missing".equals(detail.rowType())));
        assertThrows(ServiceException.class,
                () -> service.publish("price", missing.batchId(), missing.rowVersion(), 1L));

        CatalogImportView prices = service.preview(
                "price", "prices.csv", priceCsv(firstAsOf,
                        "000201,0.00,CNY,included", "000202,100.50,CNY,included"),
                "IMP04", null, null, firstAsOf, 1L);
        assertEquals("validated", prices.status());
        CatalogImportView pricesPublished = service.publish("price", prices.batchId(), prices.rowVersion(), 1L);
        assertEquals("success", pricesPublished.status());
        assertEquals("0.00", jdbc.queryForObject(
                "select cast(sale_price as char) from fq_product where source_code='IMP04' and sku_code='000201'",
                String.class));
        assertEquals(prices.batchId(), Long.toString(jdbc.queryForObject(
                "select last_price_import_batch_id from fq_product where source_code='IMP04' and sku_code='000202'",
                Long.class)));
        assertEquals("success", service.publish("price", prices.batchId(), pricesPublished.rowVersion(), 1L).status());

        Instant secondAsOf = firstAsOf.plusSeconds(10);
        CatalogImportView newerPrices = service.preview(
                "price", "prices-new.csv", priceCsv(secondAsOf,
                        "000201,20.00,CNY,included", "000202,120.00,CNY,included"),
                "IMP04", null, null, secondAsOf, 1L);
        service.publish("price", newerPrices.batchId(), newerPrices.rowVersion(), 1L);
        CatalogImportView priceRestore = service.restore(
                "price", newerPrices.batchId(), "restore-price-1", "恢复前一批价格", 1L);
        assertEquals("restore", priceRestore.operationType());
        assertEquals(newerPrices.batchId(), priceRestore.sourceBatchId());
        service.publish("price", priceRestore.batchId(), priceRestore.rowVersion(), 1L);
        assertEquals("0.00", jdbc.queryForObject(
                "select cast(sale_price as char) from fq_product where source_code='IMP04' and sku_code='000201'",
                String.class));

        Instant stockAsOf = Instant.now().minusSeconds(15).truncatedTo(ChronoUnit.MILLIS);
        CatalogImportView stock = service.preview(
                "stock", "stock.csv", stockCsv(stockAsOf, "000201,0", "000202,5"),
                "IMP04", null, "MAIN", stockAsOf, 1L);
        assertEquals("success", service.publish("stock", stock.batchId(), stock.rowVersion(), 1L).status());
        assertEquals(0, jdbc.queryForObject("""
                select s.available_qty from fq_stock s join fq_product p on p.id=s.product_id
                 where p.source_code='IMP04' and p.sku_code='000201' and s.warehouse_code='MAIN'
                """, Integer.class));
        CatalogImportView oldStock = service.preview(
                "stock", "stock-old.csv", stockCsv(stockAsOf.minusSeconds(1), "000201,9", "000202,9"),
                "IMP04", null, "MAIN", stockAsOf.minusSeconds(1), 1L);
        assertEquals("invalid", oldStock.status());
        assertTrue(oldStock.details().stream().flatMap(detail -> detail.errors().stream())
                .anyMatch(error -> "older_than_current".equals(error.code())));
        assertEquals(0, jdbc.queryForObject("""
                select s.available_qty from fq_stock s join fq_product p on p.id=s.product_id
                 where p.source_code='IMP04' and p.sku_code='000201' and s.warehouse_code='MAIN'
                """, Integer.class));

        Instant stockNewAsOf = stockAsOf.plusSeconds(5);
        CatalogImportView stockNew = service.preview(
                "stock", "stock-new.csv", stockCsv(stockNewAsOf, "000201,7", "000202,8"),
                "IMP04", null, "MAIN", stockNewAsOf, 1L);
        service.publish("stock", stockNew.batchId(), stockNew.rowVersion(), 1L);
        CatalogImportView stockRestore = service.restore(
                "stock", stockNew.batchId(), "restore-stock-1", "恢复显式零库存", 1L);
        service.publish("stock", stockRestore.batchId(), stockRestore.rowVersion(), 1L);
        assertEquals(0, jdbc.queryForObject("""
                select s.available_qty from fq_stock s join fq_product p on p.id=s.product_id
                 where p.source_code='IMP04' and p.sku_code='000201' and s.warehouse_code='MAIN'
                """, Integer.class));
        Instant failureAsOf = Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.MILLIS);
        CatalogImportView rollbackCandidate = service.preview(
                "price", "price-rollback.csv", priceCsv(failureAsOf,
                        "000201,30.00,CNY,included", "000202,130.00,CNY,included"),
                "IMP04", null, null, failureAsOf, 1L);
        String priceBeforeFailure = jdbc.queryForObject(
                "select cast(sale_price as char) from fq_product where source_code='IMP04' and sku_code='000201'",
                String.class);
        jdbc.update("""
                update fq_import_detail
                   set normalized_data=json_set(normalized_data, '$.salePrice', '999999999999999.00')
                 where batch_id=? and business_key='IMP04:000202'
                """, Long.parseLong(rollbackCandidate.batchId()));
        assertThrows(RuntimeException.class, () -> service.publish(
                "price", rollbackCandidate.batchId(), rollbackCandidate.rowVersion(), 1L));
        assertEquals(priceBeforeFailure, jdbc.queryForObject(
                "select cast(sale_price as char) from fq_product where source_code='IMP04' and sku_code='000201'",
                String.class), "第二行写入失败必须回滚第一行价格");
        assertEquals("validated", jdbc.queryForObject(
                "select status from fq_import_batch where id=?", String.class,
                Long.parseLong(rollbackCandidate.batchId())));

        Instant conflictAsOf = failureAsOf.plusSeconds(1);
        CatalogImportView conflict = service.preview(
                "price", "price-conflict.csv", priceCsv(conflictAsOf,
                        "000201,40.00,CNY,included", "000202,140.00,CNY,included"),
                "IMP04", null, null, conflictAsOf, 1L);
        jdbc.update("update fq_product set row_version=row_version+1 where source_code='IMP04' and sku_code='000202'");
        assertThrows(ServiceException.class,
                () -> service.publish("price", conflict.batchId(), conflict.rowVersion(), 1L));
        assertEquals("conflict", jdbc.queryForObject(
                "select status from fq_import_batch where id=?", String.class, Long.parseLong(conflict.batchId())));
        assertTrue(stored.size() >= 5);
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from fq_import_batch b
                 where b.source_code='IMP04' and b.status='publishing'
                """, Integer.class));
    }

    @Test
    void customerQuoteAgentAndRunLifecycleEnforcesEncryptionScopeLeaseAndFencing() {
        ObjectMapper objectMapper = new ObjectMapper();
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        FashionJsonCodec json = new FashionJsonCodec(objectMapper);
        byte[] contactKey = new byte[32];
        java.util.Arrays.fill(contactKey, (byte) 23);
        JdbcFashionCustomerRepository customers = new JdbcFashionCustomerRepository(
                namedJdbc, json, objectMapper,
                new AesGcmFashionCustomerContactCipher("imp05", Map.of("imp05", contactKey)));
        JdbcFashionQuoteRepository quotes = new JdbcFashionQuoteRepository(namedJdbc, json);
        JdbcFashionAgentRepository agents = new JdbcFashionAgentRepository(namedJdbc, json);
        JdbcFashionRunRepository runs = new JdbcFashionRunRepository(namedJdbc, json);
        JdbcFashionProductRepository products = new JdbcFashionProductRepository(namedJdbc, json, objectMapper);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        FashionCustomer customer = new FashionCustomer(
                950_001L, "IMP05-CUSTOMER", "IMP05 客户", "group_purchase", "联系人", "13800138000",
                "上海", 11L, List.of(12L), "仅内部可见", "active", 11L, now, 11L, now, 1L);
        customers.insert(customer);
        String storedPhone = jdbc.queryForObject(
                "select contact_phone from fq_customer where id=950001", String.class);
        assertTrue(storedPhone.startsWith("v1.imp05."));
        org.junit.jupiter.api.Assertions.assertNotEquals("13800138000", storedPhone);
        assertEquals("13800138000", customers.findById(950_001L).orElseThrow().contactPhone());
        assertEquals(1, customers.search(11L, false, "active", null, 0, 20).size());
        assertEquals(1, customers.search(12L, false, "active", null, 0, 20).size());
        assertEquals(0, customers.search(13L, false, "active", null, 0, 20).size());

        var requirement = objectMapper.createObjectNode();
        requirement.put("schema_version", "1.0").put("confirmed", false);
        requirement.putArray("preferred_colors");
        requirement.putArray("exclusions");
        var combo = objectMapper.createObjectNode();
        combo.put("schema_version", "1.0").put("template_code", "quote-tier-selection")
                .put("template_version", "1.0");
        var group = combo.putArray("groups").addObject();
        group.put("count", 1).put("candidate_count", 3).putArray("slots").add("TOP");
        FashionQuote quote = new FashionQuote(
                950_002L, "Q-IMP05-1", 1, null, customer.id(), customer.name(), "员工活动服装",
                11L, "需要一百套员工活动服装", requirement, false, 100, new BigDecimal("30000.00"),
                "total", "alternatives", true, combo, "MAIN", "CNY", "included",
                objectMapper.createObjectNode().put("schema_version", "1.0"), "draft",
                11L, now, 11L, now, 1L);
        quotes.insert(quote);
        assertEquals(1, quotes.search(12L, false, "draft", "员工", 0, 20).size());
        assertEquals(0, quotes.search(13L, false, "draft", null, 0, 20).size());

        agents.insertAgent(950_003L, "requirement-imp05", "需求分析", "requirement", null, 1L, now);
        AgentVersionDraft draft = new AgentVersionDraft(
                "test-provider", "test-model", "只输出待人工确认的需求草稿",
                objectMapper.createObjectNode(), objectMapper.createArrayNode(), objectMapper.createArrayNode(),
                objectMapper.createObjectNode().put("type", "object"),
                objectMapper.createObjectNode().put("type", "object"), objectMapper.createObjectNode(), 4, 120);
        String configHash = FashionHashing.sha256("imp05-agent-config".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, agents.nextVersion(950_003L));
        AgentVersionView draftVersion = agents.insertVersion(
                950_004L, 950_003L, 1, draft, configHash, 1L, now);
        assertEquals(Boolean.TRUE, transaction.execute(status -> agents.publishVersion(
                950_003L, 950_004L, draftVersion.rowVersion(), 1L, now)));
        AgentVersionView version = agents.findCurrentVersionByType("requirement").orElseThrow();

        String sourceHash = FashionHashing.sha256("需要一百套员工活动服装".getBytes(StandardCharsets.UTF_8));
        RunView first = transaction.execute(status -> runs.insertRequirementRun(
                950_005L, 950_006L, 950_007L, quote, version, "需要一百套员工活动服装",
                sourceHash, "imp05-requirement-request-0001", now.plusSeconds(120), 11L, now));
        assertNotNull(first);
        RunWorkItem firstLease = transaction.execute(status -> runs.claimNext(
                950_008L, "lease-1", "worker-a", now.plusMillis(1), now.plusSeconds(30))).orElseThrow();
        assertEquals(1, firstLease.runAttempt());
        assertEquals(1L, firstLease.fencingToken());
        assertEquals(Boolean.TRUE, transaction.execute(status -> runs.renew(
                firstLease.runId(), firstLease.leaseId(), firstLease.fencingToken(),
                now.plusSeconds(1), now.plusSeconds(31))));

        jdbc.update("update fq_ai_run set lease_until=? where id=950007",
                Timestamp.from(now.minusSeconds(1)));
        RunWorkItem takeover = transaction.execute(status -> runs.claimNext(
                950_009L, "lease-2", "worker-b", now.plusSeconds(2), now.plusSeconds(32))).orElseThrow();
        assertEquals(2, takeover.runAttempt());
        assertEquals(2L, takeover.fencingToken());
        var result = objectMapper.createObjectNode();
        result.put("fact_scope", "requirements_only").put("human_confirmation_required", true);
        result.putObject("draft").put("status", "pending_human_confirmation")
                .put("audience", "员工").put("set_count", 100);
        result.putArray("unresolved_questions");
        assertEquals(Boolean.FALSE, transaction.execute(status -> runs.complete(
                firstLease, result, "requirement", FashionHashing.sha256("old".getBytes(StandardCharsets.UTF_8)),
                950_010L, "需求分析完成", now.plusSeconds(3))));
        assertEquals(Boolean.TRUE, transaction.execute(status -> runs.complete(
                takeover, result, "requirement", FashionHashing.sha256("new".getBytes(StandardCharsets.UTF_8)),
                950_011L, "需求分析完成", now.plusSeconds(3))));
        assertEquals("succeeded", runs.findById(950_007L).orElseThrow().status());
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from fq_ai_run_step where run_id=950007", Integer.class));

        RunView cancellable = transaction.execute(status -> runs.insertRequirementRun(
                950_012L, 950_013L, 950_014L, quote, version, "再分析一份员工活动服装需求",
                sourceHash, "imp05-requirement-request-0002", now.plusSeconds(120), 11L, now));
        RunWorkItem cancelLease = transaction.execute(status -> runs.claimNext(
                950_015L, "lease-cancel", "worker-a", now.plusSeconds(4), now.plusSeconds(34))).orElseThrow();
        assertEquals(Boolean.TRUE, transaction.execute(status -> runs.cancel(
                950_014L, runs.findById(950_014L).orElseThrow().rowVersion(), 11L, now.plusSeconds(5))));
        assertEquals(Boolean.TRUE, transaction.execute(
                status -> runs.acknowledgeCancellation(cancelLease, now.plusSeconds(5))));
        assertEquals("cancelled", runs.findById(950_014L).orElseThrow().status());

        RunView retryable = transaction.execute(status -> runs.insertRequirementRun(
                950_016L, 950_017L, 950_018L, quote, version, "第三次分析员工活动服装需求",
                sourceHash, "imp05-requirement-request-0003", now.plusSeconds(120), 11L, now));
        RunWorkItem retryLease = transaction.execute(status -> runs.claimNext(
                950_019L, "lease-retry-1", "worker-a", now.plusSeconds(6), now.plusSeconds(36))).orElseThrow();
        assertEquals(Boolean.TRUE, transaction.execute(status -> runs.fail(
                retryLease, "DEPENDENCY_UNAVAILABLE", "上游暂不可用", true, now.plusSeconds(7))));
        assertEquals("queued", runs.findById(950_018L).orElseThrow().status());
        jdbc.update("update fq_ai_run set deadline_at=? where id=950018", Timestamp.from(now));
        assertEquals(Integer.valueOf(1), transaction.execute(status -> runs.expireDue(now.plusSeconds(8))));
        assertEquals("failed", runs.findById(950_018L).orElseThrow().status());
        assertNotNull(cancellable);
        assertNotNull(retryable);

        FashionProduct product = new FashionProduct(
                950_020L, "IMP05", "SKU-AI-001", "STYLE-AI", "待补全商品", "TOP",
                "BLACK", "黑色", "M", "LETTER", "件", null, "CNY", "included", null,
                null, null, null, "四季", List.of(), null, List.of(), 1L, null, false,
                null, null, null, null, null, FashionProductStatus.DRAFT,
                11L, now, 11L, now, 1L);
        products.insert(product);
        agents.insertAgent(950_021L, "selection-imp05", "商品属性建议", "selection", null, 1L, now);
        AgentVersionView selectionDraft = agents.insertVersion(
                950_022L, 950_021L, 1, draft,
                FashionHashing.sha256("imp05-selection-config".getBytes(StandardCharsets.UTF_8)), 1L, now);
        assertEquals(Boolean.TRUE, transaction.execute(status -> agents.publishVersion(
                950_021L, 950_022L, selectionDraft.rowVersion(), 1L, now)));
        AgentVersionView selectionVersion = agents.findCurrentVersionByType("selection").orElseThrow();
        RunView productRun = transaction.execute(status -> runs.insertProductAttributeRun(
                950_023L, 950_024L, 950_025L, ProductView.from(product), selectionVersion,
                FashionHashing.sha256("product-snapshot".getBytes(StandardCharsets.UTF_8)),
                "imp05-product-attribute-0001", now.plusSeconds(120), 11L, now));
        RunWorkItem productLease = transaction.execute(status -> runs.claimNext(
                950_026L, "lease-product", "worker-a", now.plusSeconds(9), now.plusSeconds(39))).orElseThrow();
        assertEquals("select_products", productLease.triggerType());
        assertEquals(Long.valueOf(950_020L), productLease.productId());
        var productResult = objectMapper.createObjectNode();
        productResult.put("fact_scope", "product_attributes_only").put("human_confirmation_required", true);
        productResult.putObject("draft").put("status", "pending_human_confirmation")
                .put("product_ref", "950020").put("style", "商务休闲").put("scene", "员工活动")
                .putArray("observable_tags").add("纯色").add("圆领");
        productResult.putArray("ambiguities");
        String productOutputHash = FashionHashing.sha256("product-output".getBytes(StandardCharsets.UTF_8));
        assertEquals(Boolean.TRUE, transaction.execute(status -> runs.complete(
                productLease, productResult, "product_attributes", productOutputHash,
                950_027L, "商品属性建议完成", now.plusSeconds(10))));
        FashionProductService productService = new FashionProductService(
                products, mock(FashionDictionaryGuard.class), () -> 950_099L,
                new FashionTimeSource(), new FashionCatalogWriteLock(dataSource), transaction);
        ProductView appliedProduct = productService.applyAttributeSuggestion(
                "950020", Map.of("tags", List.of("style:商务休闲", "scene:员工活动", "appearance:纯色", "appearance:圆领")),
                product.rowVersion(), 950_025L, 11L);
        assertEquals(List.of("style:商务休闲", "scene:员工活动", "appearance:纯色", "appearance:圆领"), appliedProduct.tags());
        assertTrue(appliedProduct.attributesConfirmed());
        assertEquals(Long.valueOf(950_025L), products.findById(950_020L).orElseThrow().lastAiRunId());
        assertEquals(Boolean.TRUE, transaction.execute(status -> runs.markApplied(
                950_025L, "imp05-product-apply-0001",
                FashionHashing.sha256((productOutputHash + ":1").getBytes(StandardCharsets.UTF_8)), 11L,
                objectMapper.createObjectNode().put("product_id", "950020"), now.plusSeconds(11))));
        assertEquals("applied", runs.findByRunNo(productRun.runNo()).orElseThrow().applyStatus());
    }

    @Test
    void frozenSelectionRunAndQuoteComboPersistOnTheExistingSixteenTables() {
        ObjectMapper objectMapper = new ObjectMapper();
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        FashionJsonCodec json = new FashionJsonCodec(objectMapper);
        JdbcFashionQuoteRepository quotes = new JdbcFashionQuoteRepository(namedJdbc, json);
        JdbcFashionSelectionRepository selection = new JdbcFashionSelectionRepository(namedJdbc, objectMapper, json);
        JdbcFashionAgentRepository agents = new JdbcFashionAgentRepository(namedJdbc, json);
        JdbcFashionRunRepository runs = new JdbcFashionRunRepository(namedJdbc, json);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String hash = "d".repeat(64);

        jdbc.update("""
                insert into fq_import_batch (
                    id,batch_no,import_type,operation_type,source_code,warehouse_code,mapping_snapshot,
                    scope_json,scope_hash,base_data_hash,as_of,request_key,expected_count,actual_count,
                    error_count,status,create_by,create_time,update_by,update_time,row_version)
                values (960001,'B-IMP06-PRODUCT','product','import','IMP06',null,json_object(),
                    json_object(),?,?,?, 'imp06-product-batch-0001',2,2,0,'success',1,?,1,?,1),
                       (960002,'B-IMP06-PRICE','price','import','IMP06',null,json_object(),
                    json_object(),?,?,?, 'imp06-price-batch-00001',2,2,0,'success',1,?,1,?,1),
                       (960003,'B-IMP06-STOCK','stock','import','IMP06','MAIN',json_object(),
                    json_object(),?,?,?, 'imp06-stock-batch-00001',2,2,0,'success',1,?,1,?,1)
                """, hash, hash, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                hash, hash, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                hash, hash, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into fq_customer (
                    id,code,name,customer_type,salesperson_id,collaborator_ids,status,
                    create_by,create_time,update_by,update_time,row_version)
                values (960010,'IMP06-CUSTOMER','IMP06 客户','group_purchase',1,json_array(),'active',
                    1,?,1,?,1)
                """, Timestamp.from(now), Timestamp.from(now));
        String imageJson = """
                [{"imageId":"IMG-IMP06","objectKey":"fashion/imp06/main.jpg","sha256":"%s",
                "usage":"main","sourceType":"upload","jdId":null,"sourceUrl":null,
                "capturedAt":"%s","allowInternal":true,"allowAi":true,"allowProposal":true,
                "allowEcommerce":false,"status":"active","confirmedBy":1,"confirmedAt":"%s",
                "width":800,"height":800,"originalFilename":"main.jpg"}]
                """.formatted(hash, now, now).replaceAll("\\s+", "");
        for (int index = 0; index < 2; index++) {
            long productId = 960020 + index;
            String size = index == 0 ? "M" : "L";
            jdbc.update("""
                    insert into fq_product (
                        id,source_code,sku_code,style_code,name,category_code,color_code,color_name,size_code,
                        size_system,unit,sale_price,currency,tax_mode,price_as_of,last_price_import_batch_id,
                        season,tags_json,main_image_key,images_json,visual_version,attributes_confirmed,
                        attributes_confirmed_by,attributes_confirmed_at,last_product_import_batch_id,status,
                        create_by,create_time,update_by,update_time,row_version)
                    values (?,'IMP06',?,'STYLE-A','活动上衣','TOP','BEIGE','米色',?,'LETTER','件',
                        50.00,'CNY','included',?,960002,'秋季',json_array('休闲'),'fashion/imp06/main.jpg',?,
                        1,1,1,?,960001,'active',1,?,1,?,1)
                    """, productId, "SKU-" + size, size, Timestamp.from(now), imageJson,
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
            jdbc.update("""
                    insert into fq_stock (
                        id,product_id,warehouse_code,available_qty,as_of,confirmation_type,last_import_batch_id,
                        create_by,create_time,update_by,update_time,row_version)
                    values (?,?,'MAIN',60,?,'import',960003,1,?,1,?,1)
                    """, 960030 + index, productId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        }
        ObjectNode requirement = objectMapper.createObjectNode();
        requirement.put("schema_version", "1.0").put("confirmed", true);
        requirement.putArray("preferred_colors").add("米色");
        requirement.putArray("exclusions");
        ObjectNode template = objectMapper.createObjectNode();
        template.put("schema_version", "1.0");
        template.putArray("groups").addObject().put("count", 1).put("candidate_count", 1)
                .putArray("slots").add("TOP");
        FashionQuote quote = new FashionQuote(960040, "Q-IMP06-1", 1, null, 960010, "IMP06 客户",
                "IMP06 选品", 1, "一百套活动上衣", requirement, true, 100, new BigDecimal("100.00"),
                "per_set", "alternatives", true, template, "MAIN", "CNY", "included",
                objectMapper.createObjectNode(), "draft", 1, now, 1, now, 1);
        quotes.insert(quote);

        agents.insertAgent(960050, "selection-imp06", "选品搭配", "selection", null, 1, now);
        AgentVersionDraft draft = new AgentVersionDraft("test-provider", "test-model", "仅排序冻结候选",
                objectMapper.createObjectNode(), objectMapper.createArrayNode(), objectMapper.createArrayNode(),
                objectMapper.createObjectNode().put("type", "object"),
                objectMapper.createObjectNode().put("type", "object"), objectMapper.createObjectNode(), 4, 120);
        AgentVersionView versionDraft = agents.insertVersion(960051, 960050, 1, draft, hash, 1, now);
        assertEquals(Boolean.TRUE, transaction.execute(status -> agents.publishVersion(
                960050, 960051, versionDraft.rowVersion(), 1, now)));

        List<com.ruoyi.fashion.application.selection.SelectionProductFact> facts =
                selection.findCandidateFacts(Set.of("TOP"), "MAIN");
        assertEquals(2, facts.size());
        assertEquals(120, facts.stream().mapToInt(
                com.ruoyi.fashion.application.selection.SelectionProductFact::availableQty).sum());
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("quote_ref", "960040").put("quote_row_version", 1)
                .put("selection_mode", "progressive").put("requested_qty", 100)
                .put("candidate_set_hash", hash);
        snapshot.putArray("frozen_candidates");
        RunView run = transaction.execute(status -> runs.insertSelectionRun(
                960052, 960053, 960054, quote, agents.findCurrentVersionByType("selection").orElseThrow(),
                snapshot, hash, "imp06-selection-run-00001", now.plusSeconds(120), 1, now));
        RunWorkItem work = transaction.execute(status -> runs.claimNext(
                960055, "lease-imp06", "worker-imp06", now.plusMillis(1), now.plusSeconds(30))).orElseThrow();
        assertEquals("style", work.triggerType());
        assertEquals(hash, work.contextSnapshot().path("selection_snapshot").path("candidate_set_hash").asText());
        ObjectNode output = objectMapper.createObjectNode();
        output.put("fact_scope", "frozen_candidates_only").put("human_confirmation_required", true)
                .put("quote_ref", "960040").put("quote_row_version", 1).put("candidate_set_hash", hash)
                .putArray("tiers");
        assertEquals(Boolean.TRUE, transaction.execute(status -> runs.complete(work, output, "selection", hash,
                960056, "选品搭配完成", now.plusSeconds(2))));

        SelectionComboWrite combo = new SelectionComboWrite(960060, 960040, "C-960060", "单品类方案", 1,
                100, 0, "冻结候选", List.of("SLOT-1"), hash, 1, now);
        List<SelectionDetailWrite> details = List.of(
                new SelectionDetailWrite(960061, 960060, 960054L, 1, "SLOT-1", 960020,
                        "IMP06", "SKU-M", "STYLE-A", "活动上衣", "TOP", "BEIGE", "米色",
                        "M", "LETTER", "件", 50, new BigDecimal("50.00"), 960002, 960003,
                        60, now, "fashion/imp06/main.jpg", hash, 1, 1, now),
                new SelectionDetailWrite(960062, 960060, 960054L, 2, "SLOT-1", 960021,
                        "IMP06", "SKU-L", "STYLE-A", "活动上衣", "TOP", "BEIGE", "米色",
                        "L", "LETTER", "件", 50, new BigDecimal("50.00"), 960002, 960003,
                        60, now, "fashion/imp06/main.jpg", hash, 1, 1, now));
        transaction.executeWithoutResult(status -> {
            assertTrue(quotes.touchDraft(960040, 1, 1, now.plusSeconds(3)));
            selection.insertCombo(combo, details);
        });
        assertEquals(1, selection.findByQuoteId(960040).get(0).categoryCount());
        assertEquals(2, selection.findByQuoteId(960040).get(0).details().size());
        assertEquals(100, selection.findByQuoteId(960040).get(0).details().stream()
                .mapToInt(com.ruoyi.fashion.application.selection.SelectionDetailView::qty).sum());
        assertEquals("succeeded", runs.findById(Long.parseLong(run.id())).orElseThrow().status());
        assertEquals(19, jdbc.queryForObject("""
                select count(*) from information_schema.tables
                 where table_schema=database() and left(table_name,3)='fq_'
                """, Integer.class));
    }

    @Test
    void deterministicPricingRechecksFactsFreezesOneVersionAndCopiesThreeLayers() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        FashionJsonCodec json = new FashionJsonCodec(objectMapper);
        JdbcFashionQuotePricingRepository pricingRepository = new JdbcFashionQuotePricingRepository(namedJdbc, json);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        FashionQuoteDraftService quoteAccess = mock(FashionQuoteDraftService.class);
        ISysConfigService settings = mock(ISysConfigService.class);
        when(settings.selectConfigByKey("fashion.stock.freshnessHours")).thenReturn("24");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        FashionTimeSource fixedTime = mock(FashionTimeSource.class);
        when(fixedTime.now()).thenReturn(now);
        FashionQuotePricingService service = new FashionQuotePricingService(pricingRepository, quoteAccess,
                new QuotePricingCalculator(), new FashionCatalogWriteLock(dataSource), settings, fixedTime,
                objectMapper, transaction);
        String hash = "8".repeat(64);

        jdbc.update("""
                insert into fq_import_batch (
                    id,batch_no,import_type,operation_type,source_code,warehouse_code,mapping_snapshot,
                    scope_json,scope_hash,base_data_hash,as_of,request_key,expected_count,actual_count,
                    error_count,status,create_by,create_time,update_by,update_time,row_version)
                values (970001,'B-IMP08-PRODUCT','product','import','IMP08',null,json_object(),json_object(),?,?,?,
                        'imp08-product-batch-0001',4,4,0,'success',1,?,1,?,1),
                       (970002,'B-IMP08-PRICE','price','import','IMP08',null,json_object(),json_object(),?,?,?,
                        'imp08-price-batch-00001',4,4,0,'success',1,?,1,?,1),
                       (970003,'B-IMP08-STOCK','stock','import','IMP08','MAIN',json_object(),json_object(),?,?,?,
                        'imp08-stock-batch-00001',4,4,0,'success',1,?,1,?,1)
                """, hash, hash, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                hash, hash, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                hash, hash, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into fq_customer (id,code,name,customer_type,salesperson_id,collaborator_ids,status,
                    create_by,create_time,update_by,update_time,row_version)
                values (970010,'IMP08-CUSTOMER','IMP08 客户','group_purchase',1,json_array(),'active',1,?,1,?,1)
                """, Timestamp.from(now), Timestamp.from(now));
        String[] slots = {"TOP", "PANTS", "HAT", "SHOES"};
        String[] prices = {"80.00", "60.00", "20.00", "90.00"};
        for (int index = 0; index < slots.length; index++) {
            long productId = 970020 + index;
            String objectKey = "fashion/imp08/" + index + ".jpg";
            String imageJson = """
                    [{"imageId":"IMG-IMP08-%d","objectKey":"%s","sha256":"%s","usage":"main",
                    "sourceType":"upload","jdId":null,"sourceUrl":null,"capturedAt":"%s",
                    "allowInternal":true,"allowAi":true,"allowProposal":true,"allowEcommerce":false,
                    "status":"active","confirmedBy":1,"confirmedAt":"%s","width":800,"height":800,
                    "originalFilename":"main.jpg"}]
                    """.formatted(index, objectKey, hash, now, now).replaceAll("\\s+", "");
            jdbc.update("""
                    insert into fq_product (id,source_code,sku_code,style_code,name,category_code,color_code,
                        color_name,size_code,size_system,unit,sale_price,currency,tax_mode,price_as_of,
                        last_price_import_batch_id,tags_json,main_image_key,images_json,visual_version,
                        attributes_confirmed,last_product_import_batch_id,status,create_by,create_time,update_by,
                        update_time,row_version)
                    values (?,'IMP08',?,?,'IMP08 商品',?,'BLACK','黑色','M','LETTER','件',?,'CNY','included',?,
                        970002,json_array(),?,?,1,0,970001,'active',1,?,1,?,1)
                    """, productId, "SKU-IMP08-" + index, "STYLE-IMP08-" + index, slots[index],
                    new BigDecimal(prices[index]), Timestamp.from(now), objectKey, imageJson,
                    Timestamp.from(now), Timestamp.from(now));
            jdbc.update("""
                    insert into fq_stock (id,product_id,warehouse_code,available_qty,as_of,confirmation_type,
                        last_import_batch_id,create_by,create_time,update_by,update_time,row_version)
                    values (?,?, 'MAIN',130,?,'import',970003,1,?,1,?,1)
                    """, 970030 + index, productId, Timestamp.from(now.minusSeconds(3600)),
                    Timestamp.from(now), Timestamp.from(now));
        }
        jdbc.update("""
                insert into fq_quote (id,quote_no,version_no,customer_id,customer_name,title,salesperson_id,
                    requirement_json,requirement_confirmed,requested_qty,budget_basis,quote_mode,progressive,
                    combo_template_json,warehouse_code,currency,tax_mode,discount_type,discount_rate,
                    fixed_discount,freight,valid_days,presentation_json,status,create_by,create_time,
                    update_by,update_time,row_version)
                values (970040,'Q-IMP08',1,970010,'IMP08 客户','确定性报价',1,json_object('confirmed',true),1,
                    100,'total','combined',1,json_object(),'MAIN','CNY','included','percent',5.00,0,300.00,7,
                    json_object(),'draft',1,?,1,?,1)
                """, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into fq_quote_combo (id,quote_id,combo_no,name,category_count,set_qty,selected,sort_no,
                    visual_hash,allocation_confirmed,create_by,create_time,update_by,update_time,row_version)
                values (970050,970040,'C-IMP08','四品类组合',4,100,1,1,?,1,1,?,1,?,1)
                """, hash, Timestamp.from(now), Timestamp.from(now));
        for (int index = 0; index < slots.length; index++) {
            jdbc.update("""
                    insert into fq_quote_detail (id,combo_id,line_no,slot_code,product_id,source_code,sku_code,
                        style_code,product_name,category_code,color_code,color_name,size_code,size_system,unit,qty,
                        source_price,quote_price,price_batch_id,stock_batch_id,stock_qty,stock_as_of,image_key,
                        image_hash,image_version,create_by,create_time,update_by,update_time,row_version)
                    values (?,970050,?,?,?,'IMP08',?,?,'IMP08 商品',?,'BLACK','黑色','M','LETTER','件',100,?,?,
                        970002,970003,130,?,?,?,1,1,?,1,?,1)
                    """, 970060 + index, index + 1, slots[index], 970020 + index,
                    "SKU-IMP08-" + index, "STYLE-IMP08-" + index, slots[index],
                    new BigDecimal(prices[index]), new BigDecimal(prices[index]), Timestamp.from(now.minusSeconds(3600)),
                    "fashion/imp08/" + index + ".jpg", hash, Timestamp.from(now), Timestamp.from(now));
        }

        QuotePricingWorkspace firstPreview = service.get("970040");
        assertEquals("24050.00", firstPreview.totalAmount());
        transaction.executeWithoutResult(status -> new FashionCatalogWriteLock(dataSource).executeLocked(2, () -> {
            jdbc.update("update fq_product set sale_price=81.00,row_version=row_version+1 where id=970020");
            return null;
        }));
        ServiceException changed = assertThrows(ServiceException.class, () -> service.confirm("970040",
                new QuoteConfirmCommand(firstPreview.inputHash(), firstPreview.rowVersion()), 1));
        assertEquals(409, changed.getCode());
        transaction.executeWithoutResult(status -> new FashionCatalogWriteLock(dataSource).executeLocked(2, () -> {
            jdbc.update("update fq_product set sale_price=80.00,row_version=row_version+1 where id=970020");
            return null;
        }));

        QuotePricingWorkspace refreshed = service.get("970040");
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<QuotePricingWorkspace> left = CompletableFuture.supplyAsync(() -> {
            await(start); return service.confirm("970040",
                    new QuoteConfirmCommand(refreshed.inputHash(), refreshed.rowVersion()), 1);
        });
        CompletableFuture<QuotePricingWorkspace> right = CompletableFuture.supplyAsync(() -> {
            await(start); return service.confirm("970040",
                    new QuoteConfirmCommand(refreshed.inputHash(), refreshed.rowVersion()), 2);
        });
        start.countDown();
        assertEquals("confirmed", left.get(10, TimeUnit.SECONDS).status());
        assertEquals("confirmed", right.get(10, TimeUnit.SECONDS).status());
        assertEquals(2L, jdbc.queryForObject("select row_version from fq_quote where id=970040", Long.class));
        assertEquals(new BigDecimal("24050.00"), jdbc.queryForObject(
                "select total_amount from fq_quote where id=970040", BigDecimal.class));

        jdbc.update("""
                update fq_product set sale_price=99.00,visual_version=2,
                    images_json=json_set(images_json,'$[0].allowProposal',false,'$[0].sha256',?),
                    row_version=row_version+1 where id=970020
                """, "9".repeat(64));
        jdbc.update("update fq_stock set available_qty=5,row_version=row_version+1 where product_id=970020");
        QuotePricingWorkspace frozen = service.get("970040");
        assertEquals("24050.00", frozen.totalAmount());
        assertEquals("80.00", frozen.combos().get(0).lines().get(0).sourcePrice());
        assertTrue(frozen.combos().get(0).lines().get(0).priceChanged());
        assertTrue(frozen.combos().get(0).lines().get(0).stockChanged());
        assertTrue(frozen.combos().get(0).lines().get(0).imageChanged());

        jdbc.update("""
                insert into fq_quote (id,quote_no,version_no,source_quote_id,customer_id,customer_name,title,
                    salesperson_id,requirement_json,requirement_confirmed,requested_qty,budget_basis,quote_mode,
                    progressive,combo_template_json,warehouse_code,currency,tax_mode,presentation_json,status,
                    create_by,create_time,update_by,update_time,row_version)
                select 970041,quote_no,2,id,customer_id,customer_name,title,salesperson_id,requirement_json,
                    requirement_confirmed,requested_qty,budget_basis,quote_mode,progressive,combo_template_json,
                    warehouse_code,currency,tax_mode,presentation_json,'draft',1,?,1,?,1
                  from fq_quote where id=970040
                """, Timestamp.from(now), Timestamp.from(now));
        AtomicLong revisionIds = new AtomicLong(970100);
        transaction.executeWithoutResult(status -> pricingRepository.copyStructure(
                970040, 970041, revisionIds::getAndIncrement, 1, now));
        assertEquals(1, jdbc.queryForObject("select count(*) from fq_quote_combo where quote_id=970041", Integer.class));
        assertEquals(4, jdbc.queryForObject("""
                select count(*) from fq_quote_detail d join fq_quote_combo c on c.id=d.combo_id
                 where c.quote_id=970041
                """, Integer.class));
        assertNull(jdbc.queryForObject("select total_amount from fq_quote where id=970041", BigDecimal.class));
        assertEquals(19, jdbc.queryForObject("""
                select count(*) from information_schema.tables
                 where table_schema=database() and left(table_name,3)='fq_'
                """, Integer.class));
    }

    @Test
    void deliveryTasksUseConfirmedFrozenSnapshotLeaseFencingAndRetentionWithoutNewTables() {
        ObjectMapper mapper = new ObjectMapper();
        FashionJsonCodec json = new FashionJsonCodec(mapper);
        JdbcFashionDeliveryRepository deliveries = new JdbcFashionDeliveryRepository(
                new NamedParameterJdbcTemplate(dataSource), json, mapper);
        JdbcFashionOperationsRepository operations = new JdbcFashionOperationsRepository(
                new NamedParameterJdbcTemplate(dataSource));
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String hash = "9".repeat(64);

        jdbc.update("""
                insert into fq_customer (id,code,name,customer_type,salesperson_id,collaborator_ids,status,
                    create_by,create_time,update_by,update_time,row_version)
                values (990010,'IMP09-CUSTOMER','IMP09 客户','group_purchase',1,json_array(),'active',1,?,1,?,1)
                """, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into fq_product (id,source_code,sku_code,style_code,name,category_code,color_code,
                    color_name,size_code,size_system,unit,tags_json,images_json,visual_version,
                    attributes_confirmed,status,create_by,create_time,update_by,update_time,row_version)
                values (990020,'IMP09','SKU-IMP09','STYLE-IMP09','IMP09 商品','TOP','BLACK','黑色','M','LETTER',
                    '件',json_array(),json_array(),1,1,'active',1,?,1,?,1)
                """, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into fq_quote (id,quote_no,version_no,customer_id,customer_name,title,salesperson_id,
                    requirement_json,requirement_confirmed,requested_qty,budget_basis,quote_mode,progressive,
                    combo_template_json,warehouse_code,currency,tax_mode,fee_taxable,discount_type,discount_rate,
                    fixed_discount,freight,subtotal,discount_amount,tax_amount,total_amount,valid_days,valid_until,
                    public_note,presentation_json,content_hash,confirmed_by,confirmed_at,status,
                    create_by,create_time,update_by,update_time,row_version)
                values (990040,'Q-IMP09',1,990010,'IMP09 客户','冻结报价交付',1,
                    json_object('scene','团购交付'),1,100,'total','combined',1,json_object(),'MAIN','CNY','included',
                    1,'percent',5.00,0,300.00,8000.00,400.00,0,7900.00,7,?,'仅用于交付验证',json_object(),?,
                    1,?,'confirmed',1,?,1,?,1)
                """, Timestamp.from(now.plus(7, ChronoUnit.DAYS)), hash, Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into fq_quote_combo (id,quote_id,combo_no,name,category_count,set_qty,selected,sort_no,
                    visual_hash,allocation_confirmed,allocation_confirmed_by,allocation_confirmed_at,
                    subtotal,discount_amount,freight,tax_amount,total_amount,
                    create_by,create_time,update_by,update_time,row_version)
                values (990050,990040,'C-IMP09','交付组合',1,100,1,1,?,1,1,?,8000.00,400.00,300.00,0,7900.00,
                    1,?,1,?,1)
                """, hash, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into fq_quote_detail (id,combo_id,line_no,slot_code,product_id,source_code,sku_code,
                    style_code,product_name,category_code,color_code,color_name,size_code,size_system,unit,qty,
                    source_price,quote_price,amount,stock_qty,stock_as_of,remark,
                    create_by,create_time,update_by,update_time,row_version)
                values (990060,990050,1,'TOP',990020,'IMP09','SKU-IMP09','STYLE-IMP09','IMP09 商品','TOP',
                    'BLACK','黑色','M','LETTER','件',100,80.00,80.00,8000.00,130,?,'确认版本冻结明细',
                    1,?,1,?,1)
                """, Timestamp.from(now.minusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));

        DeliveryQuoteSnapshot snapshot = deliveries.findConfirmedSnapshot(990040).orElseThrow();
        assertEquals("Q-IMP09", snapshot.quoteNo());
        assertEquals(new BigDecimal("7900.00"), snapshot.totalAmount());
        assertEquals(new BigDecimal("80.00"), snapshot.combos().get(0).lines().get(0).sourcePrice());
        assertEquals(new BigDecimal("80.00"), snapshot.combos().get(0).lines().get(0).quotePrice());

        DeliveryFileTask queued = new DeliveryFileTask(990001, 990040, snapshot.quoteNo(), snapshot.versionNo(),
                snapshot.title(), "pptx", "customer", snapshot.contentHash(), "fashion-delivery-1.0",
                "imp09-delivery-request-000000000000000000000000000000000000000000000000000000000001",
                "queued", List.of(), 0, null, null, null, null, 0, 1, now, 1);
        assertTrue(deliveries.insert(queued));
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> deliveries.insert(
                new DeliveryFileTask(990002, 990040, snapshot.quoteNo(), snapshot.versionNo(), snapshot.title(),
                        "pptx", "customer", snapshot.contentHash(), "fashion-delivery-1.0", queued.requestKey(),
                        "queued", List.of(), 0, null, null, null, null, 0, 1, now, 1)));

        DeliveryFileTask claimed = transaction.execute(status -> deliveries.claimNext(
                now.plusMillis(1), now.plusSeconds(120)).orElseThrow());
        assertEquals("running", claimed.status());
        assertEquals(2L, claimed.rowVersion());
        DeliveryArtifact artifact = new DeliveryArtifact("Q-IMP09_V1_客户报价.pptx",
                "delivery/990040/frozen.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "c".repeat(64), 4096, 8, "customer-presentation", List.of("SKU-IMP09"),
                "frozen-quote", "confirmed", null);
        Boolean completed = transaction.execute(status -> deliveries.complete(990001, claimed.rowVersion(),
                List.of(artifact), 1, now.plusSeconds(2)));
        assertEquals(Boolean.TRUE, completed);
        assertTrue(!deliveries.complete(990001, claimed.rowVersion(), List.of(artifact), 1, now.plusSeconds(3)));
        assertEquals("success", deliveries.findById(990001).orElseThrow().status());
        assertTrue(deliveries.markDownloaded(990001, 3, 1, now.plusSeconds(4)));
        assertTrue(deliveries.markDownloaded(990001, 3, 1, now.plusSeconds(5)));
        assertEquals(2, deliveries.findById(990001).orElseThrow().downloadCount());
        DeliveryFileTask downloaded = deliveries.findById(990001).orElseThrow();
        assertTrue(deliveries.extendRetention(990001, downloaded.rowVersion(), now.plus(400, ChronoUnit.DAYS),
                1, now.plusSeconds(6)));
        assertEquals(now.plus(400, ChronoUnit.DAYS),
                deliveries.findById(990001).orElseThrow().artifacts().get(0).retainUntil());

        DeliveryFileTask retry = new DeliveryFileTask(990003, 990040, snapshot.quoteNo(), snapshot.versionNo(),
                snapshot.title(), "csv", "customer", snapshot.contentHash(), "fashion-delivery-1.0",
                "imp09-delivery-request-000000000000000000000000000000000000000000000000000000000003",
                "queued", List.of(), 0, null, null, null, null, 0, 1, now, 1);
        deliveries.insert(retry);
        DeliveryFileTask retryClaim = transaction.execute(status -> deliveries.claimNext(
                now.plusSeconds(10), now.plusSeconds(130)).orElseThrow());
        assertEquals(990003L, retryClaim.id());
        assertTrue(deliveries.fail(990003, retryClaim.rowVersion(), 1, now.plusSeconds(15),
                "对象存储暂时不可用", 0, now.plusSeconds(11)));
        assertTrue(deliveries.claimNext(now.plusSeconds(14), now.plusSeconds(134)).isEmpty());
        DeliveryFileTask reclaimed = transaction.execute(status -> deliveries.claimNext(
                now.plusSeconds(16), now.plusSeconds(136)).orElseThrow());
        assertEquals(990003L, reclaimed.id());
        assertEquals("running", reclaimed.status());

        assertTrue(operations.retentionCandidates(now.plusSeconds(20), now.minus(365, ChronoUnit.DAYS),
                now.minus(90, ChronoUnit.DAYS), now.minus(30, ChronoUnit.DAYS)).stream()
                .anyMatch(item -> "990001".equals(item.recordId()) && "extended".equals(item.decision())));
        assertEquals(19, jdbc.queryForObject("""
                select count(*) from information_schema.tables
                 where table_schema=database() and left(table_name,3)='fq_'
                """, Integer.class));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("并发测试启动超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(exception);
        }
    }

    private static byte[] productCsv(String sku, String name, String category) {
        return ("来源编码,SKU编码,款号,商品名称,品类编码,颜色编码,颜色名称,尺码,尺码制式,单位,品牌,材质,季节,京东商品ID,京东链接\n"
                + "MANUAL," + sku + ",STYLE-X," + name + "," + category
                + ",BLACK,黑色,M,LETTER,件,品牌,棉,四季,,\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] priceCsv(Instant asOf, String... rows) {
        StringBuilder csv = new StringBuilder("来源编码,SKU编码,销售单价,币种,含税口径,业务时间\n");
        for (String row : rows) csv.append("IMP04,").append(row).append(',').append(asOf).append('\n');
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] stockCsv(Instant asOf, String... rows) {
        StringBuilder csv = new StringBuilder("来源编码,SKU编码,仓库编码,可售数量,业务时间\n");
        for (String row : rows) {
            String[] fields = row.split(",", -1);
            csv.append("IMP04,").append(fields[0]).append(",MAIN,").append(fields[1])
                    .append(',').append(asOf).append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static ResourceDatabasePopulator populator(String name) {
        ResourceDatabasePopulator result = new ResourceDatabasePopulator();
        result.setSqlScriptEncoding("UTF-8");
        result.addScript(new FileSystemResource(workspacePath("ruoyi-backend", "sql", name)));
        return result;
    }

    private static Path workspacePath(String... parts) {
        Path current = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && current != null; up++, current = current.getParent()) {
            Path candidate = current;
            for (String part : parts) {
                candidate = candidate.resolve(part);
            }
            if (Files.exists(candidate)) {
                return candidate.normalize();
            }
        }
        throw new IllegalStateException("无法定位 workspace 文件 " + String.join("/", parts));
    }
}
