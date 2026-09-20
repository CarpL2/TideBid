package io.github.carpl2.tidebid.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RocketMqTopologyTest {

    @Test
    void resourceNamesAreStableDistinctAndEnvironmentNeutral() {
        List<String> topics = List.of(
                RocketMqTopology.AUCTION_EVENTS_TOPIC,
                RocketMqTopology.ACCOUNT_EVENTS_TOPIC,
                RocketMqTopology.TRADE_EVENTS_TOPIC,
                RocketMqTopology.SCHEDULED_COMMANDS_TOPIC
        );
        List<String> groups = List.of(
                RocketMqTopology.AUCTION_CLOSE_CONSUMER_GROUP,
                RocketMqTopology.ACCOUNT_DEPOSIT_CONSUMER_GROUP,
                RocketMqTopology.TRADE_AUCTION_CONSUMER_GROUP,
                RocketMqTopology.TRADE_ACCOUNT_CONSUMER_GROUP,
                RocketMqTopology.TRADE_TIMEOUT_CONSUMER_GROUP,
                RocketMqTopology.ACCOUNT_CREDIT_CONSUMER_GROUP,
                RocketMqTopology.REALTIME_AUCTION_CONSUMER_GROUP
        );

        assertThat(topics)
                .doesNotHaveDuplicates()
                .allMatch(name -> name.startsWith("tidebid-") && !name.contains("dev"));
        assertThat(groups)
                .doesNotHaveDuplicates()
                .allMatch(name -> name.startsWith("tidebid-") && name.endsWith("-v1"));
        assertThat(RocketMqTopology.SCHEDULED_COMMANDS_TOPIC)
                .isEqualTo("tidebid-scheduled-commands");
        assertThat(RocketMqTopology.REALTIME_AUCTION_EVENT_TAGS.split("\\|\\|"))
                .containsExactly(
                        BidAcceptedEvent.EVENT_TYPE,
                        AuctionTimeExtendedEvent.EVENT_TYPE,
                        AuctionClosedSoldEvent.EVENT_TYPE,
                        AuctionClosedUnsoldEvent.EVENT_TYPE
                );
    }
}
