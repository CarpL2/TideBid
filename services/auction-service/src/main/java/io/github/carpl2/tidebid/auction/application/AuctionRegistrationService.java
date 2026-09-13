package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AccountWalletPort;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationResultTransaction;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionRegistrationRecoveryProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@Profile({"local-db", "nacos"})
public class AuctionRegistrationService {

    private final AuctionRegistrationCreationService creationService;
    private final AccountWalletPort accountWallet;
    private final AuctionRegistrationResultTransaction resultTransaction;
    private final AuctionRegistrationRecoveryProperties recoveryProperties;
    private final Clock clock;

    public AuctionRegistrationService(
            AuctionRegistrationCreationService creationService,
            AccountWalletPort accountWallet,
            AuctionRegistrationResultTransaction resultTransaction,
            AuctionRegistrationRecoveryProperties recoveryProperties,
            Clock clock
    ) {
        this.creationService = creationService;
        this.accountWallet = accountWallet;
        this.resultTransaction = resultTransaction;
        this.recoveryProperties = recoveryProperties;
        this.clock = clock;
    }

    public AuctionRegistration register(RegisterCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        String requestId = AccountWalletPort.requireRequestId(command.requestId());
        String traceId = AccountWalletPort.requireTraceId(command.traceId());
        AuctionRegistration registration = creationService.createPending(
                new AuctionRegistrationCreationService.CreateRegistrationCommand(
                        command.bidderId(), command.auctionId()
                )
        ).registration();
        if (registration.status() != AuctionRegistrationStatus.PENDING_HOLD) {
            return registration;
        }

        Instant attemptedAt = now();
        AccountWalletPort.HoldAttempt attempt = accountWallet.hold(new AccountWalletPort.HoldCommand(
                registration.registrationNo(),
                registration.bidderId(),
                registration.depositAmount(),
                requestId,
                traceId
        ));
        if (attempt instanceof AccountWalletPort.Held) {
            return resultTransaction.markRegistered(registration.id(), attemptedAt);
        }
        if (attempt instanceof AccountWalletPort.Rejected rejected) {
            return resultTransaction.markFailed(registration.id(), rejected.errorCode(), attemptedAt);
        }
        return resultTransaction.scheduleRetry(
                registration.id(),
                attemptedAt,
                attemptedAt.plus(recoveryProperties.initialRetryDelay())
        );
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public record RegisterCommand(long bidderId, long auctionId, String requestId, String traceId) {
    }
}
