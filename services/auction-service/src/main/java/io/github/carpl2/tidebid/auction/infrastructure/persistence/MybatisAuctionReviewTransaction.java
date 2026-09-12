package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionReviewTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionReview;
import io.github.carpl2.tidebid.auction.domain.AuctionReviewDecision;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile({"local-db", "nacos"})
public class MybatisAuctionReviewTransaction implements AuctionReviewTransaction {

    private final AuctionItemRepository itemRepository;
    private final AuctionSessionRepository sessionRepository;

    public MybatisAuctionReviewTransaction(
            AuctionItemRepository itemRepository,
            AuctionSessionRepository sessionRepository
    ) {
        this.itemRepository = itemRepository;
        this.sessionRepository = sessionRepository;
    }

    @Override
    @Transactional
    public ReviewedAuction approve(
            AuctionReview review,
            long expectedItemVersion,
            AuctionSession session
    ) {
        if (review == null || session == null
                || review.decision() != AuctionReviewDecision.APPROVED
                || review.itemId() != session.itemId()) {
            throw new IllegalArgumentException("Approval review and auction session are inconsistent");
        }
        if (!itemRepository.approvePendingItem(
                review.itemId(), review.submissionVersion(), expectedItemVersion, review.reviewedAt()
        )) {
            throw new ReviewConflictException("Auction submission changed before review");
        }
        if (!sessionRepository.scheduleDraftSession(
                session.id(), session.itemId(), session.sellerId(), session.version(), review.reviewedAt()
        )) {
            throw new ReviewConflictException("Auction session changed or its start time is no longer valid");
        }
        AuctionReview storedReview = itemRepository.insertReview(review);
        AuctionItem storedItem = itemRepository.findItemById(review.itemId())
                .orElseThrow(() -> new IllegalStateException("Approved auction item could not be reloaded"));
        AuctionSession storedSession = sessionRepository.findSessionByItemId(review.itemId())
                .orElseThrow(() -> new IllegalStateException("Scheduled auction session could not be reloaded"));
        return new ReviewedAuction(storedItem, storedSession, storedReview);
    }

    @Override
    @Transactional
    public ReviewedAuction reject(
            AuctionReview review,
            long expectedItemVersion,
            AuctionSession session
    ) {
        if (review == null || session == null
                || review.decision() != AuctionReviewDecision.REJECTED
                || review.itemId() != session.itemId()) {
            throw new IllegalArgumentException("Rejection review and auction session are inconsistent");
        }
        if (!itemRepository.rejectPendingItem(
                review.itemId(), review.submissionVersion(), expectedItemVersion, review.reviewedAt()
        )) {
            throw new ReviewConflictException("Auction submission changed before review");
        }
        AuctionReview storedReview = itemRepository.insertReview(review);
        AuctionItem storedItem = itemRepository.findItemById(review.itemId())
                .orElseThrow(() -> new IllegalStateException("Rejected auction item could not be reloaded"));
        AuctionSession storedSession = sessionRepository.findSessionByItemId(review.itemId())
                .orElseThrow(() -> new IllegalStateException("Draft auction session could not be reloaded"));
        return new ReviewedAuction(storedItem, storedSession, storedReview);
    }
}
