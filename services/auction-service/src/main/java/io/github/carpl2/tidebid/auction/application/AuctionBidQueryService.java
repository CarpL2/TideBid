package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.core.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
@Profile({"local-db", "nacos"})
public class AuctionBidQueryService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAXIMUM_PAGE_SIZE = 100;

    private static final Set<AuctionSessionStatus> VISIBLE_STATUSES = Set.of(
            AuctionSessionStatus.SCHEDULED,
            AuctionSessionStatus.OPEN,
            AuctionSessionStatus.AWAITING_CLOSE,
            AuctionSessionStatus.CLOSED_SOLD,
            AuctionSessionStatus.CLOSED_UNSOLD
    );

    private final AuctionSessionRepository sessionRepository;
    private final AuctionItemRepository itemRepository;
    private final AuctionSessionLifecycleService lifecycleService;

    public AuctionBidQueryService(
            AuctionSessionRepository sessionRepository,
            AuctionItemRepository itemRepository,
            AuctionSessionLifecycleService lifecycleService
    ) {
        this.sessionRepository = sessionRepository;
        this.itemRepository = itemRepository;
        this.lifecycleService = lifecycleService;
    }

    public BidPage find(long requesterId, long auctionId, int page, int size) {
        requirePositive(requesterId, "requesterId");
        requirePositive(auctionId, "auctionId");
        int offset = pageOffset(page, size);
        AuctionSession session = sessionRepository.findSessionById(auctionId)
                .map(lifecycleService::advanceToCurrentState)
                .filter(current -> VISIBLE_STATUSES.contains(current.status()))
                .orElseThrow(() -> new BusinessException(AuctionErrorCode.AUCTION_NOT_FOUND));
        AuctionItem item = itemRepository.findItemById(session.itemId())
                .filter(storedItem -> storedItem.reviewStatus() == AuctionItemReviewStatus.APPROVED)
                .orElseThrow(() -> new BusinessException(AuctionErrorCode.AUCTION_NOT_FOUND));
        if (item.sellerId() != session.sellerId()) {
            throw new IllegalStateException("Auction item and session sellers do not match");
        }

        AuctionSessionRepository.BidPage stored = sessionRepository.findBidsByAuction(auctionId, offset, size);
        validateOrderAndOwnership(stored.bids(), auctionId);
        List<BidView> bids = stored.bids().stream()
                .map(bid -> new BidView(
                        bid.id(), bid.amount(), bid.previousPrice(), bid.sequenceNo(), bid.createdAt(),
                        bid.bidderId() == requesterId
                ))
                .toList();
        return new BidPage(
                auctionId, page, size, stored.total(), totalPages(stored.total(), size), bids
        );
    }

    private static void validateOrderAndOwnership(List<BidRecord> bids, long auctionId) {
        Long previousSequence = null;
        for (BidRecord bid : bids) {
            if (bid.auctionId() != auctionId) {
                throw new IllegalStateException("Bid record does not belong to the requested auction");
            }
            if (previousSequence != null && bid.sequenceNo() >= previousSequence) {
                throw new IllegalStateException("Bid records are not ordered by descending sequence");
            }
            previousSequence = bid.sequenceNo();
        }
    }

    private static int pageOffset(int page, int size) {
        if (page < 1 || size < 1 || size > MAXIMUM_PAGE_SIZE) {
            throw new BusinessException(
                    AuctionErrorCode.AUCTION_INVALID,
                    "page must be positive and size must be between 1 and " + MAXIMUM_PAGE_SIZE
            );
        }
        long offset = (long) (page - 1) * size;
        if (offset > Integer.MAX_VALUE) {
            throw new BusinessException(AuctionErrorCode.AUCTION_INVALID, "page offset is too large");
        }
        return (int) offset;
    }

    private static long totalPages(long total, int size) {
        return total == 0 ? 0 : ((total - 1) / size) + 1;
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new BusinessException(AuctionErrorCode.AUCTION_INVALID, name + " must be positive");
        }
    }

    public record BidPage(
            long auctionId,
            int page,
            int size,
            long total,
            long totalPages,
            List<BidView> items
    ) {
        public BidPage {
            items = List.copyOf(items);
        }
    }

    public record BidView(
            long bidId,
            BigDecimal amount,
            BigDecimal previousPrice,
            long sequenceNo,
            Instant createdAt,
            boolean mine
    ) {
    }
}
