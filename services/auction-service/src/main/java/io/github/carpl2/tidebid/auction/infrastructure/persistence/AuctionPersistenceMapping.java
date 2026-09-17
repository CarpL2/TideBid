package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import io.github.carpl2.tidebid.auction.domain.AuctionImageStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemImage;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionReview;
import io.github.carpl2.tidebid.auction.domain.AuctionReviewDecision;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionItemEntity;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionItemImageEntity;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionRegistrationEntity;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionReviewEntity;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.AuctionSessionEntity;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.entity.BidRecordEntity;

final class AuctionPersistenceMapping {

    private AuctionPersistenceMapping() {
    }

    static AuctionItemEntity toEntity(AuctionItem source) {
        AuctionItemEntity target = new AuctionItemEntity();
        target.setId(source.id());
        target.setSellerId(source.sellerId());
        target.setTitle(source.title());
        target.setDescription(source.description());
        target.setCategory(source.category());
        target.setItemCondition(source.itemCondition().name());
        target.setReviewStatus(source.reviewStatus().name());
        target.setSubmissionVersion(source.submissionVersion());
        target.setVersion(source.version());
        target.setSubmittedAt(source.submittedAt());
        target.setApprovedAt(source.approvedAt());
        target.setCreatedAt(source.createdAt());
        target.setUpdatedAt(source.updatedAt());
        return target;
    }

    static AuctionItem toDomain(AuctionItemEntity source) {
        return new AuctionItem(
                source.getId(), source.getSellerId(), source.getTitle(), source.getDescription(),
                source.getCategory(), AuctionItemCondition.valueOf(source.getItemCondition()),
                AuctionItemReviewStatus.valueOf(source.getReviewStatus()), source.getSubmissionVersion(),
                source.getVersion(), source.getSubmittedAt(), source.getApprovedAt(), source.getCreatedAt(),
                source.getUpdatedAt()
        );
    }

    static AuctionItemImageEntity toEntity(AuctionItemImage source) {
        AuctionItemImageEntity target = new AuctionItemImageEntity();
        target.setId(source.id());
        target.setItemId(source.itemId());
        target.setOwnerId(source.ownerId());
        target.setObjectKey(source.objectKey());
        target.setOriginalFilename(source.originalFilename());
        target.setContentType(source.contentType());
        target.setContentLength(source.contentLength());
        target.setContentSha256(source.contentSha256());
        target.setSortOrder(source.sortOrder());
        target.setStorageStatus(source.storageStatus().name());
        target.setUploadExpiresAt(source.uploadExpiresAt());
        target.setCreatedAt(source.createdAt());
        target.setUpdatedAt(source.updatedAt());
        return target;
    }

    static AuctionItemImage toDomain(AuctionItemImageEntity source) {
        return new AuctionItemImage(
                source.getId(), source.getItemId(), source.getOwnerId(), source.getObjectKey(),
                source.getOriginalFilename(), source.getContentType(), source.getContentLength(),
                source.getContentSha256(), source.getSortOrder(), AuctionImageStatus.valueOf(source.getStorageStatus()),
                source.getUploadExpiresAt(), source.getCreatedAt(), source.getUpdatedAt()
        );
    }

    static AuctionReviewEntity toEntity(AuctionReview source) {
        AuctionReviewEntity target = new AuctionReviewEntity();
        target.setId(source.id());
        target.setItemId(source.itemId());
        target.setSubmissionVersion(source.submissionVersion());
        target.setReviewerId(source.reviewerId());
        target.setDecision(source.decision().name());
        target.setComment(source.comment());
        target.setReviewedAt(source.reviewedAt());
        return target;
    }

    static AuctionReview toDomain(AuctionReviewEntity source) {
        return new AuctionReview(
                source.getId(), source.getItemId(), source.getSubmissionVersion(), source.getReviewerId(),
                AuctionReviewDecision.valueOf(source.getDecision()), source.getComment(), source.getReviewedAt()
        );
    }

