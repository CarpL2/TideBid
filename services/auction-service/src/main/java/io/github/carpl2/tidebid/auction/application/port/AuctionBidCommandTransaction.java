package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.auction.domain.AuctionBidCommand;
import io.github.carpl2.tidebid.auction.domain.AuctionProxyBid;
import io.github.carpl2.tidebid.auction.domain.BidRecord;

import java.util.List;
import java.util.Objects;

/** Atomic persistence boundary for one manual/proxy auction command. */
public interface AuctionBidCommandTransaction {

    CommittedCommand commit(CommitRequest request);

    record CommitRequest(
            AuctionBidCommand processingCommand,
            AuctionBidCommand completedCommand,
            ProxyMutation proxyMutation,
            List<BidRecord> bids,
            long expectedSessionVersion
    ) {
        public CommitRequest {
            Objects.requireNonNull(processingCommand, "processingCommand must not be null");
            Objects.requireNonNull(completedCommand, "completedCommand must not be null");
            if (processingCommand.status() != io.github.carpl2.tidebid.auction.domain.AuctionBidCommandStatus.PROCESSING) {
                throw new IllegalArgumentException("processingCommand must be PROCESSING");
            }
            if (completedCommand.status() != io.github.carpl2.tidebid.auction.domain.AuctionBidCommandStatus.SUCCEEDED) {
                throw new IllegalArgumentException("completedCommand must be SUCCEEDED");
            }
            if (processingCommand.id() != completedCommand.id()
                    || processingCommand.auctionId() != completedCommand.auctionId()
                    || processingCommand.actorId() != completedCommand.actorId()
                    || !processingCommand.requestId().equals(completedCommand.requestId())
                    || !processingCommand.payloadHash().equals(completedCommand.payloadHash())) {
                throw new IllegalArgumentException("processing and completed commands must match");
            }
            proxyMutation = proxyMutation == null ? ProxyMutation.none() : proxyMutation;
            bids = List.copyOf(Objects.requireNonNull(bids, "bids must not be null"));
            if (bids.size() > 2) {
                throw new IllegalArgumentException("one command may contain at most two bids");
            }
            if (completedCommand.resultBidCount() != bids.size()) {
                throw new IllegalArgumentException("command result count must match bids");
            }
            if (expectedSessionVersion < 0) {
                throw new IllegalArgumentException("expectedSessionVersion must not be negative");
            }
            for (int index = 0; index < bids.size(); index++) {
                BidRecord bid = Objects.requireNonNull(bids.get(index), "bids must not contain null");
                if (bid.auctionId() != processingCommand.auctionId()
                        || bid.commandId() == null
                        || bid.commandId() != processingCommand.id()
                        || bid.sequenceNo() != expectedSequence(bids, index)) {
                    throw new IllegalArgumentException("bids must belong to the command and be contiguous");
                }
            }
        }

        private static long expectedSequence(List<BidRecord> bids, int index) {
            return index == 0
                    ? bids.getFirst().sequenceNo()
                    : bids.get(index - 1).sequenceNo() + 1L;
        }
    }

    record ProxyMutation(Type type, AuctionProxyBid proxyBid) {
        public ProxyMutation {
            type = Objects.requireNonNull(type, "type must not be null");
            if (type == Type.NONE && proxyBid != null) {
                throw new IllegalArgumentException("NONE mutation must not contain a proxy bid");
            }
            if (type != Type.NONE && proxyBid == null) {
                throw new IllegalArgumentException("proxy mutation requires a proxy bid");
            }
        }

        public static ProxyMutation none() {
            return new ProxyMutation(Type.NONE, null);
        }

        public static ProxyMutation insert(AuctionProxyBid proxyBid) {
            return new ProxyMutation(Type.INSERT, proxyBid);
        }

        public static ProxyMutation update(AuctionProxyBid proxyBid) {
            return new ProxyMutation(Type.UPDATE, proxyBid);
        }

        public enum Type {
            NONE,
            INSERT,
            UPDATE
        }
    }

    record CommittedCommand(AuctionBidCommand command, List<BidRecord> bids) {
        public CommittedCommand {
            command = Objects.requireNonNull(command, "command must not be null");
            bids = List.copyOf(Objects.requireNonNull(bids, "bids must not be null"));
            if (command.status() != io.github.carpl2.tidebid.auction.domain.AuctionBidCommandStatus.SUCCEEDED
                    || command.resultBidCount() != bids.size()) {
                throw new IllegalArgumentException("committed command does not match its bids");
            }
        }
    }

    final class BidConflictException extends RuntimeException {
        public BidConflictException() {
            super("Auction command CAS conflict");
        }
    }

    final class DuplicateCommandException extends RuntimeException {
        public DuplicateCommandException(Throwable cause) {
            super("Auction command idempotency key already exists", cause);
        }
    }
}
