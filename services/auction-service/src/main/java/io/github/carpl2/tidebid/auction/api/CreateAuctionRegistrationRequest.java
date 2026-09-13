package io.github.carpl2.tidebid.auction.api;

import jakarta.validation.constraints.NotBlank;

public record CreateAuctionRegistrationRequest(
        @NotBlank String auctionId
) {
}
