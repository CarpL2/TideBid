package io.github.carpl2.tidebid.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DepositSettlementContractTest {

    private static final long AUCTION_ID = 9_007_199_254_740_993L;
    private static final long ORDER_ID = 9_007_199_254_740_994L;
    private static final long USER_ID = 9_007_199_254_740_995L;
    private static final String HOLD_NO = "REGISTRATION:9007199254740996";
    private static final Instant SETTLED_AT = Instant.parse("2026-09-16T03:00:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void releaseRequestHasNoOrderAndNoCaptureTarget() {
        DepositSettlementRequestedEvent request = releaseRequest();

        assertThat(DepositSettlementRequestedEvent.EVENT_TYPE).isEqualTo("deposit.settlement-requested");
        assertThat(DepositSettlementRequestedEvent.SCHEMA_VERSION).isEqualTo(1);
        assertThat(request.orderId()).isNull();
        assertThat(request.captureTargetAmount()).isEqualByComparingTo("0.00");
        assertThat(request.captureTargetAmount().scale()).isEqualTo(2);
    }

    @Test
    void captureRequestRequiresOrderAndPositiveTarget() throws Exception {
        DepositSettlementRequestedEvent request = captureRequest("2333.00");

        String json = objectMapper.writeValueAsString(request);
        assertThat(json)
                .contains("\"auctionId\":\"9007199254740993\"")
                .contains("\"orderId\":\"9007199254740994\"")
                .contains("\"userId\":\"9007199254740995\"")
                .doesNotContain("nickname", "mobile", "phone", "token", "accessKey", "secret");

        assertThatThrownBy(() -> new DepositSettlementRequestedEvent(
                DepositSettlementType.CAPTURE, AUCTION_ID, null, USER_ID, HOLD_NO,
                money("100.00"), money("50.00")
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("orderId");
        assertThatThrownBy(() -> new DepositSettlementRequestedEvent(
                DepositSettlementType.RELEASE, AUCTION_ID, ORDER_ID, USER_ID, HOLD_NO,
                money("100.00"), money("0.00")
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RELEASE");
        assertThatThrownBy(() -> captureRequest("0.00"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
    }

    @Test
    void releasedResultReturnsCompleteHoldToAvailableBalance() {
        WalletHoldSettledEvent result = new WalletHoldSettledEvent(
                DepositSettlementType.RELEASE,
                WalletHoldSettlementStatus.RELEASED,
                AUCTION_ID,
                null,
                USER_ID,
                HOLD_NO,
                money("1233.00"),
                money("0.00"),
                money("0.00"),
                money("1233.00"),
                SETTLED_AT
        );

        assertThat(WalletHoldSettledEvent.EVENT_TYPE).isEqualTo("wallet-hold.settled");
        assertThat(WalletHoldSettledEvent.SCHEMA_VERSION).isEqualTo(1);
        assertThat(result.capturedAmount()).isEqualByComparingTo("0.00");
        assertThat(result.releasedAmount()).isEqualByComparingTo(result.holdAmount());
    }

    @Test
    void capturedResultSplitsHoldWhenDepositExceedsFinalPrice() {
        WalletHoldSettledEvent result = capturedResult("3000.00", "2333.00", "2333.00", "667.00");

        assertThat(result.holdStatus()).isEqualTo(WalletHoldSettlementStatus.CAPTURED);
        assertThat(result.capturedAmount()).isEqualByComparingTo("2333.00");
        assertThat(result.releasedAmount()).isEqualByComparingTo("667.00");
        assertThat(result.capturedAmount().add(result.releasedAmount()))
                .isEqualByComparingTo(result.holdAmount());
    }

    @Test
    void capturedResultConsumesCompleteHoldWhenFinalPriceIsHigher() {
        WalletHoldSettledEvent result = capturedResult("1000.00", "2333.00", "1000.00", "0.00");

        assertThat(result.capturedAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.releasedAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void resultRejectsStatusAmountAndPrecisionMismatches() {
        assertThatThrownBy(() -> new WalletHoldSettledEvent(
                DepositSettlementType.RELEASE, WalletHoldSettlementStatus.CAPTURED,
                AUCTION_ID, null, USER_ID, HOLD_NO,
                money("100.00"), money("0.00"), money("0.00"), money("100.00"), SETTLED_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RELEASED");
        assertThatThrownBy(() -> capturedResult("100.00", "80.00", "70.00", "30.00"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("min(holdAmount");
        assertThatThrownBy(() -> capturedResult("100.00", "80.00", "80.00", "19.00"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must equal holdAmount");
        assertThatThrownBy(() -> new DepositSettlementRequestedEvent(
                DepositSettlementType.CAPTURE, AUCTION_ID, ORDER_ID, USER_ID, HOLD_NO,
                money("100.00"), money("80.001")
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("decimal places");
    }

    private static DepositSettlementRequestedEvent releaseRequest() {
        return new DepositSettlementRequestedEvent(
                DepositSettlementType.RELEASE,
                AUCTION_ID,
                null,
                USER_ID,
                HOLD_NO,
                money("1233.00"),
                money("0.00")
        );
    }

    private static DepositSettlementRequestedEvent captureRequest(String targetAmount) {
        return new DepositSettlementRequestedEvent(
                DepositSettlementType.CAPTURE,
                AUCTION_ID,
                ORDER_ID,
                USER_ID,
                HOLD_NO,
                money("1233.00"),
                money(targetAmount)
        );
    }

    private static WalletHoldSettledEvent capturedResult(
            String holdAmount,
            String targetAmount,
            String capturedAmount,
            String releasedAmount
    ) {
        return new WalletHoldSettledEvent(
                DepositSettlementType.CAPTURE,
                WalletHoldSettlementStatus.CAPTURED,
                AUCTION_ID,
                ORDER_ID,
                USER_ID,
                HOLD_NO,
                money(holdAmount),
                money(targetAmount),
                money(capturedAmount),
                money(releasedAmount),
                SETTLED_AT
        );
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
