package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionProxyBidRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionProxyBidStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
@Profile({"local-db", "nacos"})
public class AuctionRealtimeSnapshotService {

    public static final int MAX_INCREMENTAL_BIDS = 100;

    private final AuctionSessionRepository sessionRepository;
    private final AuctionProxyBidRepository proxyBidRepository;
    private final AuctionSessionLifecycleService lifecycleService;
    private final Clock clock;

    public AuctionRealtimeSnapshotService(
            AuctionSessionRepository sessionRepository,
            AuctionProxyBidRepository proxyBidRepository,
            AuctionSessionLifecycleService lifecycleService,
            Clock clock
    ) {
        this.sessionRepository = sessionRepository;
        this.proxyBidRepository = proxyBidRepository;
        this.lifecycleService = lifecycleService;
        this.clock = clock;
    }

    public Snapshot find(long auctionId, long afterSequenceNo, int requestedLimit, Long trustedUserId) {
        if (auctionId <= 0 || afterSequenceNo < 0) {
            throw new BusinessException(AuctionErrorCode.AUCTION_INVALID);
        }
        int limit = Math.min(requestedLimit, MAX_INCREMENTAL_BIDS);
        if (requestedLimit < 1 || requestedLimit > MAX_INCREMENTAL_BIDS) {
            throw new BusinessException(AuctionErrorCode.AUCTION_INVALID,
                    "limit must be between 1 and " + MAX_INCREMENTAL_BIDS);
        }
        AuctionSession session = sessionRepository.findSessionById(auctionId)
                .map(lifecycleService::advanceToCurrentState)
                .orElseThrow(() -> new BusinessException(AuctionErrorCode.AUCTION_NOT_FOUND));
        List<BidRecord> bids = sessionRepository.findBidsAfterSequence(auctionId, afterSequenceNo, limit);
        validateBids(session, bids, afterSequenceNo);
        boolean leading = trustedUserId != null
                && Objects.equals(trustedUserId, session.currentBidderId());
        boolean hasProxy = trustedUserId != null && proxyBidRepository.findByAuctionAndBidder(auctionId, trustedUserId)
                .filter(proxy -> proxy.status() == AuctionProxyBidStatus.ACTIVE)
                .isPresent();
        return new Snapshot(session, bids, trustedUserId, leading, hasProxy, clock.instant());
    }

    private static void validateBids(AuctionSession session, List<BidRecord> bids, long afterSequenceNo) {
        long previous = afterSequenceNo;
        for (BidRecord bid : bids) {
            if (bid.auctionId() != session.id() || bid.sequenceNo() <= previous) {
                throw new IllegalStateException("snapshot bid sequence is inconsistent");
            }
            previous = bid.sequenceNo();
        }
        if (!bids.isEmpty() && bids.getLast().sequenceNo() > session.bidCount()) {
            throw new IllegalStateException("snapshot bid exceeds session bid count");
        }
    }

    public record Snapshot(
            AuctionSession session,
            List<BidRecord> bids,
            Long trustedUserId,
            boolean currentUserLeading,
            boolean currentUserHasProxy,
            Instant generatedAt
    ) {
        public Snapshot {
            session = Objects.requireNonNull(session, "session must not be null");
            bids = List.copyOf(Objects.requireNonNull(bids, "bids must not be null"));
            if (trustedUserId != null && trustedUserId <= 0) {
                throw new IllegalArgumentException("trustedUserId must be positive");
            }
            generatedAt = Objects.requireNonNull(generatedAt, "generatedAt must not be null");
        }
    }
}
