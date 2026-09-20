package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RealtimeAuctionExtended(
        UUID eventId,
        @JsonSerialize(using = ToStringSerializer.class) long auctionId,
        Instant previousEndAt,
        Instant endAt,
        int extensionCount,
        Instant extendedAt
) {
    public RealtimeAuctionExtended {
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        AuctionTimeExtendedEvent event = new AuctionTimeExtendedEvent(
                auctionId, previousEndAt, endAt, extensionCount, extendedAt);
        auctionId = event.auctionId();
        previousEndAt = event.previousEndAt();
        endAt = event.endAt();
        extensionCount = event.extensionCount();
        extendedAt = event.extendedAt();
    }
}
