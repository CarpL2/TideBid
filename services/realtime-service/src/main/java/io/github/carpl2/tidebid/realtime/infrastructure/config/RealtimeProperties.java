package io.github.carpl2.tidebid.realtime.infrastructure.config;

import io.github.carpl2.tidebid.contracts.RocketMqTopology;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("tidebid.realtime")
public record RealtimeProperties(
        Ticket ticket,
        Connection connection,
        Subscription subscription,
        Heartbeat heartbeat,
        Queue queue,
        AuctionClient auctionClient,
        Redis redis,
        RocketMq rocketmq
) {
    public RealtimeProperties {
        ticket = Objects.requireNonNull(ticket, "ticket must not be null");
        connection = Objects.requireNonNull(connection, "connection must not be null");
        subscription = Objects.requireNonNull(subscription, "subscription must not be null");
        heartbeat = Objects.requireNonNull(heartbeat, "heartbeat must not be null");
        queue = Objects.requireNonNull(queue, "queue must not be null");
        auctionClient = Objects.requireNonNull(auctionClient, "auctionClient must not be null");
        redis = Objects.requireNonNull(redis, "redis must not be null");
        rocketmq = Objects.requireNonNull(rocketmq, "rocketmq must not be null");
        if (connection.leaseTtl().compareTo(heartbeat.idleTimeout()) < 0) {
            throw new IllegalArgumentException("connection leaseTtl must not be shorter than idleTimeout");
        }
    }

    public record Ticket(Duration ttl, Duration rateLimitWindow, int rateLimitMaxRequests) {
        public Ticket {
            ttl = between(ttl, Duration.ofSeconds(5), Duration.ofMinutes(2), "ticket.ttl");
            rateLimitWindow = between(rateLimitWindow, Duration.ofSeconds(1), Duration.ofMinutes(10),
                    "ticket.rateLimitWindow");
            range(rateLimitMaxRequests, 1, 1000, "ticket.rateLimitMaxRequests");
        }
    }

    public record Connection(int maxPerUser, Duration leaseTtl) {
        public Connection {
            range(maxPerUser, 1, 20, "connection.maxPerUser");
            leaseTtl = between(leaseTtl, Duration.ofSeconds(30), Duration.ofMinutes(10),
                    "connection.leaseTtl");
        }
    }

    public record Subscription(int maxPerConnection, int syncBufferCapacity) {
        public Subscription {
            range(maxPerConnection, 1, 100, "subscription.maxPerConnection");
            range(syncBufferCapacity, 16, 4096, "subscription.syncBufferCapacity");
        }
    }

    public record Heartbeat(Duration interval, Duration idleTimeout) {
        public Heartbeat {
            interval = between(interval, Duration.ofSeconds(5), Duration.ofMinutes(1), "heartbeat.interval");
            idleTimeout = between(idleTimeout, Duration.ofSeconds(10), Duration.ofMinutes(10),
                    "heartbeat.idleTimeout");
            if (idleTimeout.compareTo(interval.multipliedBy(2)) < 0) {
                throw new IllegalArgumentException("heartbeat.idleTimeout must be at least twice interval");
            }
        }
    }

    public record Queue(
            int sendCapacity,
            DataSize maxClientMessageSize,
            Duration controlWindow,
            int controlMaxMessages
    ) {
        public Queue {
            range(sendCapacity, 16, 4096, "queue.sendCapacity");
            maxClientMessageSize = Objects.requireNonNull(maxClientMessageSize,
                    "queue.maxClientMessageSize must not be null");
            if (maxClientMessageSize.compareTo(DataSize.ofKilobytes(1)) < 0
                    || maxClientMessageSize.compareTo(DataSize.ofKilobytes(64)) > 0) {
                throw new IllegalArgumentException("queue.maxClientMessageSize must be between 1KB and 64KB");
            }
            controlWindow = between(controlWindow, Duration.ofSeconds(1), Duration.ofMinutes(1),
                    "queue.controlWindow");
            range(controlMaxMessages, 1, 1000, "queue.controlMaxMessages");
        }
    }

    public record AuctionClient(
            boolean enabled,
            String baseUrl,
            String internalToken,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        public AuctionClient {
            baseUrl = normalize(baseUrl);
            internalToken = normalize(internalToken);
            connectTimeout = between(connectTimeout, Duration.ofMillis(100), Duration.ofSeconds(10),
                    "auctionClient.connectTimeout");
            readTimeout = between(readTimeout, Duration.ofMillis(100), Duration.ofSeconds(30),
                    "auctionClient.readTimeout");
            if (!baseUrl.isEmpty() && !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
                throw new IllegalArgumentException("auctionClient.baseUrl must be empty or an HTTP(S) URL");
            }
        }

        public String requiredInternalToken() {
            if (!enabled || internalToken.length() < 32 || internalToken.length() > 512) {
                throw new IllegalStateException(
                        "TIDEBID_INTERNAL_SERVICE_TOKEN must contain 32 to 512 characters when Auction client is enabled");
            }
            return internalToken;
        }

        @Override
        public String toString() {
            return "AuctionClient[enabled=" + enabled + ", baseUrl=" + baseUrl
                    + ", internalToken=[REDACTED], connectTimeout=" + connectTimeout
                    + ", readTimeout=" + readTimeout + "]";
        }
    }

    public record Redis(boolean enabled) { }

    public record RocketMq(
            boolean enabled,
            String endpoints,
            Duration requestTimeout,
            String consumerGroup,
            String auctionEventsTopic
    ) {
        public RocketMq {
            endpoints = endpoint(endpoints);
            requestTimeout = between(requestTimeout, Duration.ofMillis(100), Duration.ofSeconds(30),
                    "rocketmq.requestTimeout");
            exact(consumerGroup, RocketMqTopology.REALTIME_AUCTION_CONSUMER_GROUP, "rocketmq.consumerGroup");
            exact(auctionEventsTopic, RocketMqTopology.AUCTION_EVENTS_TOPIC, "rocketmq.auctionEventsTopic");
        }
    }

    private static Duration between(Duration value, Duration minimum, Duration maximum, String name) {
        value = Objects.requireNonNull(value, name + " must not be null");
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " is outside the allowed range");
        }
        return value;
    }

    private static void range(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }

    private static String endpoint(String value) {
        value = normalize(value);
        if (value.isEmpty() || value.contains("://") || value.contains("/")
                || value.chars().anyMatch(Character::isWhitespace)
                || !value.matches("[^:]+:[1-9][0-9]{0,4}")) {
            throw new IllegalArgumentException("rocketmq.endpoints must use host:port");
        }
        int port = Integer.parseInt(value.substring(value.lastIndexOf(':') + 1));
        if (port > 65535) throw new IllegalArgumentException("rocketmq endpoint port is invalid");
        return value;
    }

    private static void exact(String actual, String expected, String name) {
        if (!expected.equals(actual)) throw new IllegalArgumentException(name + " must match shared topology");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
