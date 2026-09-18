package io.github.carpl2.tidebid.trade.infrastructure.client;

import feign.FeignException;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.trade.application.port.AccountDebitPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local-db", "nacos"})
public class FeignAccountDebitAdapter implements AccountDebitPort {

    private final AccountDebitFeignClient client;

    public FeignAccountDebitAdapter(AccountDebitFeignClient client) {
        this.client = client;
    }

    @Override
    public DebitResult debit(DebitCommand command) {
        try {
            ApiResponse<AccountDebitResponse> response = client.debit(
                    command.requestId(), command.traceId(), new AccountDebitRequest(
                            command.paymentNo(), Long.toString(command.buyerId()),
                            Long.toString(command.orderId()), command.amount()));
            return result(response, command);
        } catch (FeignException | InvalidDebitResponseException exception) {
            return new Unknown();
        }
    }

    @Override
    public DebitLookup lookup(DebitLookupQuery query) {
        try {
            ApiResponse<AccountDebitResponse> response = client.find(query.paymentNo(), query.traceId());
            return new Found(result(response, query.paymentNo()));
        } catch (FeignException.NotFound exception) {
            return new Missing();
        } catch (FeignException | InvalidDebitResponseException exception) {
            return new LookupUnknown();
        }
    }

    private static DebitResult result(ApiResponse<AccountDebitResponse> response, DebitCommand command) {
        if (response == null || !ApiResponse.SUCCESS_CODE.equals(response.code()) || response.data() == null) {
            throw new InvalidDebitResponseException();
        }
        AccountDebitResponse data = response.data();
        try {
            long buyerId = Long.parseLong(data.userId());
            long orderId = Long.parseLong(data.orderId());
            if (!command.paymentNo().equals(data.paymentNo()) || buyerId != command.buyerId()
                    || orderId != command.orderId() || data.amount().compareTo(command.amount()) != 0
                    || data.decidedAt() == null) {
                throw new InvalidDebitResponseException();
            }
            return switch (data.status()) {
                case "SUCCEEDED" -> {
                    if (data.failureCode() != null) throw new InvalidDebitResponseException();
                    yield new Succeeded(data.paymentNo(), buyerId, orderId, data.amount(), data.decidedAt());
                }
                case "REJECTED" -> {
                    if (data.failureCode() == null) throw new InvalidDebitResponseException();
                    yield new Rejected(data.paymentNo(), buyerId, orderId, data.amount(),
                            data.failureCode(), data.decidedAt());
                }
                default -> throw new InvalidDebitResponseException();
            };
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidDebitResponseException();
        }
    }

    private static DebitResult result(ApiResponse<AccountDebitResponse> response, String paymentNo) {
        if (response == null || !ApiResponse.SUCCESS_CODE.equals(response.code()) || response.data() == null) {
            throw new InvalidDebitResponseException();
        }
        AccountDebitResponse data = response.data();
        try {
            long buyerId = Long.parseLong(data.userId());
            long orderId = Long.parseLong(data.orderId());
            if (!paymentNo.equals(data.paymentNo()) || buyerId <= 0 || orderId <= 0
                    || data.amount() == null || data.amount().signum() <= 0 || data.decidedAt() == null) {
                throw new InvalidDebitResponseException();
            }
            return switch (data.status()) {
                case "SUCCEEDED" -> {
                    if (data.failureCode() != null) throw new InvalidDebitResponseException();
                    yield new Succeeded(data.paymentNo(), buyerId, orderId, data.amount(), data.decidedAt());
                }
                case "REJECTED" -> {
                    if (data.failureCode() == null) throw new InvalidDebitResponseException();
                    yield new Rejected(data.paymentNo(), buyerId, orderId, data.amount(),
                            data.failureCode(), data.decidedAt());
                }
                default -> throw new InvalidDebitResponseException();
            };
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidDebitResponseException();
        }
    }

    private static final class InvalidDebitResponseException extends RuntimeException {
    }
}
