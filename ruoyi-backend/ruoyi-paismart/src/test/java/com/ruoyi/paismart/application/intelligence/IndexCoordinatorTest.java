package com.ruoyi.paismart.application.intelligence;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class IndexCoordinatorTest {
    private JdbcTemplate db;private IndexCoordinator service;
    @BeforeEach void prepare(){var ds=new DriverManagerDataSource("jdbc:h2:mem:fence_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");db=new JdbcTemplate(ds);db.execute("CREATE TABLE ps_intelligence_lock(id VARCHAR(50) PRIMARY KEY,owner VARCHAR(100),fence_revision BIGINT DEFAULT 0,lease_until TIMESTAMP)");db.update("INSERT INTO ps_intelligence_lock(id) VALUES('KNOWLEDGE_INDEX')");service=new IndexCoordinator(db,new TransactionTemplate(new JdbcTransactionManager(ds)));}
    @Test void activeOwnerPreventsConcurrentIndexAndDocumentWrites(){var fence=service.acquire("first-job:first-lease");assertThrows(IllegalStateException.class,()->service.acquire("second-job:lease"));service.verify(fence);service.release(fence);assertDoesNotThrow(()->service.acquire("second-job:lease"));}
    @Test void expiredLeaseCannotOverwriteNewOwnerOrReleaseNewFence(){var old=service.acquire("old");db.update("UPDATE ps_intelligence_lock SET lease_until=DATEADD('SECOND',-1,CURRENT_TIMESTAMP)");var current=service.acquire("new");assertThrows(IllegalStateException.class,()->service.verify(old));assertThrows(IllegalStateException.class,()->service.heartbeat(old));service.release(old);assertDoesNotThrow(()->service.verify(current));assertTrue(current.revision()>old.revision());}
}
