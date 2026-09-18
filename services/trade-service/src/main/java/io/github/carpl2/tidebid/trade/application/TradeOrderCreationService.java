package io.github.carpl2.tidebid.trade.application;

import io.github.carpl2.tidebid.contracts.AuctionClosedSoldEvent;
import io.github.carpl2.tidebid.trade.application.port.TradeIdGenerator;
import io.github.carpl2.tidebid.trade.application.port.TradeOrderRepository;
import io.github.carpl2.tidebid.trade.domain.TradeOrder;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.JdbcTradeOutboxRepository;
import io.github.carpl2.tidebid.trade.infrastructure.messaging.TradeOutboxEventFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@Profile({"local-db", "nacos"})
public class TradeOrderCreationService {

    private final TradeOrderRepository orders;
    private final TradeIdGenerator ids;
    private final JdbcTradeOutboxRepository outbox;
    private final TradeOutboxEventFactory events;
    private final Clock clock;

    public TradeOrderCreationService(
            TradeOrderRepository orders,
            TradeIdGenerator ids,
            JdbcTradeOutboxRepository outbox,
            TradeOutboxEventFactory events,
            Clock clock
    ) {
        this.orders = orders;
        this.ids = ids;
        this.outbox = outbox;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public TradeOrder createFrom(AuctionClosedSoldEvent source, String traceId) {
        TradeOrder existing = orders.findByAuctionId(source.auctionId()).orElse(null);
        if (existing != null) {
            validateReplay(existing, source);
            return existing;
        }

        var now = clock.instant();
        TradeOrder candidate = TradeOrder.pendingDeposit(ids.nextId(), source, now);
        if (!orders.insertIfAbsent(candidate)) {
            TradeOrder concurrent = orders.findByAuctionIdForUpdate(source.auctionId())
                    .orElseThrow(() -> new IllegalStateException("concurrent order insert is not visible"));
            validateReplay(concurrent, source);
            return concurrent;
        }

        outbox.enqueue(events.captureWinnerDeposit(candidate, source, traceId, now), now);
        return candidate;
    }

    private static void validateReplay(TradeOrder existing, AuctionClosedSoldEvent source) {
        if (!existing.hasSameAuctionSnapshot(source)) {
            throw new OrderCreationConflictException(
                    "auction result conflicts with the immutable order snapshot for auction " + source.auctionId());
        }
    }

    public static final class OrderCreationConflictException extends RuntimeException {
        public OrderCreationConflictException(String message) {
            super(message);
        }
    }
}
