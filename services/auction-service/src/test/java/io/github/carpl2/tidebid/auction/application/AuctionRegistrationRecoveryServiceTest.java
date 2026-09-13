package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AccountWalletPort;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRecoveryTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationResultTransaction;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionRegistrationRecoveryProperties;
import io.github.carpl2.tidebid.core.TraceIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuctionRegistrationRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T09:00:00.123456Z");
    private static final long REGISTRATION_ID = 501L;
    private static final String REGISTRATION_NO = "REGISTRATION:" + REGISTRATION_ID;
    private static final long BIDDER_ID = 401L;
    private static final BigDecimal DEPOSIT = new BigDecimal("50.00");

    private AuctionRegistrationRecoveryTransaction recoveryTransaction;
    private AuctionRegistrationResultTransaction resultTransaction;
    private AccountWalletPort accountWallet;
    private AuctionRegistrationRecoveryService service;

    @BeforeEach
    void setUp() {
        recoveryTransaction = mock(AuctionRegistrationRecoveryTransaction.class);
        resultTransaction = mock(AuctionRegistrationResultTransaction.class);
        accountWallet = mock(AccountWalletPort.class);
        service = new AuctionRegistrationRecoveryService(
                recoveryTransaction,
                resultTransaction,
                accountWallet,
                new AuctionRegistrationRecoveryProperties(
                        Duration.ofSeconds(5), Duration.ofMinutes(5), Duration.ofSeconds(30),
                        Duration.ofSeconds(2), 50
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void claimsWithAShortLeaseAndConfirmsAnAlreadyExistingHold() {
        AuctionRegistration registration = claimedRegistration(1);
        when(recoveryTransaction.claimDue(NOW, "worker-01", NOW.plusSeconds(30), 50))
                .thenReturn(List.of(registration));
        when(accountWallet.findByHoldNo(anyString(), anyString()))
                .thenReturn(new AccountWalletPort.Found(holdSnapshot(BIDDER_ID, DEPOSIT)));
        when(resultTransaction.markRegistered(REGISTRATION_ID, NOW))
                .thenReturn(finalRegistration(AuctionRegistrationStatus.REGISTERED, null));

        AuctionRegistrationRecoveryService.RecoveryResult result = service.recoverBatch("worker-01");

        assertThat(result).isEqualTo(new AuctionRegistrationRecoveryService.RecoveryResult(1, 1, 0, 0));
        ArgumentCaptor<String> traceId = ArgumentCaptor.forClass(String.class);
        verify(accountWallet).findByHoldNo(org.mockito.ArgumentMatchers.eq(REGISTRATION_NO), traceId.capture());
        assertThat(TraceIds.isValid(traceId.getValue())).isTrue();
        verify(resultTransaction).markRegistered(REGISTRATION_ID, NOW);
        verify(accountWallet, never()).hold(any());
    }

    @Test
    void retriesTheSameHoldOnlyAfterAccountExplicitlyReportsItMissing() {
        arrangeClaimed(claimedRegistration(1));
        when(accountWallet.findByHoldNo(anyString(), anyString())).thenReturn(new AccountWalletPort.Missing());
        when(accountWallet.hold(any())).thenReturn(new AccountWalletPort.Held(holdSnapshot(BIDDER_ID, DEPOSIT)));
        when(resultTransaction.markRegistered(REGISTRATION_ID, NOW))
                .thenReturn(finalRegistration(AuctionRegistrationStatus.REGISTERED, null));

        assertThat(service.recoverBatch("worker-01").registered()).isEqualTo(1);

        ArgumentCaptor<AccountWalletPort.HoldCommand> command =
                ArgumentCaptor.forClass(AccountWalletPort.HoldCommand.class);
        verify(accountWallet).hold(command.capture());
        assertThat(command.getValue().holdNo()).isEqualTo(REGISTRATION_NO);
        assertThat(command.getValue().userId()).isEqualTo(BIDDER_ID);
        assertThat(command.getValue().amount()).isEqualByComparingTo(DEPOSIT);
        assertThat(command.getValue().requestId()).isEqualTo("recovery-" + REGISTRATION_ID);
        assertThat(TraceIds.isValid(command.getValue().traceId())).isTrue();
        verify(resultTransaction).markRegistered(REGISTRATION_ID, NOW);
    }

    @Test
    void keepsAnUnknownLookupPendingWithoutBlindlyHoldingAgain() {
        arrangeClaimed(claimedRegistration(2));
        when(accountWallet.findByHoldNo(anyString(), anyString()))
                .thenReturn(new AccountWalletPort.Unknown("AUCTION_ACCOUNT_SERVICE_UNAVAILABLE"));
        when(resultTransaction.scheduleRetry(REGISTRATION_ID, NOW, NOW.plusSeconds(20)))
                .thenReturn(pendingAfterRetry(3, NOW.plusSeconds(20)));

        AuctionRegistrationRecoveryService.RecoveryResult result = service.recoverBatch("worker-01");

        assertThat(result.pending()).isEqualTo(1);
        verify(accountWallet, never()).hold(any());
        verify(resultTransaction).scheduleRetry(REGISTRATION_ID, NOW, NOW.plusSeconds(20));
    }

    @Test
    void recordsADeterministicRejectionAfterAMissingLookup() {
        arrangeClaimed(claimedRegistration(0));
        when(accountWallet.findByHoldNo(anyString(), anyString())).thenReturn(new AccountWalletPort.Missing());
        when(accountWallet.hold(any())).thenReturn(
                new AccountWalletPort.Rejected("ACCOUNT_WALLET_INSUFFICIENT_BALANCE")
        );
        when(resultTransaction.markFailed(
                REGISTRATION_ID, "ACCOUNT_WALLET_INSUFFICIENT_BALANCE", NOW
        )).thenReturn(finalRegistration(
                AuctionRegistrationStatus.FAILED, "ACCOUNT_WALLET_INSUFFICIENT_BALANCE"
        ));

        AuctionRegistrationRecoveryService.RecoveryResult result = service.recoverBatch("worker-01");

        assertThat(result.failed()).isEqualTo(1);
        verify(resultTransaction).markFailed(
                REGISTRATION_ID, "ACCOUNT_WALLET_INSUFFICIENT_BALANCE", NOW
        );
    }

    @Test
    void rejectsAnExistingHoldWhoseImmutablePayloadDoesNotMatch() {
        arrangeClaimed(claimedRegistration(1));
        when(accountWallet.findByHoldNo(anyString(), anyString())).thenReturn(
                new AccountWalletPort.Found(holdSnapshot(BIDDER_ID + 1, DEPOSIT))
        );
        when(resultTransaction.markFailed(
                REGISTRATION_ID, "ACCOUNT_WALLET_HOLD_IDEMPOTENCY_CONFLICT", NOW
        )).thenReturn(finalRegistration(
                AuctionRegistrationStatus.FAILED, "ACCOUNT_WALLET_HOLD_IDEMPOTENCY_CONFLICT"
        ));

        assertThat(service.recoverBatch("worker-01").failed()).isEqualTo(1);
        verify(resultTransaction).markFailed(
                REGISTRATION_ID, "ACCOUNT_WALLET_HOLD_IDEMPOTENCY_CONFLICT", NOW
        );
        verify(accountWallet, never()).hold(any());
    }

    @Test
    void convertsAnUnexpectedClientFailureIntoABoundedRetry() {
        arrangeClaimed(claimedRegistration(10));
        when(accountWallet.findByHoldNo(anyString(), anyString()))
                .thenThrow(new IllegalStateException("provider details must not escape"));
        when(resultTransaction.scheduleRetry(
                REGISTRATION_ID, NOW, NOW.plus(Duration.ofMinutes(5))
        )).thenReturn(pendingAfterRetry(11, NOW.plus(Duration.ofMinutes(5))));

        assertThat(service.recoverBatch("worker-01").pending()).isEqualTo(1);
        verify(resultTransaction).scheduleRetry(REGISTRATION_ID, NOW, NOW.plus(Duration.ofMinutes(5)));
    }

    private void arrangeClaimed(AuctionRegistration registration) {
        when(recoveryTransaction.claimDue(NOW, "worker-01", NOW.plusSeconds(30), 50))
                .thenReturn(List.of(registration));
    }

    private static AccountWalletPort.HoldSnapshot holdSnapshot(long userId, BigDecimal amount) {
        return new AccountWalletPort.HoldSnapshot(
                601L, REGISTRATION_NO, userId, amount, "HELD", 0, NOW.minusSeconds(10), NOW
        );
    }

    private static AuctionRegistration claimedRegistration(int attemptCount) {
        return new AuctionRegistration(
                REGISTRATION_ID,
                REGISTRATION_NO,
                101L,
                BIDDER_ID,
                DEPOSIT,
                AuctionRegistrationStatus.PENDING_HOLD,
                null,
                attemptCount,
                NOW.minusSeconds(1),
                attemptCount == 0 ? null : NOW.minusSeconds(5),
                "worker-01",
                NOW.plusSeconds(30),
                null,
                2,
                NOW.minusSeconds(60),
                NOW
        );
    }

    private static AuctionRegistration pendingAfterRetry(int attemptCount, Instant nextRetryAt) {
        return new AuctionRegistration(
                REGISTRATION_ID, REGISTRATION_NO, 101L, BIDDER_ID, DEPOSIT,
                AuctionRegistrationStatus.PENDING_HOLD, null, attemptCount, nextRetryAt, NOW,
                null, null, null, 3, NOW.minusSeconds(60), NOW
        );
    }

    private static AuctionRegistration finalRegistration(
            AuctionRegistrationStatus status,
            String failureCode
    ) {
        return new AuctionRegistration(
                REGISTRATION_ID, REGISTRATION_NO, 101L, BIDDER_ID, DEPOSIT,
                status, failureCode, 2, null, NOW, null, null,
                status == AuctionRegistrationStatus.REGISTERED ? NOW : null,
                3, NOW.minusSeconds(60), NOW
        );
    }
}
