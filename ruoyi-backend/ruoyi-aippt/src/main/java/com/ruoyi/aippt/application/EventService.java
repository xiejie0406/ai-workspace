package com.ruoyi.aippt.application;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.aippt.configuration.AipptEnabled;
import com.ruoyi.aippt.domain.AipptException;
import com.ruoyi.aippt.infrastructure.persistence.AipptRepository;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
/** 有界持久重放与快照；Redis促发DB读取，每秒轮询始终保留，事务外发送。 */
@Service @AipptEnabled public class EventService  {
    private final AipptService service;
    private final AipptRepository db;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, runnable ->  {
        Thread thread = new Thread(runnable, "aippt-events");
        thread.setDaemon(true);
        return thread;
    }
    );
    private final AtomicInteger connections = new AtomicInteger();
    private RedisDeliveryService delivery;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setDelivery(RedisDeliveryService value) { delivery=value; }
    public EventService(AipptService service, AipptRepository db)  {
        this.service=service;
        this.db=db;
    }
    public SseEmitter subscribe(String project, String taskId, boolean deck, long owner, long after, BooleanSupplier sessionValid)  {
        Runnable authorize=()-> {
            if(!sessionValid.getAsBoolean())throw new AipptException(401,"SESSION_EXPIRED","会话已到期或身份已变化");
        };
        service.getProject(project, owner);
        if (taskId != null) service.task(taskId, owner);
        long latest = db.lastSequence(project), first = db.firstSequence(project);
        if (after < 0 || after > latest) throw AipptException.invalid("事件游标无效");
        if (connections.incrementAndGet() > 100)  {
            connections.decrementAndGet();
            throw new AipptException(503,"STREAM_LIMIT","进度订阅已达容量上限");
        }
        SseEmitter emitter = newEmitter(16*60*1000L);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        AtomicReference<AutoCloseable> noticeListener = new AtomicReference<>();
        Runnable close = () ->  {
            if (closed.compareAndSet(false,true))  {
                connections.decrementAndGet();
                ScheduledFuture<?> job=future.get();
                if(job!=null)job.cancel(false);
                AutoCloseable listener=noticeListener.get();
                if(listener!=null)try { listener.close(); } catch(Exception ignored) { }
            }
        }
        ;
        emitter.onCompletion(close);
        emitter.onTimeout(() ->  {
            close.run();
            emitter.complete();
        }
        );
        emitter.onError(error -> close.run());
        AtomicLong cursor=new AtomicLong(after);
        AtomicLong lastHeartbeat=new AtomicLong(System.nanoTime());
        AtomicReference<JSONObject> lastDelivery=new AtomicReference<>(deliveryStatus());
        AtomicBoolean reading=new AtomicBoolean();
        AtomicBoolean queued=new AtomicBoolean();
        try  {
            authorize.run();
            if (after>0 && ((first==0&&after<latest) || after<first-1 || latest-after>500))  {
                JSONObject frame=JSONObject.of("eventSeq",latest,"type","resync_required","projectId",project, "state","resync_required","data",JSONObject.of("reason","事件游标超过保留范围，请读取当前快照"));
                if(taskId!=null)frame.put("taskId",taskId);
                send(emitter,frame,authorize);
                cursor.set(latest);
            }
            else if (after>0)  {
                for(JSONObject event:service.events(project,owner,after))  {
                    cursor.set(event.getLongValue("eventSeq"));
                    replay(emitter,event,project,taskId,deck,authorize);
                }
            }
            sendSnapshot(emitter,project,taskId,deck,owner,latest,authorize);
            cursor.set(latest);
            if(terminal(project,taskId,deck,owner,authorize))  {
                emitter.complete();
                close.run();
                return emitter;
            }
        }
        catch(IOException|RuntimeException error)  {
            close.run();
            emitter.completeWithError(error);
            return emitter;
        }
        Runnable poll=() -> {
            if(closed.get()||!reading.compareAndSet(false,true))return;
            try  {
                authorize.run();
                service.getProject(project,owner);
                JSONObject state=deliveryStatus();
                if(!state.equals(lastDelivery.get())) {
                    JSONObject frame=JSONObject.of("eventSeq",cursor.get(),"type","delivery","projectId",project,"data",new JSONObject());
                    if(taskId!=null)frame.put("taskId",taskId);
                    send(emitter,frame,authorize);
                    lastDelivery.set(state);
                }
                for(JSONObject event:service.events(project,owner,cursor.get()))  {
                    cursor.set(event.getLongValue("eventSeq"));
                    replay(emitter,event,project,taskId,deck,authorize);
                }
                long now=System.nanoTime();
                if(now-lastHeartbeat.get()>=TimeUnit.SECONDS.toNanos(15)) {
                    authorize.run();
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastHeartbeat.set(now);
                }
                if(terminal(project,taskId,deck,owner,authorize))  {
                    close.run();
                    emitter.complete();
                }
            }
            catch(IOException|RuntimeException error)  {
                close.run();
                emitter.completeWithError(error);
            }
            finally { reading.set(false); }
        };
        try {
            future.set(executor.scheduleAtFixedRate(poll,1,1,TimeUnit.SECONDS));
            if(delivery!=null)noticeListener.set(delivery.listen(project,()->{
                if(closed.get()||!queued.compareAndSet(false,true))return;
                try { executor.execute(()->{queued.set(false);poll.run();}); }
                catch(RejectedExecutionException stopped) { queued.set(false); }
            }));
            if(closed.get()&&noticeListener.get()!=null)try { noticeListener.get().close(); } catch(Exception ignored) { }
            if(closed.get())future.get().cancel(false);
        } catch(RuntimeException unavailable) {
            close.run();
            emitter.completeWithError(unavailable);
        }
        return emitter;
    }
    private void replay(SseEmitter emitter,JSONObject event,String project,String taskId,boolean deck,Runnable authorize)throws IOException  {
        if(taskId!=null&&!taskId.equals(event.getString("taskId")))return;
        authorize.run();
        JSONObject data;
        String state;
        if(taskId!=null)  {
            data=event.getJSONObject("task_data");
            state=event.getString("task_state");
        }
        else if(deck)  {
            data=JSONObject.of("project_id",project,"status",event.getString("deck_state"), "ready",event.getLongValue("completed"),"total",event.getLongValue("total"),"event",event.getJSONObject("data"));
            state=event.getString("deck_state");
        }
        else  {
            data=event.getJSONObject("data");
            state=event.getString("task_state");
            if(state==null)state="changed";
        }
        JSONObject frame=JSONObject.of("eventSeq",event.getLongValue("eventSeq"),"type",event.getString("type"), "projectId",project,"state",state,"data",data==null?new JSONObject():data);
        if(taskId!=null)frame.put("taskId",taskId);
        if(deck)  {
            frame.put("completed",event.getLongValue("completed"));
            frame.put("total",event.getLongValue("total"));
        }
        send(emitter,frame,authorize);
    }
    private boolean terminal(String project,String taskId,boolean deck,long owner,Runnable authorize)  {
        authorize.run();
        if(taskId==null&&!deck&&db.children("task",project).stream().anyMatch(task -> "generate_outline".equals(task.getString("type"))&&Set.of("queued","running").contains(task.getString("status"))))return false;
        String state=taskId==null ? deck?service.deck(project,owner).getString("status"):service.outline(project,owner).getString("status") : service.task(taskId,owner).getString("status");
        return taskId==null ? !"generating".equals(state) : Set.of("succeeded","failed","cancelled").contains(state);
    }
    private void sendSnapshot(SseEmitter emitter,String project,String taskId,boolean deck,long owner,long seq,Runnable authorize)throws IOException  {
        authorize.run();
        JSONObject data=taskId!=null?service.task(taskId,owner):deck?service.deck(project,owner):service.outline(project,owner);
        if(taskId==null&&!deck&&db.children("task",project).stream().anyMatch(task -> "generate_outline".equals(task.getString("type"))&&Set.of("queued","running").contains(task.getString("status"))))data.put("status","generating");
        JSONObject frame=JSONObject.of("eventSeq",seq,"type","snapshot","projectId",project,"state",data.getString("status"),"data",data);
        if(taskId!=null)frame.put("taskId",taskId);
        if(deck)  {
            frame.put("completed",data.getLongValue("ready"));
            frame.put("total",data.getLongValue("total"));
        }
        send(emitter,frame,authorize);
    }
    private void send(SseEmitter emitter,JSONObject frame,Runnable authorize)throws IOException  {
        authorize.run();
        frame.put("delivery",deliveryStatus());
        emitter.send(SseEmitter.event().id(frame.getString("eventSeq")).name(frame.getString("type")).data(frame));
    }
    SseEmitter newEmitter(long timeout) { return new SseEmitter(timeout); }
    private JSONObject deliveryStatus() {
        return delivery==null?JSONObject.of("status","disabled","reason","REDIS_DISABLED"):delivery.status();
    }
    @PreDestroy public void stop()  {
        executor.shutdownNow();
    }
}
