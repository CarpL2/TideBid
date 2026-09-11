package io.github.carpl2.tidebid.auction.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import io.github.carpl2.tidebid.auction.application.port.AccountWalletPort;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.core.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeignAccountWalletAdapterTest {

    private static final String HOLD_NO = "registration:1001";
    private static final String REQUEST_ID = "request_1001";
    private static final String TRACE_ID = "trace_1001";
    private static final Instant CREATED_AT = Instant.parse("2026-09-12T00:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-12T00:00:01Z");

    private StubAccountWalletFeignClient client;
    private FeignAccountWalletAdapter adapter;

    @BeforeEach
    void setUp() {
        client = new StubAccountWalletFeignClient();
        adapter = new FeignAccountWalletAdapter(client, new ObjectMapper());
    }

    @Test
    void sendsStableBusinessKeyAndPropagatesRequestAndTraceIds() {
        client.holdResponse = success(response(HOLD_NO, "42", new BigDecimal("100.00")));

        AccountWalletPort.HoldAttempt result = adapter.hold(command());

        AccountWalletPort.Held held = (AccountWalletPort.Held) result;
        assertThat(held.hold().holdNo()).isEqualTo(HOLD_NO);
        assertThat(held.hold().userId()).isEqualTo(42L);
        assertThat(held.hold().amount()).isEqualByComparingTo("100.00");
        assertThat(client.capturedRequestId).isEqualTo(REQUEST_ID);
        assertThat(client.capturedTraceId).isEqualTo(TRACE_ID);
        assertThat(client.capturedRequest)
                .extracting(
                        AccountWalletHoldRequest::holdNo,
                        AccountWalletHoldRequest::userId,
                        AccountWalletHoldRequest::businessType
                )
                .containsExactly(HOLD_NO, "42", "AUCTION_DEPOSIT");
    }

    @Test
    void mapsKnownBusinessFailureToDeterministicRejection() {
        client.holdFailure = remoteFailure(
                409,
                "{\"code\":\"ACCOUNT_WALLET_INSUFFICIENT_BALANCE\",\"message\":\"insufficient\"}"
        );

        AccountWalletPort.HoldAttempt result = adapter.hold(command());

        assertThat(result).isEqualTo(new AccountWalletPort.Rejected("ACCOUNT_WALLET_INSUFFICIENT_BALANCE"));
    }

    @Test
    void mapsServerAndNetworkFailuresToUnknownWithoutRetryingInAdapter() {
        client.holdFailure = remoteFailure(503, "{\"code\":\"COMMON_SERVICE_UNAVAILABLE\"}");
        assertThat(adapter.hold(command())).isEqualTo(unknown());

        client.holdFailure = new TestFeignException(-1, "connection refused", new byte[0]);
        assertThat(adapter.hold(command())).isEqualTo(unknown());
        assertThat(client.holdCalls).isEqualTo(2);
    }

    @Test
    void treatsMismatchedOrMalformedSuccessAsUnknown() {
        client.holdResponse = success(response("registration:someone-else", "42", new BigDecimal("100.00")));
        assertThat(adapter.hold(command())).isEqualTo(unknown());

        client.holdResponse = success(response(HOLD_NO, "not-a-number", new BigDecimal("100.00")));
        assertThat(adapter.hold(command())).isEqualTo(unknown());
    }

    @Test
    void supportsRecoveryLookupWithFoundMissingAndUnknownResults() {
        client.lookupResponse = success(response(HOLD_NO, "42", new BigDecimal("100.00")));
        AccountWalletPort.Found found = (AccountWalletPort.Found) adapter.findByHoldNo(HOLD_NO, TRACE_ID);
        assertThat(found.hold().holdNo()).isEqualTo(HOLD_NO);
        assertThat(client.capturedLookupTraceId).isEqualTo(TRACE_ID);

        client.lookupFailure = remoteFailure(
                404,
                "{\"code\":\"ACCOUNT_WALLET_HOLD_NOT_FOUND\",\"message\":\"missing\"}"
        );
        assertThat(adapter.findByHoldNo(HOLD_NO, TRACE_ID)).isEqualTo(new AccountWalletPort.Missing());

        client.lookupFailure = remoteFailure(503, "{\"code\":\"COMMON_SERVICE_UNAVAILABLE\"}");
        assertThat(adapter.findByHoldNo(HOLD_NO, TRACE_ID)).isEqualTo(unknown());
    }

    @Test
    void validatesLookupIdentifiersBeforeCallingRemoteService() {
        assertThatThrownBy(() -> adapter.findByHoldNo("bad hold number", TRACE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holdNo");
        assertThatThrownBy(() -> adapter.findByHoldNo(HOLD_NO, "bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traceId");
        assertThat(client.lookupCalls).isZero();
    }

    private static AccountWalletPort.HoldCommand command() {
        return new AccountWalletPort.HoldCommand(
                HOLD_NO,
                42L,
                new BigDecimal("100.00"),
                REQUEST_ID,
                TRACE_ID
        );
    }

    private static AccountWalletHoldResponse response(String holdNo, String userId, BigDecimal amount) {
        return new AccountWalletHoldResponse(
                "7",
                holdNo,
                userId,
                "AUCTION_DEPOSIT",
                amount,
                "HELD",
                0L,
                CREATED_AT,
                UPDATED_AT
        );
    }

    private static ApiResponse<AccountWalletHoldResponse> success(AccountWalletHoldResponse response) {
        return ApiResponse.success(response, TRACE_ID);
    }

    private static AccountWalletPort.Unknown unknown() {
        return new AccountWalletPort.Unknown(AuctionErrorCode.ACCOUNT_SERVICE_UNAVAILABLE.code());
    }

    private static FeignException remoteFailure(int status, String body) {
        return new TestFeignException(status, "remote failure", body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class TestFeignException extends FeignException {
        private TestFeignException(int status, String message, byte[] body) {
            super(status, message, body, Map.of());
        }
    }

    private static final class StubAccountWalletFeignClient implements AccountWalletFeignClient {
        private ApiResponse<AccountWalletHoldResponse> holdResponse;
        private ApiResponse<AccountWalletHoldResponse> lookupResponse;
        private FeignException holdFailure;
        private FeignException lookupFailure;
        private String capturedRequestId;
        private String capturedTraceId;
        private String capturedLookupTraceId;
        private AccountWalletHoldRequest capturedRequest;
        private int holdCalls;
        private int lookupCalls;

        @Override
        public ApiResponse<AccountWalletHoldResponse> hold(
                String requestId,
                String traceId,
                AccountWalletHoldRequest request
        ) {
            holdCalls++;
            capturedRequestId = requestId;
            capturedTraceId = traceId;
            capturedRequest = request;
            if (holdFailure != null) {
                FeignException exception = holdFailure;
                holdFailure = null;
                throw exception;
            }
            return holdResponse;
        }

        @Override
        public ApiResponse<AccountWalletHoldResponse> findByHoldNo(String holdNo, String traceId) {
            lookupCalls++;
            capturedLookupTraceId = traceId;
            if (lookupFailure != null) {
                FeignException exception = lookupFailure;
                lookupFailure = null;
                throw exception;
            }
            return lookupResponse;
        }
    }
}
