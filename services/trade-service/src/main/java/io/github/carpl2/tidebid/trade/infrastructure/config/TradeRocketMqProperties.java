package io.github.carpl2.tidebid.trade.infrastructure.config;

import io.github.carpl2.tidebid.contracts.RocketMqTopology;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("tidebid.messaging.rocketmq")
public record TradeRocketMqProperties(
        @DefaultValue("127.0.0.1:8081") String endpoints,
        @DefaultValue("3s") Duration requestTimeout,
        @DefaultValue("2") int producerRetryAttempts,
        @DefaultValue Topics topics,
        @DefaultValue ConsumerGroups consumerGroups
) {

    public TradeRocketMqProperties {
        endpoints = requireEndpoint(endpoints);
        requestTimeout = requireDuration(
                requestTimeout, Duration.ofMillis(100), Duration.ofSeconds(30), "requestTimeout");
        if (producerRetryAttempts < 0 || producerRetryAttempts > 5) {
            throw new IllegalArgumentException("producerRetryAttempts must be between 0 and 5");
        }
        topics = Objects.requireNonNull(topics, "topics must not be null");
        consumerGroups = Objects.requireNonNull(consumerGroups, "consumerGroups must not be null");
    }

    public record Topics(
            @DefaultValue(RocketMqTopology.TRADE_EVENTS_TOPIC) String tradeEvents,
            @DefaultValue(RocketMqTopology.AUCTION_EVENTS_TOPIC) String auctionEvents,
            @DefaultValue(RocketMqTopology.ACCOUNT_EVENTS_TOPIC) String accountEvents,
            @DefaultValue(RocketMqTopology.SCHEDULED_COMMANDS_TOPIC) String scheduledCommands
    ) {
        public Topics {
            requireExact(tradeEvents, RocketMqTopology.TRADE_EVENTS_TOPIC, "topics.tradeEvents");
            requireExact(auctionEvents, RocketMqTopology.AUCTION_EVENTS_TOPIC, "topics.auctionEvents");
            requireExact(accountEvents, RocketMqTopology.ACCOUNT_EVENTS_TOPIC, "topics.accountEvents");
            requireExact(scheduledCommands, RocketMqTopology.SCHEDULED_COMMANDS_TOPIC, "topics.scheduledCommands");
        }
    }

    public record ConsumerGroups(
            @DefaultValue(RocketMqTopology.TRADE_AUCTION_CONSUMER_GROUP) String auctionResults,
            @DefaultValue(RocketMqTopology.TRADE_ACCOUNT_CONSUMER_GROUP) String accountResults,
            @DefaultValue(RocketMqTopology.TRADE_TIMEOUT_CONSUMER_GROUP) String paymentTimeout
    ) {
        public ConsumerGroups {
            requireExact(auctionResults, RocketMqTopology.TRADE_AUCTION_CONSUMER_GROUP,
                    "consumerGroups.auctionResults");
            requireExact(accountResults, RocketMqTopology.TRADE_ACCOUNT_CONSUMER_GROUP,
                    "consumerGroups.accountResults");
            requireExact(paymentTimeout, RocketMqTopology.TRADE_TIMEOUT_CONSUMER_GROUP,
                    "consumerGroups.paymentTimeout");
        }
    }

    private static String requireEndpoint(String value) {
        value = Objects.requireNonNull(value, "endpoints must not be null").trim();
        if (value.isEmpty() || value.length() > 255 || value.contains("://") || value.contains("/")
                || value.chars().anyMatch(Character::isWhitespace) || !value.matches("[^:]+:[1-9][0-9]{0,4}")) {
            throw new IllegalArgumentException("endpoints must use host:port without a URI scheme or path");
        }
        int port = Integer.parseInt(value.substring(value.lastIndexOf(':') + 1));
        if (port > 65535) {
            throw new IllegalArgumentException("endpoints port must be between 1 and 65535");
        }
        return value;
    }

    private static Duration requireDuration(Duration value, Duration minimum, Duration maximum, String name) {
        value = Objects.requireNonNull(value, name + " must not be null");
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " is outside the allowed range");
        }
        return value;
    }

    private static void requireExact(String value, String expected, String name) {
        if (!expected.equals(value)) {
            throw new IllegalArgumentException(name + " must match the bootstrapped RocketMQ topology");
        }
    }
}
