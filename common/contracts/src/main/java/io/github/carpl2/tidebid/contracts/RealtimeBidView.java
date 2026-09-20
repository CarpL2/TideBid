package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;

public record RealtimeBidView(
        @JsonSerialize(using = ToStringSerializer.class) long bidId,
        BigDecimal amount,
        long sequenceNo,
        boolean mine,
        Instant acceptedAt
) {
    public RealtimeBidView {
        bidId = ContractRules.positive(bidId, "bidId");
        amount = ContractRules.positiveMoney(amount, "amount");
        if (sequenceNo <= 0) {
            throw new IllegalArgumentException("sequenceNo must be positive");
        }
        acceptedAt = ContractRules.instant(acceptedAt, "acceptedAt");
    }
}
