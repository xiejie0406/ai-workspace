package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.paiflow.service.IdentityMappingService;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 流连接只有有界读取上下文；账号/session和运行状态始终来自既有中心。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyDebugStreamService {
    private final LegacyDebugBridgeService bridge;
    private final TokenService tokens;
    private final IdentityMappingService identities;
    private final ScheduledExecutorService scheduler;
    private final ThreadPoolTaskExecutor io;
    private final Semaphore capacity=new Semaphore(64);
    private final ConcurrentHashMap<Long,Integer> actors=new ConcurrentHashMap<>();
    public LegacyDebugStreamService(LegacyDebugBridgeService bridge,TokenService tokens,IdentityMappingService identities,
            @Qualifier("scheduledExecutorService") ScheduledExecutorService scheduler,
            @Qualifier("threadPoolTaskExecutor") ThreadPoolTaskExecutor io) {
        this.bridge=bridge;this.tokens=tokens;this.identities=identities;this.scheduler=scheduler;this.io=io;
    }
    /** token不进入DTO、日志或record的自动toString。 */
    public static final class Session {
        private final String bearer,sessionId;private final long actor;
        private Session(String bearer,String sessionId,long actor){this.bearer=bearer;this.sessionId=sessionId;this.actor=actor;}
        @Override public String toString(){return "PaiFlow调试流会话";}
    }
    public Session session(String bearer) {
        var authenticated=identities.current();var login=tokens.getLoginUserByToken(bearer);
        if(!valid(login) || !Objects.equals(authenticated.getUserId(),login.getUserId())
                || !Objects.equals(authenticated.getToken(),login.getToken()))throw new ServiceException("调试流登录会话无效",401);
        return new Session(bearer,login.getToken(),login.getUserId());
    }
    private static boolean valid(LoginUser user) {
        return user!=null && user.getUserId()!=null && user.getToken()!=null && !user.getToken().isBlank()
                && user.getExpireTime()!=null && user.getExpireTime()>System.currentTimeMillis();
    }
    /** 每批次从原JWT/Redis重核；新请求作用域仅供原PermissionContextHolder写本次权限标签。 */
    public <T>T withSession(Session session,Callable<T> work) throws Exception {
        var login=tokens.getLoginUserByToken(session.bearer);
        if(!valid(login) || login.getUserId()!=session.actor || !Objects.equals(login.getToken(),session.sessionId))
            throw new ServiceException("调试流登录已退出、过期或变化",401);
        var previous=SecurityContextHolder.getContext();var previousRequest=RequestContextHolder.getRequestAttributes();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(login,null,login.getAuthorities()));
        var scope=new PermissionRequestScope();
        SecurityContextHolder.setContext(context);RequestContextHolder.setRequestAttributes(scope);
        try{return work.call();}
        finally{SecurityContextHolder.setContext(previous);if(previousRequest==null)RequestContextHolder.resetRequestAttributes();else RequestContextHolder.setRequestAttributes(previousRequest);scope.close();}
    }
    /** 在创建Run/回复CP前取得容量，失败不会留下这次运行写入。 */
    public Reservation reserve(Session session) {
        if(!capacity.tryAcquire())throw new ServiceException("调试流连接已达容量，请稍后续读",503);
        try {actors.compute(session.actor,(id,count)->{int next=count==null?1:count+1;if(next>4)throw new ServiceException("当前用户调试流连接过多",503);return next;});}
        catch(RuntimeException error){capacity.release();throw error;}
        return new Reservation(session);
    }
    public final class Reservation implements AutoCloseable {
        private final Session session;private final AtomicBoolean released=new AtomicBoolean();private boolean opened;
        private Reservation(Session session){this.session=session;}
        public SseEmitter open(LegacyDebugBridgeService.Attachment attachment) {
            if(opened || released.get())throw new ServiceException("调试流连接已结束",409);
            var connection=new Connection(this,attachment);
            try {connection.start();opened=true;return connection.emitter;}
            catch(RuntimeException error){release();throw new ServiceException("调试流调度暂不可用，请续读同一运行",503);}
        }
        private void release(){if(released.compareAndSet(false,true)){actors.computeIfPresent(session.actor,(id,count)->count==1?null:count-1);capacity.release();}}
        @Override public void close(){if(!opened)release();}
    }
    private final class Connection {
        private final Reservation reservation;private final LegacyDebugBridgeService.Attachment attachment;
        private final SseEmitter emitter=new SseEmitter(60_000L);
        private final AtomicBoolean closed=new AtomicBoolean(),inFlight=new AtomicBoolean();
        private final AtomicReference<ScheduledFuture<?>> task=new AtomicReference<>();
        private long after;private long heartbeat=System.nanoTime();
        private Connection(Reservation reservation,LegacyDebugBridgeService.Attachment attachment){this.reservation=reservation;this.attachment=attachment;this.after=attachment.after();}
        private void start() {
            emitter.onCompletion(this::close);emitter.onTimeout(this::close);emitter.onError(error->close());
            var future=scheduler.scheduleWithFixedDelay(this::tick,0,250,TimeUnit.MILLISECONDS);
            task.set(future);if(closed.get())future.cancel(false);
        }
        private void tick() {
            if(closed.get() || !inFlight.compareAndSet(false,true))return;
            try{io.execute(this::readSend);}catch(RuntimeException error){inFlight.set(false);fail(new ServiceException("调试流读取调度不可用",503));}
        }
        private void readSend() {
            try {
                if(closed.get())return;
                var page=withSession(reservation.session,()->bridge.page(attachment,after));
                for(var frame:page.frames()) {
                    if(closed.get())return;
                    emitter.send(SseEmitter.event().id(frame.id()).data(frame.data().toJSONString(JSONWriter.Feature.WriteMapNullValue)));
                }
                after=page.scannedThrough();
                if(page.waiting() || page.terminal()){emitter.complete();close();return;}
                if(System.nanoTime()-heartbeat>=TimeUnit.SECONDS.toNanos(15)) {
                    emitter.send(SseEmitter.event().comment("keepalive"));heartbeat=System.nanoTime();
                }
            }catch(Exception error){fail(error);}finally{inFlight.set(false);}
        }
        private void fail(Exception error){if(closed.get())return;close();emitter.completeWithError(error);}
        private void close(){if(closed.compareAndSet(false,true)){var future=task.get();if(future!=null)future.cancel(false);reservation.release();}}
    }
    /** 不持有已结束的Servlet request或创建HTTP session；仅适配既有权限标签的请求上下文。 */
    private static final class PermissionRequestScope implements RequestAttributes,AutoCloseable {
        private final Map<String,Object> values=new HashMap<>();private final Map<String,Runnable> callbacks=new LinkedHashMap<>();
        private static void scope(int scope){if(scope!=SCOPE_REQUEST)throw new IllegalStateException("后台流只提供当前请求权限作用域");}
        public Object getAttribute(String name,int scope){scope(scope);return values.get(name);}
        public void setAttribute(String name,Object value,int scope){scope(scope);values.put(name,value);}
        public void removeAttribute(String name,int scope){scope(scope);values.remove(name);callbacks.remove(name);}
        public String[] getAttributeNames(int scope){scope(scope);return values.keySet().toArray(String[]::new);}
        public void registerDestructionCallback(String name,Runnable callback,int scope){scope(scope);callbacks.put(name,callback);}
        public Object resolveReference(String key){return null;}
        public String getSessionId(){throw new IllegalStateException("后台流不创建session");}
        public Object getSessionMutex(){throw new IllegalStateException("后台流不创建session");}
        public void close(){try{callbacks.values().forEach(Runnable::run);}finally{values.clear();callbacks.clear();}}
    }
}
