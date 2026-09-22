package io.github.carpl2.tidebid.realtime.infrastructure.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.realtime.application.port.RealtimeConnectionLeaseStore;
import io.github.carpl2.tidebid.realtime.infrastructure.fanout.RealtimeWebSocketSessionRegistry;
import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeProperties;
import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketApplicationService;
import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeWebSocketProperties;
import io.github.carpl2.tidebid.realtime.infrastructure.metrics.RealtimeMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

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
            RealtimeWebSocketSessionRegistry sessionRegistry
    ) {
        this.properties = properties;
        this.handler = new RealtimeWebSocketHandler(
                metrics, objectMapper, realtimeProperties, leaseStore, sessionRegistry);
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
}
