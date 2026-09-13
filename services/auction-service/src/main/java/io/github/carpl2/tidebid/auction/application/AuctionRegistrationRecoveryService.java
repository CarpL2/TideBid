package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AccountWalletPort;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRecoveryTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationResultTransaction;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionRegistrationRecoveryProperties;
import io.github.carpl2.tidebid.core.TraceIds;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@Profile({"local-db", "nacos"})
public class AuctionRegistrationRecoveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuctionRegistrationRecoveryService.class);
    private static final String IDEMPOTENCY_CONFLICT = "ACCOUNT_WALLET_HOLD_IDEMPOTENCY_CONFLICT";

    private final AuctionRegistrationRecoveryTransaction recoveryTransaction;
    private final AuctionRegistrationResultTransaction resultTransaction;
    private final AccountWalletPort accountWallet;
    private final AuctionRegistrationRecoveryProperties properties;
    private final Clock clock;

    public AuctionRegistrationRecoveryService(
            AuctionRegistrationRecoveryTransaction recoveryTransaction,
            AuctionRegistrationResultTransaction resultTransaction,
            AccountWalletPort accountWallet,
            AuctionRegistrationRecoveryProperties properties,
            Clock clock
    ) {
        this.recoveryTransaction = recoveryTransaction;
        this.resultTransaction = resultTransaction;
        this.accountWallet = accountWallet;
        this.properties = properties;
        this.clock = clock;
    }

    public RecoveryResult recoverBatch(String leaseOwner) {
        Instant attemptedAt = now();
        List<AuctionRegistration> claimed = recoveryTransaction.claimDue(
                attemptedAt,
                leaseOwner,
                attemptedAt.plus(properties.leaseDuration()),
                properties.batchSize()
        );
        int registered = 0;
        int failed = 0;
        int pending = 0;
        for (AuctionRegistration registration : claimed) {
            RecoveryOutcome outcome = recover(registration, attemptedAt);
            switch (outcome) {
                case REGISTERED -> registered++;
                case FAILED -> failed++;
                case PENDING -> pending++;
            }
        }
        return new RecoveryResult(claimed.size(), registered, failed, pending);
    }

    private RecoveryOutcome recover(AuctionRegistration registration, Instant attemptedAt) {
        String traceId = TraceIds.create();
        AccountWalletPort.HoldLookup lookup = lookup(registration.registrationNo(), traceId);
        if (lookup instanceof AccountWalletPort.Found found) {
            if (!matches(found.hold(), registration)) {
                return outcome(resultTransaction.markFailed(
                        registration.id(), IDEMPOTENCY_CONFLICT, attemptedAt
                ));
            }
            return outcome(resultTransaction.markRegistered(registration.id(), attemptedAt));
        }
        if (lookup instanceof AccountWalletPort.Missing) {
            return retryHold(registration, attemptedAt, traceId);
        }
        return scheduleRetry(registration, attemptedAt);
    }

    private RecoveryOutcome retryHold(
            AuctionRegistration registration,
            Instant attemptedAt,
            String traceId
    ) {
        AccountWalletPort.HoldAttempt attempt = hold(registration, traceId);
        if (attempt instanceof AccountWalletPort.Held held) {
            if (!matches(held.hold(), registration)) {
                return outcome(resultTransaction.markFailed(
                        registration.id(), IDEMPOTENCY_CONFLICT, attemptedAt
                ));
            }
            return outcome(resultTransaction.markRegistered(registration.id(), attemptedAt));
        }
        if (attempt instanceof AccountWalletPort.Rejected rejected) {
            return outcome(resultTransaction.markFailed(
                    registration.id(), rejected.errorCode(), attemptedAt
            ));
        }
        return scheduleRetry(registration, attemptedAt);
    }

    private AccountWalletPort.HoldLookup lookup(String registrationNo, String traceId) {
        try {
            AccountWalletPort.HoldLookup result = accountWallet.findByHoldNo(registrationNo, traceId);
            return result == null
                    ? new AccountWalletPort.Unknown("AUCTION_ACCOUNT_SERVICE_UNAVAILABLE")
                    : result;
        } catch (RuntimeException exception) {
            return new AccountWalletPort.Unknown("AUCTION_ACCOUNT_SERVICE_UNAVAILABLE");
        }
    }

    private AccountWalletPort.HoldAttempt hold(AuctionRegistration registration, String traceId) {
        try {
            AccountWalletPort.HoldAttempt result = accountWallet.hold(new AccountWalletPort.HoldCommand(
                    registration.registrationNo(),
                    registration.bidderId(),
                    registration.depositAmount(),
                    "recovery-" + registration.id(),
                    traceId
            ));
            return result == null
                    ? new AccountWalletPort.Unknown("AUCTION_ACCOUNT_SERVICE_UNAVAILABLE")
                    : result;
        } catch (RuntimeException exception) {
            return new AccountWalletPort.Unknown("AUCTION_ACCOUNT_SERVICE_UNAVAILABLE");
        }
    }

    private RecoveryOutcome scheduleRetry(AuctionRegistration registration, Instant attemptedAt) {
        int completedAttempts = registration.attemptCount() + 1;
        if (completedAttempts >= properties.maximumAttempts()) {
            AuctionRegistration exhausted = resultTransaction.markRecoveryExhausted(
                    registration.id(), attemptedAt
            );
            LOGGER.error(
                    "Registration recovery exhausted registrationId={} attempts={} status={}",
                    registration.id(), completedAttempts, exhausted.status()
            );
            return outcome(exhausted);
        }
        return outcome(resultTransaction.scheduleRetry(
                registration.id(),
                attemptedAt,
                attemptedAt.plus(retryDelay(completedAttempts))
        ));
    }

    private Duration retryDelay(int completedAttempts) {
        Duration delay = properties.initialRetryDelay();
        for (int attempt = 1;
             attempt < completedAttempts && delay.compareTo(properties.maximumRetryDelay()) < 0;
             attempt++) {
            Duration doubled = delay.multipliedBy(2);
            delay = doubled.compareTo(properties.maximumRetryDelay()) > 0
                    ? properties.maximumRetryDelay()
                    : doubled;
        }
        return delay;
    }

    private static boolean matches(
            AccountWalletPort.HoldSnapshot hold,
            AuctionRegistration registration
    ) {
        return hold.holdNo().equals(registration.registrationNo())
                && hold.userId() == registration.bidderId()
                && hold.amount().compareTo(registration.depositAmount()) == 0
                && "HELD".equals(hold.status());
    }

    private static RecoveryOutcome outcome(AuctionRegistration registration) {
        return switch (registration.status()) {
            case REGISTERED -> RecoveryOutcome.REGISTERED;
            case FAILED -> RecoveryOutcome.FAILED;
            case PENDING_HOLD -> RecoveryOutcome.PENDING;
        };
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private enum RecoveryOutcome {
        REGISTERED,
        FAILED,
        PENDING
    }

    public record RecoveryResult(int claimed, int registered, int failed, int pending) {
        public RecoveryResult {
            if (claimed < 0 || registered < 0 || failed < 0 || pending < 0
                    || registered + failed + pending != claimed) {
                throw new IllegalArgumentException("recovery result counts are inconsistent");
            }
        }
    }
}
