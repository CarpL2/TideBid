package io.github.carpl2.tidebid.account.application.port;

import io.github.carpl2.tidebid.account.domain.WalletHold;
import io.github.carpl2.tidebid.account.domain.WalletHoldBusinessType;

import java.math.BigDecimal;
import java.util.Objects;

public interface WalletHoldTransaction {

    WalletHold create(HoldData holdData);

    record HoldData(
            String holdNo,
            long userId,
            WalletHoldBusinessType businessType,
            BigDecimal amount
    ) {
        public HoldData {
            Objects.requireNonNull(holdNo, "holdNo must not be null");
            Objects.requireNonNull(businessType, "businessType must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }
    }
}
