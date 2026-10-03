package com.ruoyi.aipicture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import com.ruoyi.aipicture.persistence.AiPictureTransactions;

class DatabaseRetryTest {
    PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    TransactionTemplate template() {when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());return new TransactionTemplate(manager);}
    CannotAcquireLockException failure(int code) {return new CannotAcquireLockException("受控数据库错误",new SQLException("受控事务失败","40001",code));}
    @Test void mysql1213RollsBackBeforeRetryAndCommitsOnlyOnce() {
        var calls=new AtomicInteger();int result=AiPictureTransactions.execute(template(),status->{int call=calls.incrementAndGet();if(call<3) throw failure(1213);return call;});assertEquals(3,result);verify(manager,times(2)).rollback(any());verify(manager,times(1)).commit(any());
    }
    @Test void retryBudgetIsThreeTransactions() {
        var calls=new AtomicInteger();assertThrows(CannotAcquireLockException.class,()->AiPictureTransactions.execute(template(),status->{calls.incrementAndGet();throw failure(1213);}));assertEquals(3,calls.get());verify(manager,times(3)).rollback(any());verify(manager,never()).commit(any());
    }
    @Test void lockTimeoutAndOtherDatabaseFailuresAreNotRetried() {
        var calls=new AtomicInteger();assertThrows(CannotAcquireLockException.class,()->AiPictureTransactions.execute(template(),status->{calls.incrementAndGet();throw failure(1205);}));assertEquals(1,calls.get());verify(manager,times(1)).rollback(any());
    }
    @Test void nestedTransactionDoesNotRetryInsideRollbackOnlyOuterTransaction() {
        var calls=new AtomicInteger();TransactionSynchronizationManager.setActualTransactionActive(true);
        try {assertThrows(CannotAcquireLockException.class,()->AiPictureTransactions.execute(template(),status->{calls.incrementAndGet();throw failure(1213);}));assertEquals(1,calls.get());} finally {TransactionSynchronizationManager.clear();}
    }
    @Test void alreadyCommittedAfterCommitFailureNeverReplaysTransaction() {
        var calls=new AtomicInteger();var committedTemplate=new TransactionTemplate() {
            @Override public <T> T execute(TransactionCallback<T> work) {
                TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
                try {T result=work.doInTransaction(new SimpleTransactionStatus());for(var listener:TransactionSynchronizationManager.getSynchronizations()) listener.afterCommit();return result;} finally {TransactionSynchronizationManager.clear();}
            }
        };
        assertThrows(CannotAcquireLockException.class,()->AiPictureTransactions.execute(committedTemplate,status->{calls.incrementAndGet();TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {@Override public void afterCommit() {throw failure(1213);}});return null;}));assertEquals(1,calls.get());
    }
}
