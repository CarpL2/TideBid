package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionClosingTransaction;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@Profile({"local-db", "nacos"})
public class AuctionClosingService {

    private final AuctionClosingTransaction transaction;
    private final Clock clock;

    public AuctionClosingService(AuctionClosingTransaction transaction, Clock clock) {
        this.transaction = transaction;
        this.clock = clock;
    }

    public AuctionClosingTransaction.CloseResult fromMessage(
            long auctionId,
            Instant expectedEndAt,
            UUID eventId,
            String traceId
    ) {
        return transaction.close(new AuctionClosingTransaction.CloseCommand(
                auctionId, expectedEndAt, clock.instant(), AuctionClosingTransaction.TriggerSource.MESSAGE,
                eventId, traceId));
    }

    public AuctionClosingTransaction.CloseResult fromDatabaseScan(long auctionId, Instant expectedEndAt) {
        return transaction.close(new AuctionClosingTransaction.CloseCommand(
                auctionId, expectedEndAt, clock.instant(), AuctionClosingTransaction.TriggerSource.DATABASE_SCAN,
                null, null));
    }
}
