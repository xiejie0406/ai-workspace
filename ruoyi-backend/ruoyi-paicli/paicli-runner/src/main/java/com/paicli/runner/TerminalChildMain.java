package com.paicli.runner;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;

/** 仅供固定PTY子JVM使用。父Runner失联后，即使JLine正等按键，也不能无限保留CLI。 */
public final class TerminalChildMain {
    private TerminalChildMain(){}
    public static void main(String[] args){
        ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"paicli-child-capability-watchdog");t.setDaemon(true);return t;});
        try{
            TerminalCapability capability=TerminalCapability.observeFromEnvironment();
            if(capability==null)throw new IllegalArgumentException("网页子进程必须持有短期身份");
            Instant deadline=Instant.now().plus(Duration.ofHours(8));
            watchdog.scheduleWithFixedDelay(()->{
                try{capability.token();if(Instant.now().isAfter(deadline))System.exit(2);}
                catch(Exception expired){System.exit(2);}
            },5,5,TimeUnit.SECONDS);
            // 同原Main的正式分派入口：所有业务仍经CenterCli和中心Gateway，绝不进入runNative。
            int result=CenterGateway.run(args);watchdog.shutdownNow();System.exit(result);
        }catch(Exception failed){watchdog.shutdownNow();System.err.println("网页终端短期身份不可用");System.exit(2);}
    }
}
