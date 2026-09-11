package io.github.carpl2.tidebid.auction.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import io.github.carpl2.tidebid.auction.application.port.AccountWalletPort;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.core.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Profile({"local-db", "nacos"})
public class FeignAccountWalletAdapter implements AccountWalletPort {

    private static final String BUSINESS_TYPE = "AUCTION_DEPOSIT";
    private static final String HELD_STATUS = "HELD";
    private static final String REMOTE_HOLD_NOT_FOUND = "ACCOUNT_WALLET_HOLD_NOT_FOUND";
    private static final String UNKNOWN_ERROR = AuctionErrorCode.ACCOUNT_SERVICE_UNAVAILABLE.code();
    private static final Set<String> DETERMINISTIC_REJECTIONS = Set.of(
            "ACCOUNT_NOT_FOUND",
            "ACCOUNT_DISABLED",
            "ACCOUNT_WALLET_NOT_FOUND",
            "ACCOUNT_WALLET_INSUFFICIENT_BALANCE",
            "ACCOUNT_WALLET_HOLD_IDEMPOTENCY_CONFLICT"
    );

    private final AccountWalletFeignClient client;
    private final ObjectMapper objectMapper;

    public FeignAccountWalletAdapter(AccountWalletFeignClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public HoldAttempt hold(HoldCommand command) {
        try {
            ApiResponse<AccountWalletHoldResponse> response = client.hold(
                    command.requestId(),
                    command.traceId(),
                    new AccountWalletHoldRequest(
                            command.holdNo(),
                            Long.toString(command.userId()),
                            BUSINESS_TYPE,
                            command.amount()
                    )
            );
            HoldSnapshot snapshot = snapshot(response);
            if (!matches(snapshot, command)) {
                return unknown();
            }
            return new Held(snapshot);
        } catch (FeignException exception) {
            String errorCode = remoteErrorCode(exception);
            if (DETERMINISTIC_REJECTIONS.contains(errorCode)) {
                return new Rejected(errorCode);
            }
            return unknown();
        } catch (InvalidAccountWalletResponseException exception) {
            return unknown();
        }
    }

    @Override
    public HoldLookup findByHoldNo(String holdNo, String traceId) {
        String normalizedHoldNo = AccountWalletPort.requireHoldNo(holdNo);
        String normalizedTraceId = AccountWalletPort.requireTraceId(traceId);

        try {
            HoldSnapshot snapshot = snapshot(client.findByHoldNo(normalizedHoldNo, normalizedTraceId));
            if (!normalizedHoldNo.equals(snapshot.holdNo())) {
                return unknown();
            }
            return new Found(snapshot);
        } catch (FeignException exception) {
            if (REMOTE_HOLD_NOT_FOUND.equals(remoteErrorCode(exception))) {
                return new Missing();
            }
            return unknown();
        } catch (InvalidAccountWalletResponseException exception) {
            return unknown();
        }
    }

    private HoldSnapshot snapshot(ApiResponse<AccountWalletHoldResponse> response) {
        if (response == null || !ApiResponse.SUCCESS_CODE.equals(response.code()) || response.data() == null) {
            throw new InvalidAccountWalletResponseException();
        }
        AccountWalletHoldResponse data = response.data();
        try {
            if (!BUSINESS_TYPE.equals(data.businessType()) || !HELD_STATUS.equals(data.status())) {
                throw new InvalidAccountWalletResponseException();
            }
            return new HoldSnapshot(
                    Long.parseLong(data.holdId()),
                    data.holdNo(),
                    Long.parseLong(data.userId()),
                    data.amount(),
                    data.status(),
                    data.version(),
                    data.createdAt(),
                    data.updatedAt()
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidAccountWalletResponseException(exception);
        }
    }

    private String remoteErrorCode(FeignException exception) {
        try {
            JsonNode body = objectMapper.readTree(exception.contentUTF8());
            String code = body.path("code").asText("");
            return code.length() <= 64 ? code : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean matches(HoldSnapshot snapshot, HoldCommand command) {
        return snapshot.holdNo().equals(command.holdNo())
                && snapshot.userId() == command.userId()
                && snapshot.amount().compareTo(command.amount()) == 0
                && HELD_STATUS.equals(snapshot.status());
    }

    private static Unknown unknown() {
        return new Unknown(UNKNOWN_ERROR);
    }

    private static final class InvalidAccountWalletResponseException extends RuntimeException {
        private InvalidAccountWalletResponseException() {
        }

        private InvalidAccountWalletResponseException(Throwable cause) {
            super(cause);
        }
    }
}
