package io.github.carpl2.tidebid.auction.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Plans the public bid records produced by one manual-bid or proxy-rule command.
 * Private proxy maximums are consumed as input but never exposed by the returned plan.
 */
public final class AuctionBidCommandPlanner {

    private static final Comparator<AuctionProxyBid> PROXY_ORDER = Comparator
            .comparing(AuctionProxyBid::maxAmount).reversed()
            .thenComparingLong(AuctionProxyBid::priority)
            .thenComparingLong(AuctionProxyBid::bidderId);

    private final AuctionProxyBidCalculator proxyBidCalculator;

    public AuctionBidCommandPlanner() {
        this(new AuctionProxyBidCalculator());
    }

    AuctionBidCommandPlanner(AuctionProxyBidCalculator proxyBidCalculator) {
        this.proxyBidCalculator = Objects.requireNonNull(proxyBidCalculator, "proxyBidCalculator must not be null");
    }

    public Plan plan(AuctionSession session, List<AuctionProxyBid> effectiveProxyBids, Command command) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(effectiveProxyBids, "effectiveProxyBids must not be null");
        Objects.requireNonNull(command, "command must not be null");
        if (session.status() != AuctionSessionStatus.OPEN) {
            throw new IllegalArgumentException("bid planning requires an open auction");
        }
        if (command.type() == AuctionBidCommandType.MANUAL_BID) {
            return planManualBid(session, effectiveProxyBids, command);
        }
        return planProxyRuleChange(session, effectiveProxyBids, command);
    }

    private static Plan planManualBid(
            AuctionSession session,
            List<AuctionProxyBid> effectiveProxyBids,
            Command command
    ) {
        BigDecimal amount = money(Objects.requireNonNull(command.amount(), "manual bid amount must not be null"));
        if (amount.compareTo(money(session.minimumNextBid())) < 0) {
            throw new IllegalArgumentException("manual bid amount must reach the minimum next bid");
        }

        AuctionProxyBid defendingProxy = effectiveProxyBids.stream()
                .map(proxyBid -> requireProxyForAuction(session, proxyBid))
                .filter(proxyBid -> proxyBid.status() == AuctionProxyBidStatus.ACTIVE)
                .filter(proxyBid -> proxyBid.bidderId() != command.actorId())
                .filter(proxyBid -> money(proxyBid.maxAmount()).compareTo(amount) >= 0)
                .sorted(PROXY_ORDER)
                .findFirst()
                .orElse(null);

        List<PlannedBid> bids = new ArrayList<>(2);
        if (defendingProxy == null) {
            append(bids, session, command.actorId(), BidSource.MANUAL, amount);
            return plan(session, bids, command.actorId(), null);
        }

        BigDecimal responseAmount = money(amount.add(session.bidIncrement()))
                .min(money(defendingProxy.maxAmount()));
        if (responseAmount.compareTo(amount) == 0) {
            append(bids, session, defendingProxy.bidderId(), BidSource.PROXY, amount);
        } else {
            append(bids, session, command.actorId(), BidSource.MANUAL, amount);
            append(bids, session, defendingProxy.bidderId(), BidSource.PROXY, responseAmount);
        }
        return plan(session, bids, defendingProxy.bidderId(), defendingProxy.id());
    }

    private Plan planProxyRuleChange(
            AuctionSession session,
            List<AuctionProxyBid> effectiveProxyBids,
            Command command
    ) {
        if (command.amount() != null) {
            throw new IllegalArgumentException("proxy rule command must not contain a public amount");
        }
        AuctionProxyBidCalculator.Result result = proxyBidCalculator.calculate(session, effectiveProxyBids);
        if (!publicStateChanged(session, result)) {
            return new Plan(
                    List.of(), result.leadingBidderId(), result.leadingProxyBidId(),
                    result.displayPrice(), result.minimumNextBid()
            );
        }

        AuctionProxyBid actorProxy = effectiveProxyBids.stream()
                .map(proxyBid -> requireProxyForAuction(session, proxyBid))
                .filter(proxyBid -> proxyBid.status() == AuctionProxyBidStatus.ACTIVE)
                .filter(proxyBid -> proxyBid.bidderId() == command.actorId())
                .findFirst()
                .orElse(null);
        List<PlannedBid> bids = new ArrayList<>(2);
        if (actorProxy != null
                && !Objects.equals(result.leadingBidderId(), command.actorId())
                && money(actorProxy.maxAmount()).compareTo(money(session.minimumNextBid())) >= 0
                && result.displayPrice().compareTo(money(actorProxy.maxAmount())) > 0) {
            append(bids, session, command.actorId(), BidSource.PROXY, money(actorProxy.maxAmount()));
        }
        append(bids, session, Objects.requireNonNull(result.leadingBidderId()), BidSource.PROXY,
                result.displayPrice());
        return new Plan(
                bids, result.leadingBidderId(), result.leadingProxyBidId(),
                result.displayPrice(), result.minimumNextBid()
        );
    }

    private static boolean publicStateChanged(AuctionSession session, AuctionProxyBidCalculator.Result result) {
        return result.hasAcceptedBid()
                && (session.currentBidderId() == null
                || !Objects.equals(session.currentBidderId(), result.leadingBidderId())
                || money(session.displayPrice()).compareTo(result.displayPrice()) != 0);
    }

    private static AuctionProxyBid requireProxyForAuction(AuctionSession session, AuctionProxyBid proxyBid) {
        Objects.requireNonNull(proxyBid, "effectiveProxyBids must not contain null");
        if (proxyBid.auctionId() != session.id()) {
            throw new IllegalArgumentException("proxy bid belongs to another auction");
        }
        return proxyBid;
    }

    private static void append(
            List<PlannedBid> bids,
            AuctionSession session,
            long bidderId,
            BidSource source,
            BigDecimal amount
    ) {
        if (bids.size() == 2) {
            throw new IllegalStateException("one bid command may produce at most two public bids");
        }
        BigDecimal previousPrice = bids.isEmpty()
                ? session.currentPrice()
                : bids.getLast().amount();
        BigDecimal normalizedAmount = money(amount);
        if (previousPrice != null && normalizedAmount.compareTo(money(previousPrice)) <= 0) {
            throw new IllegalStateException("planned public bids must increase strictly");
        }
        long sequenceNo;
        try {
            sequenceNo = Math.addExact(session.bidCount(), bids.size() + 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("auction bid count overflow", exception);
        }
        bids.add(new PlannedBid(bidderId, source, normalizedAmount, previousPrice, sequenceNo));
    }

    private static Plan plan(
            AuctionSession session,
            List<PlannedBid> bids,
            long leadingBidderId,
            Long leadingProxyBidId
    ) {
        BigDecimal finalPrice = bids.getLast().amount();
        return new Plan(
                bids, leadingBidderId, leadingProxyBidId, finalPrice,
                money(finalPrice.add(session.bidIncrement()))
        );
    }

    private static BigDecimal money(BigDecimal value) {
        return Objects.requireNonNull(value, "amount must not be null")
                .setScale(2, RoundingMode.UNNECESSARY);
    }

    public record Command(AuctionBidCommandType type, long actorId, BigDecimal amount) {
        public Command {
            type = Objects.requireNonNull(type, "type must not be null");
            AuctionDomainRules.positiveId(actorId, "actorId");
            if (type == AuctionBidCommandType.MANUAL_BID) {
                amount = money(Objects.requireNonNull(amount, "manual bid amount must not be null"));
            } else if (amount != null) {
                throw new IllegalArgumentException("proxy rule command must not contain amount");
            }
        }

        public static Command manualBid(long actorId, BigDecimal amount) {
            return new Command(AuctionBidCommandType.MANUAL_BID, actorId, amount);
        }

        public static Command proxyRuleChanged(AuctionBidCommandType type, long actorId) {
            if (type == AuctionBidCommandType.MANUAL_BID) {
                throw new IllegalArgumentException("proxy rule command type required");
            }
            return new Command(type, actorId, null);
        }
    }

    public record PlannedBid(
            long bidderId,
            BidSource source,
            BigDecimal amount,
            BigDecimal previousPrice,
            long sequenceNo
    ) {
        public PlannedBid {
            AuctionDomainRules.positiveId(bidderId, "bidderId");
            source = Objects.requireNonNull(source, "source must not be null");
            amount = money(amount);
            if (previousPrice != null) {
                previousPrice = money(previousPrice);
                if (amount.compareTo(previousPrice) <= 0) {
                    throw new IllegalArgumentException("amount must be greater than previousPrice");
                }
            }
            AuctionDomainRules.positiveId(sequenceNo, "sequenceNo");
        }
    }

    public record Plan(
            List<PlannedBid> bids,
            Long leadingBidderId,
            Long leadingProxyBidId,
            BigDecimal displayPrice,
            BigDecimal minimumNextBid
    ) {
        public Plan {
            bids = List.copyOf(Objects.requireNonNull(bids, "bids must not be null"));
            if (bids.size() > 2) {
                throw new IllegalArgumentException("one command may produce at most two public bids");
            }
            if (leadingBidderId != null) {
                AuctionDomainRules.positiveId(leadingBidderId, "leadingBidderId");
            }
            if (leadingProxyBidId != null) {
                AuctionDomainRules.positiveId(leadingProxyBidId, "leadingProxyBidId");
            }
            displayPrice = money(displayPrice);
            minimumNextBid = money(minimumNextBid);
        }
    }
}
