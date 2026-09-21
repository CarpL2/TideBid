package io.github.carpl2.tidebid.auction.api;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpsertAuctionProxyBidRequest(@NotNull BigDecimal maxAmount) { }
