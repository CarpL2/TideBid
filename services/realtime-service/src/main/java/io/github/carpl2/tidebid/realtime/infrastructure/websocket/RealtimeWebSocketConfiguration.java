package io.github.carpl2.tidebid.realtime.infrastructure.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.realtime.application.port.RealtimeConnectionLeaseStore;
import io.github.carpl2.tidebid.realtime.infrastructure.fanout.RealtimeWebSocketSessionRegistry;
import io.github.carpl2.tidebid.realtime.application.port.AuctionSnapshotClient;
import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeProperties;
import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketApplicationService;
import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeWebSocketProperties;
import io.github.carpl2.tidebid.realtime.infrastructure.metrics.RealtimeMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@ConditionalOnProperty(prefix = "tidebid.realtime.websocket", name = "enabled", havingValue = "true")
public class RealtimeWebSocketConfiguration implements WebSocketConfigurer {

    private final RealtimeWebSocketHandler handler;
    private final RealtimeWebSocketHandshakeInterceptor interceptor;
    private final RealtimeWebSocketProperties properties;

    public RealtimeWebSocketConfiguration(
            RealtimeTicketApplicationService ticketService,
            RealtimeWebSocketProperties properties,
            RealtimeMetrics metrics,
            ObjectMapper objectMapper,
            RealtimeProperties realtimeProperties,
            RealtimeConnectionLeaseStore leaseStore,
            RealtimeWebSocketSessionRegistry sessionRegistry,
            ObjectProvider<AuctionSnapshotClient> snapshotClients
    ) {
        this.properties = properties;
        this.handler = new RealtimeWebSocketHandler(
                metrics, objectMapper, realtimeProperties, leaseStore, sessionRegistry,
                snapshotClients.getIfAvailable());
        this.interceptor = new RealtimeWebSocketHandshakeInterceptor(
                ticketService, properties, metrics, realtimeProperties, leaseStore);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/auctions")
                .addInterceptors(interceptor)
                .setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
    }

    @Bean
    ServletServerContainerFactoryBean webSocketContainer(RealtimeProperties realtimeProperties) {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        int limit = Math.toIntExact(realtimeProperties.queue().maxClientMessageSize().toBytes());
        container.setMaxTextMessageBufferSize(limit);
        container.setMaxBinaryMessageBufferSize(limit);
        return container;
    }

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

    @Bean
    RealtimeWebSocketHeartbeatCoordinator realtimeWebSocketHeartbeatCoordinator(
            RealtimeWebSocketSessionRegistry sessionRegistry,
            RealtimeConnectionLeaseStore leaseStore,
            RealtimeProperties realtimeProperties,
            @Qualifier("realtimeWebSocketHeartbeatExecutor") ScheduledExecutorService scheduler
    ) {
        return new RealtimeWebSocketHeartbeatCoordinator(
                sessionRegistry, leaseStore, realtimeProperties, scheduler);
    }
}
