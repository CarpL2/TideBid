package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSubmissionTransaction;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
@Profile({"local-db", "nacos"})
public class MybatisAuctionSubmissionTransaction implements AuctionSubmissionTransaction {

    private final AuctionItemRepository itemRepository;
    private final AuctionSessionRepository sessionRepository;

    public MybatisAuctionSubmissionTransaction(
            AuctionItemRepository itemRepository,
            AuctionSessionRepository sessionRepository
    ) {
        this.itemRepository = itemRepository;
        this.sessionRepository = sessionRepository;
    }

    @Override
    @Transactional
    public SubmittedAuction submit(
            long itemId,
            long sellerId,
            long expectedItemVersion,
            long expectedSessionVersion,
            Instant submittedAt
    ) {
        AuctionSession session = sessionRepository.findSessionByItemId(itemId)
                .orElseThrow(() -> new IllegalStateException("Auction item has no session"));
        if (session.sellerId() != sellerId
                || session.status() != AuctionSessionStatus.DRAFT
                || session.version() != expectedSessionVersion) {
            throw new SubmissionConflictException("Auction session changed before submission");
        }
        if (!itemRepository.submitForReview(itemId, sellerId, expectedItemVersion, submittedAt)) {
            throw new SubmissionConflictException("Auction item changed before submission");
        }
        AuctionItem storedItem = itemRepository.findItemById(itemId)
                .orElseThrow(() -> new IllegalStateException("Submitted auction item could not be reloaded"));
        AuctionSession storedSession = sessionRepository.findSessionByItemId(itemId)
                .orElseThrow(() -> new IllegalStateException("Submitted auction session could not be reloaded"));
        return new SubmittedAuction(storedItem, storedSession);
    }
}
