package io.github.carpl2.tidebid.auction.infrastructure.client;

import java.math.BigDecimal;

record AccountWalletHoldRequest(
        String holdNo,
        String userId,
        String businessType,
        BigDecimal amount
) {
}