    static AuctionSessionEntity toEntity(AuctionSession source) {
        AuctionSessionEntity target = new AuctionSessionEntity();
        target.setId(source.id());
        target.setItemId(source.itemId());
        target.setSellerId(source.sellerId());
        target.setStartPrice(source.startPrice());
        target.setBidIncrement(source.bidIncrement());
        target.setDepositAmount(source.depositAmount());
        target.setCurrentPrice(source.currentPrice());
        target.setCurrentBidderId(source.currentBidderId());
        target.setBidCount(source.bidCount());
        target.setStartAt(source.startAt());
        target.setEndAt(source.endAt());
        target.setStatus(source.status().name());
        target.setWinnerId(source.winnerId());
        target.setWinningBidId(source.winningBidId());
        target.setFinalPrice(source.finalPrice());
        target.setClosedAt(source.closedAt());
        target.setVersion(source.version());
        target.setCreatedAt(source.createdAt());
        target.setUpdatedAt(source.updatedAt());
        return target;
    }

    static AuctionSession toDomain(AuctionSessionEntity source) {
        return new AuctionSession(
                source.getId(), source.getItemId(), source.getSellerId(), source.getStartPrice(),
                source.getBidIncrement(), source.getDepositAmount(), source.getCurrentPrice(),
                source.getCurrentBidderId(), source.getBidCount(), source.getStartAt(), source.getEndAt(),
                AuctionSessionStatus.valueOf(source.getStatus()), source.getWinnerId(), source.getWinningBidId(),
                source.getFinalPrice(), source.getClosedAt(), source.getVersion(), source.getCreatedAt(),
                source.getUpdatedAt()
        );
    }

    static AuctionRegistrationEntity toEntity(AuctionRegistration source) {
        AuctionRegistrationEntity target = new AuctionRegistrationEntity();
        target.setId(source.id());
        target.setRegistrationNo(source.registrationNo());
        target.setAuctionId(source.auctionId());
        target.setBidderId(source.bidderId());
        target.setDepositAmount(source.depositAmount());
        target.setStatus(source.status().name());
        target.setFailureCode(source.failureCode());
        target.setAttemptCount(source.attemptCount());
        target.setNextRetryAt(source.nextRetryAt());
        target.setLastAttemptAt(source.lastAttemptAt());
        target.setLeaseOwner(source.leaseOwner());
        target.setLeaseUntil(source.leaseUntil());
        target.setRegisteredAt(source.registeredAt());
        target.setVersion(source.version());
        target.setCreatedAt(source.createdAt());
        target.setUpdatedAt(source.updatedAt());
        return target;
    }

    static AuctionRegistration toDomain(AuctionRegistrationEntity source) {
        return new AuctionRegistration(
                source.getId(), source.getRegistrationNo(), source.getAuctionId(), source.getBidderId(),
                source.getDepositAmount(), AuctionRegistrationStatus.valueOf(source.getStatus()),
                source.getFailureCode(), source.getAttemptCount(), source.getNextRetryAt(), source.getLastAttemptAt(),
                source.getLeaseOwner(), source.getLeaseUntil(), source.getRegisteredAt(), source.getVersion(),
                source.getCreatedAt(), source.getUpdatedAt()
        );
    }

    static BidRecordEntity toEntity(BidRecord source) {
        BidRecordEntity target = new BidRecordEntity();
        target.setId(source.id());
        target.setAuctionId(source.auctionId());
        target.setBidderId(source.bidderId());
        target.setRequestId(source.requestId());
        target.setAmount(source.amount());
        target.setPreviousPrice(source.previousPrice());
        target.setSequenceNo(source.sequenceNo());
        target.setCreatedAt(source.createdAt());
        return target;
    }

    static BidRecord toDomain(BidRecordEntity source) {
        return new BidRecord(
                source.getId(), source.getAuctionId(), source.getBidderId(), source.getRequestId(),
                source.getAmount(), source.getPreviousPrice(), source.getSequenceNo(), source.getCreatedAt()
        );
    }
}
