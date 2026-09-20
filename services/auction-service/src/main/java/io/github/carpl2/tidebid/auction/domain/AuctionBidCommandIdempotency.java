package io.github.carpl2.tidebid.auction.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Defines request replay and payload-conflict behavior for auction commands.
 * The repository is responsible for enforcing the actor/request unique key.
 */
public final class AuctionBidCommandIdempotency {

    public Decision inspect(AuctionBidCommand existing, Request request) {
        Objects.requireNonNull(request, "request must not be null");
        if (existing == null) {
            return Decision.newRequest();
        }
        if (existing.actorId() != request.actorId()
                || !existing.requestId().equals(request.requestId())
                || existing.commandType() != request.commandType()
                || !existing.payloadHash().equals(request.payloadHash())) {
            return Decision.payloadConflict(existing);
        }
        return existing.status() == AuctionBidCommandStatus.SUCCEEDED
                ? Decision.replay(existing)
                : Decision.inProgress(existing);
    }

    public AuctionBidCommand start(long commandId, long auctionId, Request request, Instant createdAt) {
        Objects.requireNonNull(request, "request must not be null");
        AuctionDomainRules.positiveId(commandId, "commandId");
        AuctionDomainRules.positiveId(auctionId, "auctionId");
        Instant timestamp = Objects.requireNonNull(createdAt, "createdAt must not be null");
        return new AuctionBidCommand(
                commandId, auctionId, request.actorId(), request.requestId(), request.commandType(),
                request.payloadHash(), AuctionBidCommandStatus.PROCESSING,
                null, null, null, null, null, timestamp, null
        );
    }

    public AuctionBidCommand complete(
            AuctionBidCommand processing,
            AuctionBidCommandPlanner.Plan plan,
            Instant completedAt
    ) {
        Objects.requireNonNull(processing, "processing command must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        if (processing.status() != AuctionBidCommandStatus.PROCESSING) {
            throw new IllegalArgumentException("only processing commands can be completed");
        }
        Instant timestamp = Objects.requireNonNull(completedAt, "completedAt must not be null");
        if (timestamp.isBefore(processing.createdAt())) {
            throw new IllegalArgumentException("completedAt must not be before createdAt");
        }
        int resultBidCount = plan.bids().size();
        Long firstSequenceNo = resultBidCount == 0 ? null : plan.bids().getFirst().sequenceNo();
        Long lastSequenceNo = resultBidCount == 0 ? null : plan.bids().getLast().sequenceNo();
        return new AuctionBidCommand(
                processing.id(), processing.auctionId(), processing.actorId(), processing.requestId(),
                processing.commandType(), processing.payloadHash(), AuctionBidCommandStatus.SUCCEEDED,
                resultBidCount, plan.displayPrice(), Objects.equals(plan.leadingBidderId(), processing.actorId()),
                firstSequenceNo, lastSequenceNo, processing.createdAt(), timestamp
        );
    }

    public record Request(
            long actorId,
            String requestId,
            AuctionBidCommandType commandType,
            String payloadHash
    ) {
        public Request {
            AuctionDomainRules.positiveId(actorId, "actorId");
            requestId = AuctionDomainRules.requestId(requestId);
            commandType = Objects.requireNonNull(commandType, "commandType must not be null");
            payloadHash = AuctionDomainRules.sha256(payloadHash, "payloadHash");
        }
    }

    public enum DecisionType {
        NEW,
        REPLAY,
        IN_PROGRESS,
        PAYLOAD_CONFLICT
    }

    public record Decision(DecisionType type, AuctionBidCommand existing) {
        public Decision {
            type = Objects.requireNonNull(type, "type must not be null");
            if (type == DecisionType.NEW && existing != null) {
                throw new IllegalArgumentException("new request must not have an existing command");
            }
            if (type != DecisionType.NEW && existing == null) {
                throw new IllegalArgumentException("non-new decision requires an existing command");
            }
        }

        private static Decision newRequest() {
            return new Decision(DecisionType.NEW, null);
        }

        private static Decision replay(AuctionBidCommand existing) {
            return new Decision(DecisionType.REPLAY, existing);
        }

        private static Decision inProgress(AuctionBidCommand existing) {
            return new Decision(DecisionType.IN_PROGRESS, existing);
        }

        private static Decision payloadConflict(AuctionBidCommand existing) {
            return new Decision(DecisionType.PAYLOAD_CONFLICT, existing);
        }
    }
}
