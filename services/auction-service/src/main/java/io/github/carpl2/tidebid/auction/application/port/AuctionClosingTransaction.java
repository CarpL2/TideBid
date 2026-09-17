package io.github.carpl2.tidebid.auction.application.port;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface AuctionClosingTransaction {

    CloseResult close(CloseCommand command);

    record CloseCommand(
            long auctionId,
            Instant expectedEndAt,
            Instant triggeredAt,
            TriggerSource source,
            UUID sourceEventId,
            String traceId
    ) {
        public CloseCommand {
            if (auctionId <= 0) {
                throw new IllegalArgumentException("auctionId must be positive");
            }
            Objects.requireNonNull(expectedEndAt, "expectedEndAt must not be null");
            Objects.requireNonNull(triggeredAt, "triggeredAt must not be null");
            Objects.requireNonNull(source, "source must not be null");
            if (source == TriggerSource.MESSAGE && sourceEventId == null) {
                throw new IllegalArgumentException("message trigger requires sourceEventId");
            }
        }
    }

    enum TriggerSource { MESSAGE, DATABASE_SCAN }

    enum CloseResult {
        CLOSED_SOLD,
        CLOSED_UNSOLD,
        ALREADY_CLOSED,
        TOO_EARLY,
        END_TIME_CHANGED,
        NOT_ELIGIBLE,
        NOT_FOUND,
        LOST_RACE
    }
}
