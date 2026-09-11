package io.github.carpl2.tidebid.auction.infrastructure.client;

import java.math.BigDecimal;
import java.time.Instant;

record AccountWalletHoldResponse(
        String holdId,
        String holdNo,
        String userId,
        String businessType,
        BigDecimal amount,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
