package io.github.carpl2.tidebid.auction.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateAuctionBidRequest(
        @NotBlank String auctionId,
        @NotNull BigDecimal amount
) {
}
