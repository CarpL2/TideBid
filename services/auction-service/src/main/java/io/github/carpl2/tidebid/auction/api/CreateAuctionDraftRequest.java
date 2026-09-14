package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CreateAuctionDraftRequest(
        @NotBlank @Size(max = 80) String title,
        @NotBlank @Size(max = 2000) String description,
        @NotBlank String category,
        @NotNull AuctionItemCondition itemCondition,
        @NotNull BigDecimal startPrice,
        @NotNull BigDecimal bidIncrement,
        @NotNull BigDecimal depositAmount,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        @NotEmpty @Size(max = 9) List<@NotBlank String> imageObjectKeys
) {
}
