package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import io.github.carpl2.tidebid.auction.application.port.AuctionClosingTransaction;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.AuctionOutboxEventFactory;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.JdbcAuctionOutboxRepository;
import io.github.carpl2.tidebid.contracts.AuctionClosedSoldEvent;
import io.github.carpl2.tidebid.contracts.AuctionClosedUnsoldEvent;
import io.github.carpl2.tidebid.contracts.DepositSettlementRequestedEvent;
import io.github.carpl2.tidebid.contracts.DepositSettlementType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Repository
@Profile({"local-db", "nacos"})
public class JdbcAuctionClosingTransaction implements AuctionClosingTransaction {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcAuctionClosingTransaction.class);

    private final JdbcTemplate jdbc;
    private final JdbcAuctionOutboxRepository outbox;
    private final AuctionOutboxEventFactory eventFactory;

    public JdbcAuctionClosingTransaction(
            DataSource dataSource,
            JdbcAuctionOutboxRepository outbox,
            AuctionOutboxEventFactory eventFactory
    ) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.outbox = outbox;
        this.eventFactory = eventFactory;
    }

    @Override
    @Transactional
    public CloseResult close(CloseCommand command) {
        SessionSnapshot session = findSession(command.auctionId());
        if (session == null) {
            return CloseResult.NOT_FOUND;
        }
        if (isTerminal(session.status())) {
            return CloseResult.ALREADY_CLOSED;
        }
        if (!session.endAt().equals(command.expectedEndAt())) {
            ensureCurrentCommand(session, command.triggeredAt());
            return CloseResult.END_TIME_CHANGED;
        }
        if (command.triggeredAt().isBefore(session.endAt())) {
            ensureRetryCommand(session, command);
            return CloseResult.TOO_EARLY;
        }
        if (!isCloseable(session.status())) {
            return CloseResult.NOT_ELIGIBLE;
        }

        session = advanceToAwaitingClose(session, command.triggeredAt());
        if (session == null) {
            return CloseResult.LOST_RACE;
        }
        if (isTerminal(session.status())) {
            return CloseResult.ALREADY_CLOSED;
        }
        if (!"AWAITING_CLOSE".equals(session.status())) {
            return CloseResult.NOT_ELIGIBLE;
        }

        CloseResult result = session.bidCount() == 0
                ? closeUnsold(session, command)
                : closeSold(session, command);
        if (result == CloseResult.CLOSED_SOLD || result == CloseResult.CLOSED_UNSOLD) {
            long lagMillis = Math.max(0, Duration.between(session.endAt(), command.triggeredAt()).toMillis());
            LOGGER.info("Auction closed: eventId={}, auctionId={}, result={}, source={}, lagMillis={}",
                    command.sourceEventId(), session.id(), result, command.source(), lagMillis);
        }
        return result;
    }

    private SessionSnapshot advanceToAwaitingClose(SessionSnapshot session, Instant now) {
        if ("AWAITING_CLOSE".equals(session.status())) {
            return session;
        }
        int changed = jdbc.update("""
                UPDATE auction_session
                SET status = 'AWAITING_CLOSE', updated_at = ?, version = version + 1
                WHERE id = ?
                  AND status IN ('SCHEDULED', 'OPEN')
                  AND version = ?
                  AND end_at <= ?
                """, timestamp(now), session.id(), session.version(), timestamp(now));
        if (changed == 1) {
            return findSession(session.id());
        }
        return findSession(session.id());
    }

    private CloseResult closeSold(SessionSnapshot session, CloseCommand command) {
        WinningBid winningBid = jdbc.query("""
                SELECT id, bidder_id, amount, sequence_no
                FROM bid_record
                WHERE auction_id = ?
                ORDER BY sequence_no DESC
                LIMIT 1
                """, (row, rowNumber) -> new WinningBid(
                        row.getLong("id"), row.getLong("bidder_id"), row.getBigDecimal("amount"),
                        row.getLong("sequence_no")), session.id()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Auction bid snapshot is missing"));
        if (winningBid.sequenceNo() != session.bidCount()
                || winningBid.bidderId() != session.currentBidderId()
                || winningBid.amount().compareTo(session.currentPrice()) != 0) {
            throw new IllegalStateException("Auction bid snapshot is inconsistent");
        }

        Registration winner = registrations(session.id()).stream()
                .filter(candidate -> candidate.bidderId() == winningBid.bidderId())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Winning bidder has no registered deposit"));
        int changed = jdbc.update("""
                UPDATE auction_session
                SET status = 'CLOSED_SOLD',
                    winner_id = ?, winning_bid_id = ?, final_price = ?, closed_at = ?,
                    updated_at = ?, version = version + 1
                WHERE id = ? AND status = 'AWAITING_CLOSE' AND version = ? AND end_at <= ?
                  AND bid_count = ? AND current_bidder_id = ? AND current_price = ?
                """,
                winningBid.bidderId(), winningBid.id(), winningBid.amount(), timestamp(command.triggeredAt()),
                timestamp(command.triggeredAt()), session.id(), session.version(), timestamp(command.triggeredAt()),
                session.bidCount(), winningBid.bidderId(), winningBid.amount());
        if (changed != 1) {
            return closeRaceResult(session.id());
        }

        AuctionClosedSoldEvent closed = new AuctionClosedSoldEvent(
                session.id(), session.itemId(), session.itemTitle(), session.sellerId(), winningBid.bidderId(),
                winningBid.id(), winner.holdNo(), session.depositAmount(), winningBid.amount(), session.endAt(),
                command.triggeredAt());
        outbox.enqueue(eventFactory.auctionClosedSold(closed, command.triggeredAt(), command.traceId()),
                command.triggeredAt());
        enqueueLoserReleases(session, winningBid.bidderId(), command);
        return CloseResult.CLOSED_SOLD;
    }

    private CloseResult closeUnsold(SessionSnapshot session, CloseCommand command) {
        int changed = jdbc.update("""
                UPDATE auction_session
                SET status = 'CLOSED_UNSOLD', closed_at = ?, updated_at = ?, version = version + 1
                WHERE id = ? AND status = 'AWAITING_CLOSE' AND version = ? AND end_at <= ?
                  AND bid_count = 0 AND current_price IS NULL AND current_bidder_id IS NULL
                """, timestamp(command.triggeredAt()), timestamp(command.triggeredAt()), session.id(),
                session.version(), timestamp(command.triggeredAt()));
        if (changed != 1) {
            return closeRaceResult(session.id());
        }

        AuctionClosedUnsoldEvent closed = new AuctionClosedUnsoldEvent(
                session.id(), session.itemId(), session.itemTitle(), session.sellerId(), session.endAt(),
                command.triggeredAt());
        outbox.enqueue(eventFactory.auctionClosedUnsold(closed, command.triggeredAt(), command.traceId()),
                command.triggeredAt());
        enqueueLoserReleases(session, null, command);
        return CloseResult.CLOSED_UNSOLD;
    }

    private void enqueueLoserReleases(SessionSnapshot session, Long winnerId, CloseCommand command) {
        for (Registration registration : registrations(session.id())) {
            if (winnerId != null && registration.bidderId() == winnerId) {
                continue;
            }
            DepositSettlementRequestedEvent release = new DepositSettlementRequestedEvent(
                    DepositSettlementType.RELEASE,
                    session.id(),
                    null,
                    registration.bidderId(),
                    registration.holdNo(),
                    registration.amount(),
                    new BigDecimal("0.00"));
            outbox.enqueue(eventFactory.depositRelease(release, command.triggeredAt(), command.traceId()),
                    command.triggeredAt());
        }
    }

    private void ensureCurrentCommand(SessionSnapshot session, Instant now) {
        outbox.enqueueIfAbsent(eventFactory.closeAuction(session.id(), session.endAt(), now), now);
    }

    private void ensureRetryCommand(SessionSnapshot session, CloseCommand command) {
        if (command.source() == TriggerSource.MESSAGE) {
            outbox.enqueueIfAbsent(eventFactory.closeAuctionRetry(
                    session.id(), session.endAt(), command.triggeredAt(), command.sourceEventId()),
                    command.triggeredAt());
        } else {
            ensureCurrentCommand(session, command.triggeredAt());
        }
    }

    private CloseResult closeRaceResult(long auctionId) {
        SessionSnapshot latest = findSession(auctionId);
        return latest != null && isTerminal(latest.status()) ? CloseResult.ALREADY_CLOSED : CloseResult.LOST_RACE;
    }

    private List<Registration> registrations(long auctionId) {
        return jdbc.query("""
                SELECT bidder_id, registration_no, deposit_amount
                FROM auction_registration
                WHERE auction_id = ? AND status = 'REGISTERED'
                ORDER BY id
                """, (row, rowNumber) -> new Registration(
                        row.getLong("bidder_id"), row.getString("registration_no"),
                        row.getBigDecimal("deposit_amount")), auctionId);
    }

    private SessionSnapshot findSession(long auctionId) {
        return jdbc.query("""
                SELECT session.id, session.item_id, item.title, session.seller_id,
                       session.deposit_amount, session.current_price, session.current_bidder_id,
                       session.bid_count, session.end_at, session.status, session.version
                FROM auction_session session
                JOIN auction_item item ON item.id = session.item_id
                WHERE session.id = ?
                """, (row, rowNumber) -> new SessionSnapshot(
                        row.getLong("id"), row.getLong("item_id"), row.getString("title"),
                        row.getLong("seller_id"), row.getBigDecimal("deposit_amount"),
                        row.getBigDecimal("current_price"), (Long) row.getObject("current_bidder_id"),
                        row.getLong("bid_count"), row.getTimestamp("end_at").toInstant(),
                        row.getString("status"), row.getLong("version")), auctionId).stream().findFirst().orElse(null);
    }

    private static boolean isTerminal(String status) {
        return "CLOSED_SOLD".equals(status) || "CLOSED_UNSOLD".equals(status);
    }

    private static boolean isCloseable(String status) {
        return "SCHEDULED".equals(status) || "OPEN".equals(status) || "AWAITING_CLOSE".equals(status);
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private record SessionSnapshot(
            long id,
            long itemId,
            String itemTitle,
            long sellerId,
            BigDecimal depositAmount,
            BigDecimal currentPrice,
            Long currentBidderId,
            long bidCount,
            Instant endAt,
            String status,
            long version
    ) { }

    private record WinningBid(long id, long bidderId, BigDecimal amount, long sequenceNo) { }

    private record Registration(long bidderId, String holdNo, BigDecimal amount) { }
}
