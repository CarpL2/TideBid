package io.github.carpl2.tidebid.auction.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Applies the lifecycle rules for one bidder's proxy rule.
 * Persistence and allocation of ids/priorities remain outside this class.
 */
public final class AuctionProxyBidLifecycle {

    public AuctionProxyBid create(
            AuctionSession session,
            long proxyBidId,
            long bidderId,
            BigDecimal maxAmount,
            long priority,
            Instant now
    ) {
        requireOpen(session);
        AuctionDomainRules.positiveId(proxyBidId, "proxyBidId");
        AuctionDomainRules.positiveId(bidderId, "bidderId");
        AuctionDomainRules.positiveId(priority, "priority");
        Instant timestamp = requireTimestamp(now);
        return new AuctionProxyBid(
                proxyBidId, session.id(), bidderId, maxAmount, AuctionProxyBidStatus.ACTIVE,
                priority, 0L, null, timestamp, timestamp
        );
    }

    public AuctionProxyBid update(
            AuctionSession session,
            AuctionProxyBid existing,
            BigDecimal maxAmount,
            long newPriority,
            Instant now
    ) {
        requireExisting(session, existing);
        AuctionDomainRules.positiveId(newPriority, "newPriority");
        if (newPriority <= existing.priority()) {
            throw new IllegalArgumentException("updated proxy bid must receive a newer priority");
        }
        Instant timestamp = requireTimestamp(now);
        requireNotBefore(existing.updatedAt(), timestamp, "updatedAt");
        return new AuctionProxyBid(
                existing.id(), existing.auctionId(), existing.bidderId(), maxAmount,
                AuctionProxyBidStatus.ACTIVE, newPriority, nextVersion(existing.version()),
                null, existing.createdAt(), timestamp
        );
    }

    public AuctionProxyBid disable(AuctionSession session, AuctionProxyBid existing, Instant now) {
        requireExisting(session, existing);
        if (existing.status() == AuctionProxyBidStatus.DISABLED) {
            return existing;
        }
        Instant timestamp = requireTimestamp(now);
        requireNotBefore(existing.updatedAt(), timestamp, "disabledAt");
        return new AuctionProxyBid(
                existing.id(), existing.auctionId(), existing.bidderId(), existing.maxAmount(),
                AuctionProxyBidStatus.DISABLED, existing.priority(), nextVersion(existing.version()),
                timestamp, existing.createdAt(), timestamp
        );
    }

    public boolean isEffective(AuctionSession session, AuctionProxyBid proxyBid) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(proxyBid, "proxyBid must not be null");
        return session.status() == AuctionSessionStatus.OPEN
                && proxyBid.auctionId() == session.id()
                && proxyBid.status() == AuctionProxyBidStatus.ACTIVE;
    }

    private static void requireOpen(AuctionSession session) {
        Objects.requireNonNull(session, "session must not be null");
        if (session.status() != AuctionSessionStatus.OPEN) {
            throw new IllegalArgumentException("proxy rule changes require an open auction");
        }
    }

    private static void requireExisting(AuctionSession session, AuctionProxyBid existing) {
        requireOpen(session);
        Objects.requireNonNull(existing, "existing proxy bid must not be null");
        if (existing.auctionId() != session.id()) {
            throw new IllegalArgumentException("proxy bid belongs to another auction");
        }
        if (existing.bidderId() <= 0) {
            throw new IllegalArgumentException("proxy bidder must be positive");
        }
    }

    private static Instant requireTimestamp(Instant timestamp) {
        return Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    private static void requireNotBefore(Instant previous, Instant current, String name) {
        if (current.isBefore(previous)) {
            throw new IllegalArgumentException(name + " must not be before the previous update");
        }
    }

    private static long nextVersion(long version) {
        try {
            return Math.addExact(version, 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("proxy bid version overflow", exception);
        }
    }
}
