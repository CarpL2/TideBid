package io.github.carpl2.tidebid.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SellerCreditContractTest {

    private static final long ORDER_ID = 9_007_199_254_740_993L;
    private static final long AUCTION_ID = 9_007_199_254_740_994L;
    private static final long SELLER_ID = 9_007_199_254_740_995L;
    private static final String ORDER_NO = "ORDER:9007199254740993";
    private static final String CREDIT_NO = "CREDIT:ORDER:9007199254740993";
    private static final Instant TERMINAL_AT = Instant.parse("2026-09-16T05:00:00Z");
    private static final Instant CREDIT_AT = Instant.parse("2026-09-16T05:00:01Z");
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void paidOrderCreditsCompleteSaleProceeds() {
        SellerCreditRequestedEvent event = requested(
                SellerCreditReason.SALE_PROCEEDS, "2333.00", "1000.00", "2333.00", CREDIT_AT);

        assertThat(SellerCreditRequestedEvent.EVENT_TYPE).isEqualTo("seller.credit-requested");
        assertThat(SellerCreditRequestedEvent.SCHEMA_VERSION).isEqualTo(1);
        assertThat(event.creditAmount()).isEqualByComparingTo(event.finalPrice());
        assertThat(event.creditAmount().scale()).isEqualTo(2);
    }

    @Test
    void timedOutOrderCreditsOnlyCapturedDeposit() {
        SellerCreditRequestedEvent event = requested(
                SellerCreditReason.DEFAULT_COMPENSATION, "2333.00", "1000.00", "1000.00", CREDIT_AT);

        assertThat(event.creditAmount()).isEqualByComparingTo(event.capturedDepositAmount());
        assertThat(event.creditAmount()).isLessThan(event.finalPrice());
    }

    @Test
    void requestRejectsReasonAmountMismatchAndInvalidTimeline() {
        assertThatThrownBy(() -> requested(
                SellerCreditReason.SALE_PROCEEDS, "2333.00", "1000.00", "1000.00", CREDIT_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finalPrice");
        assertThatThrownBy(() -> requested(
                SellerCreditReason.DEFAULT_COMPENSATION, "2333.00", "1000.00", "900.00", CREDIT_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("capturedDepositAmount");
        assertThatThrownBy(() -> requested(
                SellerCreditReason.DEFAULT_COMPENSATION, "2333.00", "2333.00", "2333.00", CREDIT_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("below finalPrice");
        assertThatThrownBy(() -> requested(
                SellerCreditReason.SALE_PROCEEDS,
                "2333.00", "1000.00", "2333.00", TERMINAL_AT.minusMillis(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestedAt");
    }

    @Test
    void requestPreservesLargeIdsAndContainsNoSensitiveData() throws Exception {
        String json = objectMapper.writeValueAsString(requested(
                SellerCreditReason.SALE_PROCEEDS, "2333.00", "1000.00", "2333.00", CREDIT_AT));

        assertThat(json)
                .contains("\"orderId\":\"9007199254740993\"")
                .contains("\"auctionId\":\"9007199254740994\"")
                .contains("\"sellerId\":\"9007199254740995\"")
                .doesNotContain("buyerId", "nickname", "mobile", "phone", "token", "accessKey", "secret");
    }

    @Test
    void creditedEventCorrelatesStableCreditAndOrder() {
        SellerCreditedEvent event = new SellerCreditedEvent(
                SellerCreditReason.SALE_PROCEEDS,
                CREDIT_NO,
                ORDER_ID,
                ORDER_NO,
                AUCTION_ID,
                SELLER_ID,
                money("2333.00"),
                CREDIT_AT
        );

        assertThat(SellerCreditedEvent.EVENT_TYPE).isEqualTo("seller.credited");
        assertThat(SellerCreditedEvent.SCHEMA_VERSION).isEqualTo(1);
        assertThat(event.creditNo()).isEqualTo(CREDIT_NO);
        assertThat(event.creditedAmount()).isEqualByComparingTo("2333.00");
        assertThat(event.creditedAt()).isEqualTo(CREDIT_AT);
    }

    @Test
    void creditedEventRejectsInvalidIdentityMoneyAndTime() {
        assertThatThrownBy(() -> new SellerCreditedEvent(
                SellerCreditReason.SALE_PROCEEDS, CREDIT_NO, 0, ORDER_NO,
                AUCTION_ID, SELLER_ID, money("2333.00"), CREDIT_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("orderId");
        assertThatThrownBy(() -> new SellerCreditedEvent(
                SellerCreditReason.SALE_PROCEEDS, CREDIT_NO, ORDER_ID, ORDER_NO,
                AUCTION_ID, SELLER_ID, money("0.00"), CREDIT_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("creditedAmount");
        assertThatThrownBy(() -> new SellerCreditedEvent(
                SellerCreditReason.SALE_PROCEEDS, CREDIT_NO, ORDER_ID, ORDER_NO,
                AUCTION_ID, SELLER_ID, money("2333.00"), null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("creditedAt");
    }

    private static SellerCreditRequestedEvent requested(
            SellerCreditReason reason,
            String finalPrice,
            String capturedDepositAmount,
            String creditAmount,
            Instant requestedAt
    ) {
        return new SellerCreditRequestedEvent(
                reason,
                CREDIT_NO,
                ORDER_ID,
                ORDER_NO,
                AUCTION_ID,
                SELLER_ID,
                money(finalPrice),
                money(capturedDepositAmount),
                money(creditAmount),
                TERMINAL_AT,
                requestedAt
        );
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
