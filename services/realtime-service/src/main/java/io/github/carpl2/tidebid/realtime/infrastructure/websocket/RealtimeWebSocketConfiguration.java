package io.github.carpl2.tidebid.realtime.infrastructure.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketApplicationService;
import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeWebSocketProperties;
import io.github.carpl2.tidebid.realtime.infrastructure.metrics.RealtimeMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@ConditionalOnProperty(prefix = "tidebid.realtime.websocket", name = "enabled", havingValue = "true")
public class RealtimeWebSocketConfiguration implements WebSocketConfigurer {

    private final RealtimeWebSocketHandler handler;
    private final RealtimeWebSocketHandshakeInterceptor interceptor;

    public RealtimeWebSocketConfiguration(
            RealtimeTicketApplicationService ticketService,
            RealtimeWebSocketProperties properties,
            RealtimeMetrics metrics,
            ObjectMapper objectMapper
    ) {
        this.handler = new RealtimeWebSocketHandler(metrics, objectMapper);
        this.interceptor = new RealtimeWebSocketHandshakeInterceptor(ticketService, properties, metrics);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/auctions")
                .addInterceptors(interceptor)
                .setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
    }
}
