package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.Instant;
import java.util.Objects;

/**
 * Delayed trigger whose expected end time must be revalidated against the current auction row.
 */
public record CloseAuctionCommand(
        @JsonSerialize(using = ToStringSerializer.class) long auctionId,
        Instant expectedEndAt
) {

    public static final String EVENT_TYPE = "auction.close";
    public static final int SCHEMA_VERSION = 1;

    public CloseAuctionCommand {
        if (auctionId <= 0) {
            throw new IllegalArgumentException("auctionId must be positive");
        }
        expectedEndAt = Objects.requireNonNull(expectedEndAt, "expectedEndAt must not be null");
    }
}
