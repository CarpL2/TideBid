package io.github.carpl2.tidebid.account.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletHoldTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-12T00:00:00Z");

    @Test
    void representsAnImmutableAuctionDepositHold() {
        WalletHold hold = new WalletHold(
                11L,
                "REG:20260912:abc-123",
                22L,
                WalletHoldBusinessType.AUCTION_DEPOSIT,
                new BigDecimal("500.00"),
                WalletHoldStatus.HELD,
                0L,
                CREATED_AT,
                CREATED_AT
        );

        assertThat(hold.holdNo()).isEqualTo("REG:20260912:abc-123");
        assertThat(hold.amount()).isEqualByComparingTo("500.00");
        assertThat(hold.businessType()).isEqualTo(WalletHoldBusinessType.AUCTION_DEPOSIT);
        assertThat(hold.status()).isEqualTo(WalletHoldStatus.HELD);
    }

    @Test
    void rejectsUnsafeBusinessReferencesAndInvalidMoney() {
        assertThatThrownBy(() -> WalletHold.requireHoldNo("../../another-service"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safe characters");
        assertThatThrownBy(() -> WalletHold.requireAmount(new BigDecimal("0.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> WalletHold.requireAmount(new BigDecimal("1.001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DECIMAL(19,2)");
        assertThatThrownBy(() -> WalletHold.requireAmount(new BigDecimal("1E+20")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DECIMAL(19,2)");
    }

    @Test
    void rejectsImpossiblePersistentSnapshots() {
        assertThatThrownBy(() -> new WalletHold(
                0L,
                "REG:abc",
                22L,
                WalletHoldBusinessType.AUCTION_DEPOSIT,
                new BigDecimal("500.00"),
                WalletHoldStatus.HELD,
                0L,
                CREATED_AT,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");

        assertThatThrownBy(() -> new WalletHold(
                11L,
                "REG:abc",
                22L,
                WalletHoldBusinessType.AUCTION_DEPOSIT,
                new BigDecimal("500.00"),
                WalletHoldStatus.HELD,
                0L,
                CREATED_AT,
                CREATED_AT.minusSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("updatedAt");
    }
}
