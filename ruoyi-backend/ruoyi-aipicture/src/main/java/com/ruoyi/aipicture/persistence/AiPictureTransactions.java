package com.ruoyi.aipicture.persistence;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.*;

/** 仅用于纯 MySQL 短事务；不得包裹 Provider、HTTP 计算或产物文件写入。 */
public final class AiPictureTransactions {
    private AiPictureTransactions() {}
    public static <T> T execute(TransactionTemplate tx,TransactionCallback<T> work) {
        boolean nested=TransactionSynchronizationManager.isActualTransactionActive();
        for(int attempt=1;;attempt++) {
            var committed=new AtomicBoolean(false);
            try {
                return tx.execute(status->{
                    // afterCommit 的计划续步失败不能重放已经成功提交的回执。
                    if(status.isNewTransaction()&&TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {@Override public void afterCommit() {committed.set(true);}});
                    return work.doInTransaction(status);
                });
            } catch(DataAccessException failure) {
                // 嵌套事务交由最外层回滚并重试；仅 MySQL 明确回滚整个事务的 1213 死锁。
                if(nested||committed.get()||attempt>=3||!mysqlDeadlock(failure)) throw failure;
            }
        }
    }
    private static boolean mysqlDeadlock(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) if(cause instanceof SQLException sql&&sql.getErrorCode()==1213) return true;
        return false;
    }
}
