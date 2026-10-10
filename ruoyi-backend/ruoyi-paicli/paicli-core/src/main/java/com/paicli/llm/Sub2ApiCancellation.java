package com.paicli.llm;

import com.paicli.runtime.CancellationContext;
import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 捕获请求所属取消令牌，关闭当前client的请求，不影响其他供应商/任务。 */
final class Sub2ApiCancellation implements AutoCloseable {
    private static final ScheduledThreadPoolExecutor WATCHER=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"paicli-sub2api-cancel");t.setDaemon(true);return t;});
    static { WATCHER.setRemoveOnCancelPolicy(true); }
    private final ScheduledFuture<?> future;
    Sub2ApiCancellation(Runnable cancel)throws IOException{
        var token=CancellationContext.current();Thread owner=Thread.currentThread();
        if(owner.isInterrupted()||token!=null&&token.isCancelled())throw new IOException("Sub2API 请求已取消");
        future=WATCHER.scheduleWithFixedDelay(()->{if(owner.isInterrupted()||token!=null&&token.isCancelled())cancel.run();},50,50,TimeUnit.MILLISECONDS);
    }
    public void close(){future.cancel(false);}
}
