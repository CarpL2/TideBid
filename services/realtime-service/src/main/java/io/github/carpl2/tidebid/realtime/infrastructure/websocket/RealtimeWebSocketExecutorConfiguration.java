package io.github.carpl2.tidebid.realtime.infrastructure.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

@Configuration(proxyBeanMethods = false)
public class RealtimeWebSocketExecutorConfiguration {

    @Bean(name = "realtimeWebSocketSendExecutor", destroyMethod = "shutdown")
    Executor realtimeWebSocketSendExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "tidebid-realtime-send");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newCachedThreadPool(factory);
    }

    @Bean(name = "realtimeWebSocketHeartbeatExecutor", destroyMethod = "shutdownNow")
    ScheduledExecutorService realtimeWebSocketHeartbeatExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "tidebid-realtime-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }
}
