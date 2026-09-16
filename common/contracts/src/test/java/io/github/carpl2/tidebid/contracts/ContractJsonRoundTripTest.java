package io.github.carpl2.tidebid.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class ContractJsonRoundTripTest {

    private static final long ID_1 = 9_007_199_254_740_993L;
    private static final long ID_2 = 9_007_199_254_740_994L;
    private static final long ID_3 = 9_007_199_254_740_995L;
    private static final long ID_4 = 9_007_199_254_740_996L;
    private static final long ID_5 = 9_007_199_254_740_997L;
    private static final Instant STARTED_AT = Instant.parse("2026-09-16T06:00:00.123456Z");
    private static final Instant FINISHED_AT = Instant.parse("2026-09-16T06:00:01.654321Z");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @TestFactory
    Stream<DynamicTest> allStageThreePayloadsRoundTripWithoutPrecisionLoss() {
        return samples().map(sample -> DynamicTest.dynamicTest(sample.name(), () -> verifyRoundTrip(sample)));
    }

    private void verifyRoundTrip(ContractSample sample) throws Exception {
        String json = objectMapper.writeValueAsString(sample.payload());
        Object restored = objectMapper.readValue(json, sample.type());
        JsonNode tree = objectMapper.readTree(json);

        assertThat(restored).isEqualTo(sample.payload());
        for (RecordComponent component : sample.type().getRecordComponents()) {
            Object value = component.getAccessor().invoke(sample.payload());
            JsonNode node = tree.get(component.getName());
            if (isBusinessId(component) && value != null) {
                assertThat(node.isTextual()).as(sample.name() + "." + component.getName()).isTrue();
                assertThat(node.textValue()).isEqualTo(value.toString());
            } else if (component.getType() == BigDecimal.class) {
                BigDecimal amount = (BigDecimal) value;
                assertThat(node.isNumber()).as(sample.name() + "." + component.getName()).isTrue();
                assertThat(json).contains("\"" + component.getName() + "\":" + amount.toPlainString());
                assertThat(((BigDecimal) component.getAccessor().invoke(restored)).scale()).isEqualTo(2);
            } else if (component.getType() == Instant.class) {
                Instant instant = (Instant) value;
                assertThat(node.isTextual()).as(sample.name() + "." + component.getName()).isTrue();
                assertThat(node.textValue()).isEqualTo(instant.toString()).endsWith("Z");
            }
        }
    }

    private static boolean isBusinessId(RecordComponent component) {
        return component.getName().endsWith("Id")
                && (component.getType() == long.class || component.getType() == Long.class);
    }

    private static Stream<ContractSample> samples() {
        return Stream.of(
                sample(new BidAcceptedEvent(
                        ID_1, ID_2, ID_3, money("2333.00"), 7, STARTED_AT)),
                sample(new CloseAuctionCommand(ID_1, FINISHED_AT)),
                sample(new AuctionClosedSoldEvent(
                        ID_1, ID_2, "限量收藏品", ID_3, ID_4, ID_5,
                        "REGISTRATION:9007199254740997", money("1000.00"), money("2333.00"),
                        STARTED_AT, FINISHED_AT)),
                sample(new AuctionClosedUnsoldEvent(
                        ID_1, ID_2, "限量收藏品", ID_3, STARTED_AT, FINISHED_AT)),
                sample(new DepositSettlementRequestedEvent(
                        DepositSettlementType.CAPTURE, ID_1, ID_2, ID_3,
                        "REGISTRATION:9007199254740997", money("1000.00"), money("2333.00"))),
                sample(new WalletHoldSettledEvent(
                        DepositSettlementType.CAPTURE, WalletHoldSettlementStatus.CAPTURED,
                        ID_1, ID_2, ID_3, "REGISTRATION:9007199254740997",
                        money("1000.00"), money("2333.00"), money("1000.00"), money("0.00"),
                        FINISHED_AT)),
                sample(new OrderPaymentTimeoutCommand(ID_1, FINISHED_AT)),
                sample(new OrderPaidEvent(
                        ID_1, "ORDER:9007199254740993", ID_2, ID_3, ID_4,
                        "PAYMENT:9007199254740993", money("2333.00"), money("1000.00"),
                        money("1333.00"), FINISHED_AT)),
                sample(new OrderPaymentTimedOutEvent(
                        ID_1, "ORDER:9007199254740993", ID_2, ID_3, ID_4,
                        money("2333.00"), money("1000.00"), money("1333.00"),
                        STARTED_AT, FINISHED_AT)),
                sample(new SellerCreditRequestedEvent(
                        SellerCreditReason.SALE_PROCEEDS, "CREDIT:ORDER:9007199254740993",
                        ID_1, "ORDER:9007199254740993", ID_2, ID_3,
                        money("2333.00"), money("1000.00"), money("2333.00"),
                        STARTED_AT, FINISHED_AT)),
                sample(new SellerCreditedEvent(
                        SellerCreditReason.SALE_PROCEEDS, "CREDIT:ORDER:9007199254740993",
                        ID_1, "ORDER:9007199254740993", ID_2, ID_3,
                        money("2333.00"), FINISHED_AT))
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> ContractSample sample(T payload) {
        return new ContractSample(payload.getClass().getSimpleName(), payload, (Class<T>) payload.getClass());
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private record ContractSample(String name, Object payload, Class<?> type) {
    }
}
