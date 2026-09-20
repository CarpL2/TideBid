package io.github.carpl2.tidebid.auction.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record AuctionBidCommand(
        long id,
        long auctionId,
        long actorId,
        String requestId,
        AuctionBidCommandType commandType,
        String payloadHash,
        AuctionBidCommandStatus status,
        Integer resultBidCount,
        BigDecimal resultPrice,
        Boolean resultLeading,
        Long firstSequenceNo,
        Long lastSequenceNo,
        Instant createdAt,
        Instant completedAt
) {
    public AuctionBidCommand {
        AuctionDomainRules.positiveId(id, "id");
        AuctionDomainRules.positiveId(auctionId, "auctionId");
        AuctionDomainRules.positiveId(actorId, "actorId");
        requestId = AuctionDomainRules.requestId(requestId);
        commandType = Objects.requireNonNull(commandType, "commandType must not be null");
        payloadHash = AuctionDomainRules.sha256(payloadHash, "payloadHash");
        status = Objects.requireNonNull(status, "status must not be null");
        createdAt = AuctionDomainRules.instant(createdAt, "createdAt");
        if (status == AuctionBidCommandStatus.PROCESSING) {
            if (resultBidCount != null || resultPrice != null || resultLeading != null
                    || firstSequenceNo != null || lastSequenceNo != null || completedAt != null) {
                throw new IllegalArgumentException("processing command must not contain a result");
            }
        } else {
            if (resultBidCount == null || resultBidCount < 0 || resultBidCount > 2) {
                throw new IllegalArgumentException("resultBidCount must be between 0 and 2");
            }
            resultPrice = AuctionDomainRules.positiveAmount(resultPrice, "resultPrice");
            Objects.requireNonNull(resultLeading, "resultLeading must not be null");
            completedAt = AuctionDomainRules.instant(completedAt, "completedAt");
            if (completedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("completedAt must not be before createdAt");
            }
            if (resultBidCount == 0) {
                if (firstSequenceNo != null || lastSequenceNo != null) {
                    throw new IllegalArgumentException("zero-bid result must not contain sequences");
                }
            } else {
                AuctionDomainRules.positiveId(Objects.requireNonNull(firstSequenceNo, "firstSequenceNo"),
                        "firstSequenceNo");
                AuctionDomainRules.positiveId(Objects.requireNonNull(lastSequenceNo, "lastSequenceNo"),
                        "lastSequenceNo");
                if (lastSequenceNo != firstSequenceNo + resultBidCount - 1L) {
                    throw new IllegalArgumentException("result sequences must be contiguous");
                }
            }
        }
    }
}
