package io.github.carpl2.tidebid.auction.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record SubmitAuctionRequest(
        @NotNull @PositiveOrZero Long itemVersion,
        @NotNull @PositiveOrZero Long sessionVersion
) {
}
