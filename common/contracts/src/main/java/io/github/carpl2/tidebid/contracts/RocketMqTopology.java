package io.github.carpl2.tidebid.contracts;

/**
 * Stable RocketMQ resource names shared by TideBid message producers and consumers.
 */
public final class RocketMqTopology {

    public static final String AUCTION_EVENTS_TOPIC = "tidebid-auction-events";
    public static final String ACCOUNT_EVENTS_TOPIC = "tidebid-account-events";
    public static final String TRADE_EVENTS_TOPIC = "tidebid-trade-events";
    public static final String SCHEDULED_COMMANDS_TOPIC = "tidebid-scheduled-commands";

    public static final String AUCTION_CLOSE_CONSUMER_GROUP = "tidebid-auction-close-v1";
    public static final String ACCOUNT_DEPOSIT_CONSUMER_GROUP = "tidebid-account-deposit-v1";
    public static final String TRADE_AUCTION_CONSUMER_GROUP = "tidebid-trade-auction-v1";
    public static final String TRADE_ACCOUNT_CONSUMER_GROUP = "tidebid-trade-account-v1";
    public static final String TRADE_TIMEOUT_CONSUMER_GROUP = "tidebid-trade-timeout-v1";
    public static final String ACCOUNT_CREDIT_CONSUMER_GROUP = "tidebid-account-credit-v1";

    private RocketMqTopology() {
    }
}
