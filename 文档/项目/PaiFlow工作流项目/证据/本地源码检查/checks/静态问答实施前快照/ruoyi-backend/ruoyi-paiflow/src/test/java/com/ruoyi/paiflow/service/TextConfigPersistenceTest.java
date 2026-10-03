package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.mapper.TextConfigMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际SQL/事务与原种子；H2只证明定向边界，不证明完整MySQL8中文排序规则。 */
class TextConfigPersistenceTest {
    private JdbcDataSource source;private TextConfigMapper mapper;private TextConfigService service;
    private ResourceAccessService access;private IdentityMappingService identities;
    private final AtomicLong actor=new AtomicLong(42);private final AtomicBoolean authorized=new AtomicBoolean(true);
    @BeforeEach void setup() throws Exception {
        source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        String ddl=Files.readString(Path.of("sql/008-paiflow-text-config.sql"),StandardCharsets.UTF_8)
            .replaceAll("(?m)^--.*$","")
            .replace(" ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci","")
            .replace("`separator` VARCHAR(255)","`separator` VARCHAR_IGNORECASE(255)");
        var matches=Pattern.compile("CONVERT\\(0x([0-9A-F]+) USING utf8mb4\\)").matcher(ddl);
        StringBuilder converted=new StringBuilder();while(matches.find())matches.appendReplacement(converted,java.util.regex.Matcher.quoteReplacement("'"+new String(HexFormat.of().parseHex(matches.group(1)),StandardCharsets.UTF_8).replace("'","''")+"'"));matches.appendTail(converted);
        try(var connection=source.getConnection();var sql=connection.createStatement()) {
            sql.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.ruoyi.paiflow.service.WorkflowPersistenceTest.utcNow'");
            for(String statement:converted.toString().split(";"))if(!statement.isBlank())sql.execute(statement);
        }
        var configuration=new Configuration(new Environment("isolated-text-config",new SpringManagedTransactionFactory(),source));
        String file="mapper/paiflow/TextConfigMapper.xml";try(InputStream input=getClass().getClassLoader().getResourceAsStream(file)) {
            assertNotNull(input);new XMLMapperBuilder(input,configuration,file,configuration.getSqlFragments()).parse();
        }
        mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration)).getMapper(TextConfigMapper.class);
        access=mock(ResourceAccessService.class);identities=mock(IdentityMappingService.class);
        when(access.requirePermission(anyString())).thenAnswer(invocation->{if(!authorized.get())throw new ServiceException("fixture permission revoked",403);return actor.get();});
        when(identities.current()).thenAnswer(invocation->{var user=new SysUser();user.setUserId(actor.get());user.setUserName("fixture-"+actor.get());return new LoginUser(actor.get(),8L,user,Set.of());});
        service=proxy(mapper);
    }
    private TextConfigService proxy(TextConfigMapper records) {
        var factory=new ProxyFactory(new TextConfigService(records,access,identities));factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));return (TextConfigService)factory.getProxy();
    }
    private static JSONObject body(Object... pairs) { var value=new JSONObject();for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);return value; }
    private TextConfigService.View create(String value) { return service.create(body("separator",value)); }
    @Test void fiveOriginalSeedsKeepIdsCommentsAndLiteralWhitespaceAndCannotBecomeUserRecords() throws Exception {
        var list=service.list();assertEquals(5,list.size());
        String[] values={"。","；"," ","\t","\n"},comments={"句号","分号","空格","制表符","换行"};
        for(int i=0;i<5;i++) { var row=mapper.find(i+1);assertEquals(values[i],row.getSeparator());assertEquals(comments[i],row.getComment());assertEquals(0L,row.getOwnerUserId());assertEquals(-1L,list.stream().filter(v->v.id().equals(row.getId())).findFirst().orElseThrow().uid()); }
        assertNotEquals("\\n",mapper.find(5).getSeparator());assertEquals(1,mapper.find(5).getSeparator().length());
        String reference=Files.readString(Path.of("../../参考项目/PaiFlow-main/docker/PaiFlow/mysql/schema.sql"),StandardCharsets.UTF_8);
        assertTrue(reference.contains("(4,'-1','\t','制表符'"));assertTrue(reference.contains("(5,'-1','\\n','换行'"));
        // 原SQL默认转义后的LF与用户提交的两个字符必须区分；保存不做全局替换。
        var literal=create("\\n");assertEquals("\\n",literal.separator());assertEquals(2,literal.separator().length());
        assertEquals(409,assertThrows(ServiceException.class,()->create("\n")).getCode());
    }
    @Test void originalCreateIgnoresForgedIdentityAndUsesSeparatorAsCommentWithoutTrimming() {
        var row=service.create(body("separator"," x ","comment","forged","uid","-1","ownerUserId",0,"id",2,"revision",99,"deleted",true));
        assertTrue(row.id()>5);assertEquals(" x ",row.separator());assertEquals(" x ",row.comment());assertEquals("ruoyi:42",row.uid());assertFalse(row.deleted());assertEquals(42L,mapper.find(row.id()).getOwnerUserId());assertEquals(0L,row.revision());
    }
    @Test void ownAndSystemDuplicatesRejectButAnotherUsersIdenticalPreferenceIsIndependent() {
        create("Custom");assertEquals(409,assertThrows(ServiceException.class,()->create("custom")).getCode());assertEquals(409,assertThrows(ServiceException.class,()->create(" ")).getCode());
        actor.set(43);create("Custom");assertEquals(6,service.list().size());assertEquals(1,service.list().stream().filter(v->"ruoyi:43".equals(v.uid())).count());
        actor.set(42);assertEquals(6,service.list().size());assertFalse(service.list().stream().anyMatch(v->"ruoyi:43".equals(v.uid())));
    }
    @Test void foreignAndSystemIdsCannotBeEditedOrDeletedEvenWhenRequestForcesUidOwnerOrDeleted() {
        var mine=create("my preference");actor.set(43);
        assertEquals(404,assertThrows(ServiceException.class,()->service.update(mine.id(),body("separator","stolen","uid","ruoyi:42","ownerUserId",43))).getCode());assertEquals(404,assertThrows(ServiceException.class,()->service.delete(mine.id(),null)).getCode());
        for(int id=1;id<=5;id++) { long value=id;assertEquals(403,assertThrows(ServiceException.class,()->service.update(value,body("separator","replaced"))).getCode());assertEquals(403,assertThrows(ServiceException.class,()->service.delete(value,null)).getCode()); }
        assertEquals("my preference",mapper.find(mine.id()).getSeparator());assertEquals(0L,mapper.find(mine.id()).getRevision());
    }
    @Test void updateUsesOwnedRowAndOptionalExplicitRevisionNeverOverwritesAConflict() {
        var row=create("before");var updated=service.update(row.id(),body("id",row.id().toString(),"separator","after","comment","说明","revision",0,"uid","-1","ownerUserId",0));
        assertEquals(1,updated.revision());assertEquals("after",updated.separator());assertEquals("说明",updated.comment());assertEquals("ruoyi:42",updated.uid());
        assertEquals(409,assertThrows(ServiceException.class,()->service.update(row.id(),body("separator","stale","revision",0))).getCode());assertEquals(409,assertThrows(ServiceException.class,()->service.delete(row.id(),0L)).getCode());
        assertEquals(400,assertThrows(ServiceException.class,()->service.update(row.id(),body("id",row.id()+1,"separator","wrong id"))).getCode());assertEquals("after",mapper.find(row.id()).getSeparator());
    }
    @Test void deleteRemovesOnlyDirectoryAndDoesNotRewriteSavedCanvasOrPreventReadding() {
        var row=create("--literal--");var jdbc=new JdbcTemplate(source);jdbc.execute("CREATE TABLE fixture_canvas(id BIGINT PRIMARY KEY,data CLOB)");String canvas="{\"nodeParam\":{\"separator\":\"--literal--\"}}";jdbc.update("INSERT INTO fixture_canvas VALUES(1,?)",canvas);
        service.delete(row.id(),null);assertNull(mapper.find(row.id()));assertEquals(canvas,jdbc.queryForObject("SELECT data FROM fixture_canvas WHERE id=1",String.class));assertEquals(5,service.list().size());assertNotNull(create("--literal--").id());
    }
    @Test void duplicateUpdateAndSqlFailurePreserveOriginalRowAndRevision() {
        var first=create("first");create("taken");assertEquals(409,assertThrows(ServiceException.class,()->service.update(first.id(),body("separator","taken","revision",0))).getCode());
        new JdbcTemplate(source).execute("ALTER TABLE paiflow_text_config ADD CONSTRAINT fixture_comment CHECK(`comment` IS NULL OR `comment`<>'reject')");
        var failure=assertThrows(RuntimeException.class,()->service.update(first.id(),body("separator","changed","comment","reject","revision",0)));
        StringBuilder causes=new StringBuilder();for(Throwable cause=failure;cause!=null;cause=cause.getCause())causes.append(cause.getMessage()).append('\n');
        assertTrue(causes.toString().toLowerCase(java.util.Locale.ROOT).contains("fixture_comment"),causes.toString());
        assertEquals("first",mapper.find(first.id()).getSeparator());assertEquals(0L,mapper.find(first.id()).getRevision());
    }
    @Test void concurrentIdenticalCreatesUseRealUniqueConstraintAndExactlyOnePersists() throws Exception {
        var barrier=new CyclicBarrier(2);var racing=mock(TextConfigMapper.class,org.mockito.AdditionalAnswers.delegatesTo(mapper));
        doAnswer(invocation->{long count=mapper.countSeparator(invocation.getArgument(0),invocation.getArgument(1),invocation.getArgument(2));barrier.await(5,TimeUnit.SECONDS);return count;}).when(racing).countSeparator(anyLong(),eq("race"),isNull());
        var writer=proxy(racing);var pool=Executors.newFixedThreadPool(2);
        try { Callable<Integer> save=()->{try { writer.create(body("separator","race"));return 0; }catch(ServiceException ex) { return ex.getCode(); }};var a=pool.submit(save);var b=pool.submit(save);var results=java.util.List.of(a.get(8,TimeUnit.SECONDS),b.get(8,TimeUnit.SECONDS));assertTrue(results.contains(0));assertTrue(results.contains(409));assertEquals(1,mapper.countSeparator(42,"race",null));assertEquals(6,service.list().size()); }
        finally { pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    @Test void waitingForOwnedRowLockRechecksPermissionBeforeWriting() throws Exception {
        var row=create("unchanged");var reached=new CountDownLatch(1);var watched=mock(TextConfigMapper.class,org.mockito.AdditionalAnswers.delegatesTo(mapper));
        doAnswer(invocation->{reached.countDown();return mapper.lockOwned(invocation.getArgument(0),invocation.getArgument(1));}).when(watched).lockOwned(eq(row.id()),eq(42L));var writer=proxy(watched);var pool=Executors.newSingleThreadExecutor();
        try(var blocker=source.getConnection()) { blocker.setAutoCommit(false);try(var statement=blocker.prepareStatement("SELECT id FROM paiflow_text_config WHERE id=? FOR UPDATE")) { statement.setLong(1,row.id());assertTrue(statement.executeQuery().next()); }
            var future=pool.submit(()->assertThrows(ServiceException.class,()->writer.update(row.id(),body("separator","must not write"))));assertTrue(reached.await(5,TimeUnit.SECONDS));authorized.set(false);blocker.commit();assertEquals(403,future.get(5,TimeUnit.SECONDS).getCode());assertEquals("unchanged",mapper.find(row.id()).getSeparator());assertEquals(0L,mapper.find(row.id()).getRevision());
        } finally { pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    @Test void malformedOrOversizedValuesAreRejectedAndSqlTextRemainsBoundData() {
        for(Object bad:new Object[]{"",null,7,true,"x".repeat(256),"\uD800"})assertEquals(400,assertThrows(ServiceException.class,()->service.create(body("separator",bad))).getCode());
        assertEquals(413,assertThrows(ServiceException.class,()->service.create(body("separator","safe","unused","x".repeat(20_000)))).getCode());
        var row=create("x' OR 1=1 --");assertEquals("x' OR 1=1 --",mapper.find(row.id()).getSeparator());assertEquals(6,service.list().size());
        authorized.set(false);assertEquals(403,assertThrows(ServiceException.class,()->create("denied")).getCode());assertEquals(6,mapper.list(42).size());
    }
}
