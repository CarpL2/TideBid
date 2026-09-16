package io.github.carpl2.tidebid.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContractValidationTest {

    private static final Map<Class<?>, String> VERSIONED_TYPES = versionedTypes();

    @Test
    void everyStageThreePayloadHasStableUniqueEventTypeAndVersion() throws Exception {
        assertThat(VERSIONED_TYPES).hasSize(11);
        assertThat(VERSIONED_TYPES.values())
                .doesNotHaveDuplicates()
                .allMatch(value -> value.matches("[a-z]+(?:[.-][a-z]+)+"));

        for (Map.Entry<Class<?>, String> entry : VERSIONED_TYPES.entrySet()) {
            Class<?> payloadType = entry.getKey();
            assertThat(payloadType.isRecord()).as(payloadType.getSimpleName()).isTrue();
            assertThat(payloadType.getField("EVENT_TYPE").get(null))
                    .as(payloadType.getSimpleName() + " EVENT_TYPE")
                    .isEqualTo(entry.getValue());
            assertThat(payloadType.getField("SCHEMA_VERSION").getInt(null))
                    .as(payloadType.getSimpleName() + " SCHEMA_VERSION")
                    .isEqualTo(1);
        }
    }

    @Test
    void enumWireValuesRemainExplicitAndStable() {
        assertThat(DepositSettlementType.values())
                .extracting(Enum::name)
                .containsExactly("RELEASE", "CAPTURE");
        assertThat(WalletHoldSettlementStatus.values())
                .extracting(Enum::name)
                .containsExactly("RELEASED", "CAPTURED");
        assertThat(SellerCreditReason.values())
                .extracting(Enum::name)
                .containsExactly("SALE_PROCEEDS", "DEFAULT_COMPENSATION");
    }

    @Test
    void identifiersMustBePositiveAndBusinessKeysMustBeBounded() {
        assertThat(ContractRules.positive(1, "id")).isEqualTo(1);
        assertThatThrownBy(() -> ContractRules.positive(0, "id"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("id");
        assertThatThrownBy(() -> ContractRules.positive(-1, "id"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("id");

        assertThat(ContractRules.businessKey(" ORDER:123 ", "orderNo")).isEqualTo("ORDER:123");
        assertThatThrownBy(() -> ContractRules.businessKey("", "orderNo"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("orderNo");
        assertThatThrownBy(() -> ContractRules.businessKey("x".repeat(65), "orderNo"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("orderNo");
        assertThatThrownBy(() -> ContractRules.businessKey("ORDER/123", "orderNo"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("orderNo");
    }

    @Test
    void moneyUsesNonNegativeDecimalNineteenScaleTwoSemantics() {
        assertThat(ContractRules.nonNegativeMoney(new BigDecimal("12"), "amount"))
                .isEqualTo(new BigDecimal("12.00"));
        assertThat(ContractRules.positiveMoney(new BigDecimal("0.01"), "amount"))
                .isEqualTo(new BigDecimal("0.01"));
        assertThatThrownBy(() -> ContractRules.positiveMoney(BigDecimal.ZERO, "amount"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        assertThatThrownBy(() -> ContractRules.nonNegativeMoney(new BigDecimal("-0.01"), "amount"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("negative");
        assertThatThrownBy(() -> ContractRules.nonNegativeMoney(new BigDecimal("1.001"), "amount"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("decimal places");
        assertThatThrownBy(() -> ContractRules.nonNegativeMoney(
                new BigDecimal("100000000000000000.00"), "amount"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DECIMAL(19,2)");
        assertThatThrownBy(() -> ContractRules.nonNegativeMoney(null, "amount"))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("amount");
    }

    @Test
    void timesAreMandatoryAndTerminalTimeCannotPrecedeDeadline() {
        Instant endedAt = Instant.parse("2026-09-16T06:00:00Z");

        assertThat(ContractRules.instant(endedAt, "endedAt")).isEqualTo(endedAt);
        assertThatThrownBy(() -> ContractRules.instant(null, "endedAt"))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("endedAt");
        assertThatCode(() -> ContractRules.closedAtOrAfterEnd(endedAt, endedAt)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ContractRules.closedAtOrAfterEnd(endedAt, endedAt.minusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("closedAt");
    }

    private static Map<Class<?>, String> versionedTypes() {
        Map<Class<?>, String> types = new LinkedHashMap<>();
        types.put(BidAcceptedEvent.class, "auction.bid-accepted");
        types.put(CloseAuctionCommand.class, "auction.close");
        types.put(AuctionClosedSoldEvent.class, "auction.closed-sold");
        types.put(AuctionClosedUnsoldEvent.class, "auction.closed-unsold");
        types.put(DepositSettlementRequestedEvent.class, "deposit.settlement-requested");
        types.put(WalletHoldSettledEvent.class, "wallet-hold.settled");
        types.put(OrderPaymentTimeoutCommand.class, "order.payment-timeout");
        types.put(OrderPaidEvent.class, "order.paid");
        types.put(OrderPaymentTimedOutEvent.class, "order.payment-timed-out");
        types.put(SellerCreditRequestedEvent.class, "seller.credit-requested");
        types.put(SellerCreditedEvent.class, "seller.credited");
        return Map.copyOf(types);
    }
}
