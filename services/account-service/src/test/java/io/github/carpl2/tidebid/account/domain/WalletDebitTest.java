package io.github.carpl2.tidebid.account.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletDebitTest {

    @Test
    void normalizesValidMoneyToTwoDecimalPlaces() {
        assertThat(WalletDebit.requireAmount(new BigDecimal("1")))
                .isEqualTo(new BigDecimal("1.00"));
        assertThat(WalletDebit.requireAmount(new BigDecimal("99999999999999999.99")))
                .isEqualTo(new BigDecimal("99999999999999999.99"));
    }

    @Test
    void rejectsInvalidMoneyBoundaries() {
        assertThatThrownBy(() -> WalletDebit.requireAmount(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WalletDebit.requireAmount(new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WalletDebit.requireAmount(new BigDecimal("1.001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WalletDebit.requireAmount(new BigDecimal("100000000000000000.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesResultShape() {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        assertThatThrownBy(() -> new WalletDebit(
                1L, "PAY:1", 2L, 3L, new BigDecimal("10.00"),
                WalletDebitStatus.SUCCEEDED, WalletDebit.INSUFFICIENT_BALANCE, now, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WalletDebit(
                1L, "PAY:1", 2L, 3L, new BigDecimal("10.00"),
                WalletDebitStatus.REJECTED, null, now, now))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
