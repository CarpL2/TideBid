package io.github.carpl2.tidebid.account.application;

import io.github.carpl2.tidebid.account.application.port.DuplicateWalletHoldException;
import io.github.carpl2.tidebid.account.application.port.WalletBalanceInsufficientException;
import io.github.carpl2.tidebid.account.application.port.WalletHoldRepository;
import io.github.carpl2.tidebid.account.application.port.WalletHoldTransaction;
import io.github.carpl2.tidebid.account.domain.WalletHold;
import io.github.carpl2.tidebid.account.domain.WalletHoldBusinessType;
import io.github.carpl2.tidebid.account.domain.WalletHoldStatus;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletHoldServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-12T00:00:00Z");

    @Test
    void returnsExistingHoldWithoutOpeningAnotherTransaction() {
        WalletHold existing = hold(1L, "REGISTRATION:101", 7L, "200.00");
        AtomicInteger transactionCalls = new AtomicInteger();
        WalletHoldService service = service(
                holdNo -> Optional.of(existing),
                data -> {
                    transactionCalls.incrementAndGet();
                    throw new AssertionError("Transaction must not run for an idempotent retry");
                }
        );

        WalletHold result = service.hold(new HoldWalletFundsCommand(
                " REGISTRATION:101 ",
                7L,
                new BigDecimal("200.0")
        ));

        assertThat(result).isSameAs(existing);
        assertThat(transactionCalls).hasValue(0);
    }

    @Test
    void rejectsExistingHoldWithDifferentPayload() {
        WalletHold existing = hold(1L, "REGISTRATION:102", 7L, "200.00");
        WalletHoldService service = service(
                holdNo -> Optional.of(existing),
                data -> existing
        );

        assertError(
                () -> service.hold(new HoldWalletFundsCommand(
                        "REGISTRATION:102",
                        7L,
                        new BigDecimal("201.00")
                )),
                "ACCOUNT_WALLET_HOLD_IDEMPOTENCY_CONFLICT"
        );
    }

    @Test
    void reloadsWinnerAfterConcurrentDuplicateAndChecksItsPayload() {
        WalletHold winner = hold(2L, "REGISTRATION:103", 8L, "300.00");
        AtomicInteger reads = new AtomicInteger();
        WalletHoldRepository repository = new WalletHoldRepository() {
            @Override
            public Optional<WalletHold> findByHoldNo(String holdNo) {
                return reads.getAndIncrement() == 0 ? Optional.empty() : Optional.of(winner);
            }

            @Override
            public WalletHold insertHeld(
                    String holdNo,
                    long userId,
                    WalletHoldBusinessType businessType,
                    BigDecimal amount
            ) {
                throw new UnsupportedOperationException();
            }
        };
        WalletHoldService service = service(
                repository,
                data -> {
                    throw new DuplicateWalletHoldException(new RuntimeException("race"));
                }
        );

        WalletHold result = service.hold(new HoldWalletFundsCommand(
                "REGISTRATION:103",
                8L,
                new BigDecimal("300.00")
        ));

        assertThat(result).isSameAs(winner);
        assertThat(reads).hasValue(2);
    }

    @Test
    void mapsPersistenceFailuresToStableBusinessErrors() {
        WalletHoldService service = service(
                holdNo -> Optional.empty(),
                data -> {
                    throw new WalletBalanceInsufficientException();
                }
        );

        assertError(
                () -> service.hold(new HoldWalletFundsCommand(
                        "REGISTRATION:104",
                        9L,
                        new BigDecimal("500.00")
                )),
                "ACCOUNT_WALLET_INSUFFICIENT_BALANCE"
        );
    }

    @Test
    void rejectsInvalidInputBeforeReadingPersistence() {
        AtomicReference<String> accessed = new AtomicReference<>();
        WalletHoldService service = service(
                holdNo -> {
                    accessed.set("repository");
                    return Optional.empty();
                },
                data -> {
                    accessed.set("transaction");
                    throw new AssertionError();
                }
        );

        assertError(
                () -> service.hold(new HoldWalletFundsCommand("bad hold no", 1L, BigDecimal.ONE)),
                "COMMON_INVALID_ARGUMENT"
        );
        assertThat(accessed).hasValue(null);
    }

    private static WalletHoldService service(
            WalletHoldRepository repository,
            WalletHoldTransaction transaction
    ) {
        return new WalletHoldService(repository, transaction);
    }

    private static WalletHoldService service(
            java.util.function.Function<String, Optional<WalletHold>> finder,
            WalletHoldTransaction transaction
    ) {
        return service(new WalletHoldRepository() {
            @Override
            public Optional<WalletHold> findByHoldNo(String holdNo) {
                return finder.apply(holdNo);
            }

            @Override
            public WalletHold insertHeld(
                    String holdNo,
                    long userId,
                    WalletHoldBusinessType businessType,
                    BigDecimal amount
            ) {
                throw new UnsupportedOperationException();
            }
        }, transaction);
    }

    private static WalletHold hold(long id, String holdNo, long userId, String amount) {
        return new WalletHold(
                id,
                holdNo,
                userId,
                WalletHoldBusinessType.AUCTION_DEPOSIT,
                new BigDecimal(amount),
                WalletHoldStatus.HELD,
                0,
                CREATED_AT,
                CREATED_AT
        );
    }

    private static void assertError(Runnable action, String expectedCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo(expectedCode));
    }
}
