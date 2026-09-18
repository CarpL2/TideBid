package io.github.carpl2.tidebid.trade.api;

import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.security.JwtAccessTokenVerifier;
import io.github.carpl2.tidebid.security.JwtClaims;
import io.github.carpl2.tidebid.security.Role;
import io.github.carpl2.tidebid.trade.application.PaymentAttemptSnapshot;
import io.github.carpl2.tidebid.trade.application.TradeOrderQueryService;
import io.github.carpl2.tidebid.trade.application.TradeOrderSnapshot;
import io.github.carpl2.tidebid.trade.application.TradePaymentService;
import io.github.carpl2.tidebid.trade.domain.TradeErrorCode;
import io.github.carpl2.tidebid.trade.infrastructure.security.TradeSecurityConfiguration;
import io.github.carpl2.tidebid.web.CommonWebAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({TradeOrderController.class, TradePaymentController.class})
@ActiveProfiles("local-db")
@Import({TradeSecurityConfiguration.class, CommonWebAutoConfiguration.class})
class TradeOrderControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-19T02:00:00Z");
    private static final long ORDER_ID = 9_007_199_254_740_993L;
    private static final long BUYER_ID = 42L;
    private static final long SELLER_ID = 84L;

    @Autowired private MockMvc mockMvc;
    @MockitoBean private TradeOrderQueryService queryService;
    @MockitoBean private TradePaymentService paymentService;
    @MockitoBean private JwtAccessTokenVerifier tokenVerifier;

    @BeforeEach
    void authenticateToken() {
        when(tokenVerifier.verify("user-token")).thenReturn(new JwtClaims(
                "trade-user", BUYER_ID, Set.of(Role.USER), NOW, NOW.plusSeconds(1800), "token-id"
        ));
    }

    @Test
    void returnsBuyerPageWithStringIdsMoneyAndUtcTimes() throws Exception {
        when(queryService.findMine(BUYER_ID, 2, 10)).thenReturn(new TradeOrderQueryService.OrderPage(
                2, 10, 11, 2, List.of(order())
        ));

        mockMvc.perform(get("/api/orders/mine")
                        .header("Authorization", "Bearer user-token")
                        .header("X-Trace-Id", "trade-orders-trace-01")
                        .queryParam("page", "2")
                        .queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "trade-orders-trace-01"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items[0].orderId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.items[0].auctionId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.items[0].itemId").value("9007199254740995"))
                .andExpect(jsonPath("$.data.items[0].sellerId").value("84"))
                .andExpect(jsonPath("$.data.items[0].buyerId").value("42"))
                .andExpect(jsonPath("$.data.items[0].finalPrice").value("2333.00"))
                .andExpect(jsonPath("$.data.items[0].capturedDepositAmount").value("1000.00"))
                .andExpect(jsonPath("$.data.items[0].payableAmount").value("1333.00"))
                .andExpect(jsonPath("$.data.items[0].sellerReceivableAmount").value("2333.00"))
                .andExpect(jsonPath("$.data.items[0].paidAt").value("2026-09-19T02:00:00Z"));

        verify(queryService).findMine(BUYER_ID, 2, 10);
    }

    @Test
    void returnsOnlyCurrentUsersSales() throws Exception {
        when(queryService.findSales(BUYER_ID, 1, 20)).thenReturn(new TradeOrderQueryService.OrderPage(
                1, 20, 1, 1, List.of(order())
        ));

        mockMvc.perform(get("/api/orders/sales")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].orderNo").value("TB-ORDER-1"));

        verify(queryService).findSales(BUYER_ID, 1, 20);
    }

    @Test
    void preservesObjectAuthorizationAndInputErrors() throws Exception {
        when(queryService.findAccessible(BUYER_ID, ORDER_ID))
                .thenThrow(new BusinessException(TradeErrorCode.ORDER_FORBIDDEN));

        mockMvc.perform(get("/api/orders/{orderId}", Long.toString(ORDER_ID))
                        .header("Authorization", "Bearer user-token")
                        .header("X-Trace-Id", "trade-orders-trace-02"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TRADE_ORDER_FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").value("trade-orders-trace-02"));

        mockMvc.perform(get("/api/orders/not-a-number")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void paysWithServerAmountAndReturnsTraceableStringContract() throws Exception {
        PaymentAttemptSnapshot payment = new PaymentAttemptSnapshot(
                ORDER_ID + 10, "PAY:9007199254740993", ORDER_ID, BUYER_ID,
                "pay-request-0001", new BigDecimal("1333.00"), "SUCCEEDED", null,
                0, null, NOW, NOW.minusSeconds(1), NOW
        );
        when(paymentService.pay(BUYER_ID, ORDER_ID, "pay-request-0001", "trade-pay-trace-01"))
                .thenReturn(payment);

        mockMvc.perform(post("/api/orders/{orderId}/pay", Long.toString(ORDER_ID))
                        .header("Authorization", "Bearer user-token")
                        .header("X-Request-Id", "pay-request-0001")
                        .header("X-Trace-Id", "trade-pay-trace-01"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "trade-pay-trace-01"))
                .andExpect(jsonPath("$.data.paymentAttemptId").value("9007199254741003"))
                .andExpect(jsonPath("$.data.orderId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.amount").value("1333.00"))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.traceId").value("trade-pay-trace-01"));

        verify(paymentService).pay(BUYER_ID, ORDER_ID, "pay-request-0001", "trade-pay-trace-01");
    }

    @Test
    void rejectsAnonymousAndMissingPaymentIdempotencyKey() throws Exception {
        mockMvc.perform(get("/api/orders/mine"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHENTICATED"));

        mockMvc.perform(post("/api/orders/{orderId}/pay", Long.toString(ORDER_ID))
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        verifyNoInteractions(paymentService);
    }

    private static TradeOrderSnapshot order() {
        return new TradeOrderSnapshot(
                ORDER_ID, "TB-ORDER-1", ORDER_ID + 1, ORDER_ID + 2,
                SELLER_ID, BUYER_ID, "Mechanical keyboard", new BigDecimal("2333.00"),
                new BigDecimal("1000.00"), new BigDecimal("1333.00"), "PAID",
                NOW.plusSeconds(1800), NOW, null, "COMPLETED", new BigDecimal("2333.00"),
                NOW.plusSeconds(1), NOW.minusSeconds(120), NOW.minusSeconds(60), NOW.plusSeconds(1), false
        );
    }
}
