package io.github.carpl2.tidebid.account.api;

import io.github.carpl2.tidebid.account.domain.WalletHold;

import java.math.BigDecimal;
import java.time.Instant;

public record InternalWalletHoldResponse(
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
    static InternalWalletHoldResponse from(WalletHold hold) {
        return new InternalWalletHoldResponse(
                Long.toString(hold.id()),
                hold.holdNo(),
                Long.toString(hold.userId()),
                hold.businessType().name(),
                hold.amount(),
                hold.status().name(),
                hold.version(),
                hold.createdAt(),
                hold.updatedAt()
        );
    }
}
