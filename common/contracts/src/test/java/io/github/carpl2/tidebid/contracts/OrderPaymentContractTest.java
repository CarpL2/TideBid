package io.github.carpl2.tidebid.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderPaymentContractTest {

    private static final long ORDER_ID = 9_007_199_254_740_993L;
    private static final long AUCTION_ID = 9_007_199_254_740_994L;
    private static final long SELLER_ID = 9_007_199_254_740_995L;
    private static final long BUYER_ID = 9_007_199_254_740_996L;
    private static final String ORDER_NO = "ORDER:9007199254740993";
    private static final Instant DEADLINE = Instant.parse("2026-09-16T04:00:00Z");
    private static final Instant TERMINAL_AT = Instant.parse("2026-09-16T04:00:01Z");
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void timeoutCommandCarriesOnlyOrderAndExpectedDeadline() {
        OrderPaymentTimeoutCommand command = new OrderPaymentTimeoutCommand(ORDER_ID, DEADLINE);

        assertThat(OrderPaymentTimeoutCommand.EVENT_TYPE).isEqualTo("order.payment-timeout");
        assertThat(OrderPaymentTimeoutCommand.SCHEMA_VERSION).isEqualTo(1);
        assertThat(command.orderId()).isEqualTo(ORDER_ID);
        assertThat(command.expectedPaymentDeadline()).isEqualTo(DEADLINE);
    }

    @Test
    void timeoutCommandRejectsInvalidIdentityAndMissingDeadline() {
        assertThatThrownBy(() -> new OrderPaymentTimeoutCommand(0, DEADLINE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("orderId");
        assertThatThrownBy(() -> new OrderPaymentTimeoutCommand(ORDER_ID, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("expectedPaymentDeadline");
    }

    @Test
    void paidEventSupportsDepositOnlyPayment() {
        OrderPaidEvent event = paid(null, "2333.00", "2333.00", "0.00");

        assertThat(OrderPaidEvent.EVENT_TYPE).isEqualTo("order.paid");
        assertThat(OrderPaidEvent.SCHEMA_VERSION).isEqualTo(1);
        assertThat(event.paymentNo()).isNull();
        assertThat(event.capturedDepositAmount()).isEqualByComparingTo(event.finalPrice());
        assertThat(event.tailPaymentAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void paidEventSupportsTailPaymentAndPreservesLargeIdsInJson() throws Exception {
        OrderPaidEvent event = paid("PAYMENT:20260916:abc-123", "2333.00", "1000.00", "1333.00");

        String json = objectMapper.writeValueAsString(event);
        assertThat(json)
                .contains("\"orderId\":\"9007199254740993\"")
                .contains("\"auctionId\":\"9007199254740994\"")
                .contains("\"sellerId\":\"9007199254740995\"")
                .contains("\"buyerId\":\"9007199254740996\"")
                .doesNotContain("nickname", "mobile", "phone", "token", "accessKey", "secret");
    }

    @Test
    void paidEventRejectsInvalidPaymentCorrelationAndAmounts() {
        assertThatThrownBy(() -> paid(null, "2333.00", "1000.00", "1333.00"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("paymentNo");
        assertThatThrownBy(() -> paid("PAYMENT:1", "2333.00", "2333.00", "0.00"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be absent");
        assertThatThrownBy(() -> paid("PAYMENT:1", "2333.00", "1000.00", "1300.00"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must equal finalPrice");
    }

    @Test
    void timedOutEventCapturesOnlyDepositCompensationAndUnpaidTail() {
        OrderPaymentTimedOutEvent event = timedOut("2333.00", "1000.00", "1333.00", TERMINAL_AT);

        assertThat(OrderPaymentTimedOutEvent.EVENT_TYPE).isEqualTo("order.payment-timed-out");
        assertThat(OrderPaymentTimedOutEvent.SCHEMA_VERSION).isEqualTo(1);
        assertThat(event.capturedDepositAmount()).isEqualByComparingTo("1000.00");
        assertThat(event.unpaidAmount()).isEqualByComparingTo("1333.00");
        assertThat(event.timedOutAt()).isAfterOrEqualTo(event.paymentDeadline());
    }

    @Test
    void timedOutEventRejectsPaidOrdersAmountMismatchAndEarlyTimeout() {
        assertThatThrownBy(() -> timedOut("2333.00", "2333.00", "0.00", TERMINAL_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unpaidAmount");
        assertThatThrownBy(() -> timedOut("2333.00", "1000.00", "1200.00", TERMINAL_AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must equal finalPrice");
        assertThatThrownBy(() -> timedOut(
                "2333.00", "1000.00", "1333.00", DEADLINE.minusMillis(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timedOutAt");
    }

    private static OrderPaidEvent paid(
            String paymentNo,
            String finalPrice,
            String capturedDepositAmount,
            String tailPaymentAmount
    ) {
        return new OrderPaidEvent(
                ORDER_ID,
                ORDER_NO,
                AUCTION_ID,
                SELLER_ID,
                BUYER_ID,
                paymentNo,
                money(finalPrice),
                money(capturedDepositAmount),
                money(tailPaymentAmount),
                TERMINAL_AT
        );
    }

    private static OrderPaymentTimedOutEvent timedOut(
            String finalPrice,
            String capturedDepositAmount,
            String unpaidAmount,
            Instant timedOutAt
    ) {
        return new OrderPaymentTimedOutEvent(
                ORDER_ID,
                ORDER_NO,
                AUCTION_ID,
                SELLER_ID,
                BUYER_ID,
                money(finalPrice),
                money(capturedDepositAmount),
                money(unpaidAmount),
                DEADLINE,
                timedOutAt
        );
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
