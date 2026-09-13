package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AccountWalletPort;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationResultTransaction;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionRegistrationRecoveryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuctionRegistrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T08:00:00.123456Z");
    private static final long REGISTRATION_ID = 501L;
    private static final long AUCTION_ID = 101L;
    private static final long BIDDER_ID = 401L;
    private static final String REQUEST_ID = "register-request-01";
    private static final String TRACE_ID = "register-trace-01";

    private AuctionRegistrationCreationService creationService;
    private AccountWalletPort accountWallet;
    private AuctionRegistrationResultTransaction resultTransaction;
    private AuctionRegistrationService service;

    @BeforeEach
    void setUp() {
        creationService = mock(AuctionRegistrationCreationService.class);
        accountWallet = mock(AccountWalletPort.class);
        resultTransaction = mock(AuctionRegistrationResultTransaction.class);
        service = new AuctionRegistrationService(
                creationService,
                accountWallet,
                resultTransaction,
                new AuctionRegistrationRecoveryProperties(
                        Duration.ofSeconds(5), Duration.ofMinutes(5), Duration.ofSeconds(30), 50
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void commitsThePendingRegistrationBeforeHoldingAndMarksItRegistered() {
        AuctionRegistration pending = registration(AuctionRegistrationStatus.PENDING_HOLD, null, 0, null, null);
        AuctionRegistration registered = registration(AuctionRegistrationStatus.REGISTERED, null, 1, null, NOW);
        arrangeCreated(pending);
        when(accountWallet.hold(any())).thenReturn(new AccountWalletPort.Held(holdSnapshot()));
        when(resultTransaction.markRegistered(REGISTRATION_ID, NOW)).thenReturn(registered);

        AuctionRegistration result = service.register(command());

        assertThat(result).isSameAs(registered);
        ArgumentCaptor<AccountWalletPort.HoldCommand> captor = ArgumentCaptor.forClass(
                AccountWalletPort.HoldCommand.class
        );
        verify(accountWallet).hold(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new AccountWalletPort.HoldCommand(
                "REGISTRATION:" + REGISTRATION_ID,
                BIDDER_ID,
                new BigDecimal("50.00"),
                REQUEST_ID,
                TRACE_ID
        ));
        verify(resultTransaction).markRegistered(REGISTRATION_ID, NOW);
        InOrder callOrder = inOrder(creationService, accountWallet, resultTransaction);
        callOrder.verify(creationService).createPending(any());
        callOrder.verify(accountWallet).hold(any());
        callOrder.verify(resultTransaction).markRegistered(REGISTRATION_ID, NOW);
    }

    @Test
    void marksADeterministicRejectionFailedWithOnlyTheStableCode() {
        AuctionRegistration pending = registration(AuctionRegistrationStatus.PENDING_HOLD, null, 0, null, null);
        String errorCode = "ACCOUNT_WALLET_INSUFFICIENT_BALANCE";
        AuctionRegistration failed = registration(AuctionRegistrationStatus.FAILED, errorCode, 1, null, null);
        arrangeCreated(pending);
        when(accountWallet.hold(any())).thenReturn(new AccountWalletPort.Rejected(errorCode));
        when(resultTransaction.markFailed(REGISTRATION_ID, errorCode, NOW)).thenReturn(failed);

        assertThat(service.register(command())).isSameAs(failed);
        verify(resultTransaction).markFailed(REGISTRATION_ID, errorCode, NOW);
        verify(resultTransaction, never()).scheduleRetry(anyLong(), any(), any());
    }

    @Test
    void keepsAnUnknownResultPendingAndSchedulesTheInitialRetry() {
        AuctionRegistration pending = registration(AuctionRegistrationStatus.PENDING_HOLD, null, 0, null, null);
        Instant retryAt = NOW.plusSeconds(5);
        AuctionRegistration scheduled = registration(
                AuctionRegistrationStatus.PENDING_HOLD, null, 1, retryAt, null
        );
        arrangeCreated(pending);
        when(accountWallet.hold(any())).thenReturn(
                new AccountWalletPort.Unknown("AUCTION_ACCOUNT_SERVICE_UNAVAILABLE")
        );
        when(resultTransaction.scheduleRetry(REGISTRATION_ID, NOW, retryAt)).thenReturn(scheduled);

        assertThat(service.register(command())).isSameAs(scheduled);
        verify(resultTransaction).scheduleRetry(REGISTRATION_ID, NOW, retryAt);
        verify(resultTransaction, never()).markFailed(anyLong(), any(), any());
    }

    @Test
    void returnsAnExistingFinalRegistrationWithoutCallingAccountAgain() {
        AuctionRegistration registered = registration(AuctionRegistrationStatus.REGISTERED, null, 1, null, NOW);
        arrangeCreated(registered);

        assertThat(service.register(command())).isSameAs(registered);
        verify(accountWallet, never()).hold(any());
        verify(resultTransaction, never()).markRegistered(anyLong(), any());
    }

    @Test
    void validatesRequestAndTraceIdsBeforeCreatingLocalState() {
        assertThatThrownBy(() -> service.register(
                new AuctionRegistrationService.RegisterCommand(BIDDER_ID, AUCTION_ID, "bad", TRACE_ID)
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestId");
        assertThatThrownBy(() -> service.register(
                new AuctionRegistrationService.RegisterCommand(BIDDER_ID, AUCTION_ID, REQUEST_ID, "bad")
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("traceId");
        verify(creationService, never()).createPending(any());
    }

    private void arrangeCreated(AuctionRegistration registration) {
        when(creationService.createPending(any())).thenReturn(
                new AuctionRegistrationCreationService.RegistrationCreationResult(registration, true)
        );
    }

    private static AuctionRegistrationService.RegisterCommand command() {
        return new AuctionRegistrationService.RegisterCommand(BIDDER_ID, AUCTION_ID, REQUEST_ID, TRACE_ID);
    }

    private static AccountWalletPort.HoldSnapshot holdSnapshot() {
        return new AccountWalletPort.HoldSnapshot(
                601L,
                "REGISTRATION:" + REGISTRATION_ID,
                BIDDER_ID,
                new BigDecimal("50.00"),
                "HELD",
                0,
                NOW,
                NOW
        );
    }

    private static AuctionRegistration registration(
            AuctionRegistrationStatus status,
            String failureCode,
            int attemptCount,
            Instant nextRetryAt,
            Instant registeredAt
    ) {
        return new AuctionRegistration(
                REGISTRATION_ID,
                "REGISTRATION:" + REGISTRATION_ID,
                AUCTION_ID,
                BIDDER_ID,
                new BigDecimal("50.00"),
                status,
                failureCode,
                attemptCount,
                nextRetryAt,
                attemptCount == 0 ? null : NOW,
                null,
                null,
                registeredAt,
                attemptCount,
                NOW.minusSeconds(10),
                NOW
        );
    }
}
