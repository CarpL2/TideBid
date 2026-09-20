package io.github.carpl2.tidebid.auction.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Calculates the public auction state implied by the current accepted bid and active proxy rules.
 * The result deliberately contains no proxy maximum amount.
 */
public final class AuctionProxyBidCalculator {

    private static final long INCUMBENT_PRIORITY = Long.MIN_VALUE;
    private static final Comparator<Offer> OFFER_ORDER = Comparator
            .comparing(Offer::maximum).reversed()
            .thenComparingLong(Offer::priority)
            .thenComparingLong(Offer::bidderId);

    public Result calculate(AuctionSession session, List<AuctionProxyBid> proxyBids) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(proxyBids, "proxyBids must not be null");
        if (session.status() != AuctionSessionStatus.OPEN) {
            throw new IllegalArgumentException("proxy bidding requires an open auction");
        }

        BigDecimal publicFloor = money(session.displayPrice());
        BigDecimal minimumChallenge = money(session.minimumNextBid());
        List<Offer> eligibleOffers = activeOffers(session, proxyBids, minimumChallenge);
        Offer incumbent = incumbentOffer(session, eligibleOffers);
        if (incumbent != null) {
            eligibleOffers.add(incumbent);
        }
        eligibleOffers.sort(OFFER_ORDER);

        if (eligibleOffers.isEmpty()) {
            return result(session, null, publicFloor, false);
        }

        Offer leader = eligibleOffers.getFirst();
        Offer runnerUp = eligibleOffers.size() > 1 ? eligibleOffers.get(1) : null;
        BigDecimal candidate = priceRequiredToLead(session, leader, runnerUp, publicFloor);
        BigDecimal finalPrice = money(candidate.max(publicFloor));
        if (finalPrice.compareTo(leader.maximum()) > 0) {
            throw new IllegalStateException("calculated price exceeds the leading offer");
        }
        return result(session, leader, finalPrice, true);
    }

    private static List<Offer> activeOffers(
            AuctionSession session,
            List<AuctionProxyBid> proxyBids,
            BigDecimal minimumChallenge
    ) {
        List<Offer> offers = new ArrayList<>();
        Set<Long> activeBidders = new HashSet<>();
        for (AuctionProxyBid proxyBid : proxyBids) {
            if (proxyBid == null) {
                throw new IllegalArgumentException("proxyBids must not contain null");
            }
            if (proxyBid.auctionId() != session.id()) {
                throw new IllegalArgumentException("proxy bid belongs to another auction");
            }
            if (proxyBid.status() != AuctionProxyBidStatus.ACTIVE) {
                continue;
            }
            if (!activeBidders.add(proxyBid.bidderId())) {
                throw new IllegalArgumentException("auction contains duplicate active proxy bidders");
            }

            BigDecimal maximum = money(proxyBid.maxAmount());
            boolean incumbent = Objects.equals(session.currentBidderId(), proxyBid.bidderId());
            if (!incumbent && maximum.compareTo(minimumChallenge) < 0) {
                continue;
            }
            if (incumbent && session.currentPrice() != null) {
                if (maximum.compareTo(money(session.currentPrice())) < 0) {
                    continue;
                }
            }
            offers.add(new Offer(
                    proxyBid.bidderId(), proxyBid.id(), maximum, proxyBid.priority(), true
            ));
        }
        return offers;
    }

    private static Offer incumbentOffer(AuctionSession session, List<Offer> proxyOffers) {
        if (session.currentBidderId() == null) {
            return null;
        }
        boolean representedByProxy = proxyOffers.stream()
                .anyMatch(offer -> offer.bidderId() == session.currentBidderId());
        if (representedByProxy) {
            return null;
        }
        return new Offer(
                session.currentBidderId(), null, money(session.currentPrice()), INCUMBENT_PRIORITY, false
        );
    }

    private static BigDecimal priceRequiredToLead(
            AuctionSession session,
            Offer leader,
            Offer runnerUp,
            BigDecimal publicFloor
    ) {
        if (!leader.proxy()) {
            return publicFloor;
        }
        if (runnerUp != null) {
            BigDecimal priceAgainstRunner = money(runnerUp.maximum().add(session.bidIncrement()));
            return leader.maximum().min(priceAgainstRunner);
        }
        if (session.currentBidderId() == null || session.currentBidderId() == leader.bidderId()) {
            return publicFloor;
        }
        return leader.maximum().min(money(publicFloor.add(session.bidIncrement())));
    }

    private static Result result(
            AuctionSession session,
            Offer leader,
            BigDecimal displayPrice,
            boolean hasAcceptedBid
    ) {
        Long leaderId = leader == null ? null : leader.bidderId();
        Long proxyBidId = leader == null ? null : leader.proxyBidId();
        BigDecimal minimumNextBid = hasAcceptedBid
                ? money(displayPrice.add(session.bidIncrement()))
                : money(session.startPrice());
        return new Result(
                leaderId,
                proxyBidId,
                displayPrice,
                minimumNextBid,
                hasAcceptedBid,
                displayPrice.compareTo(money(session.displayPrice())) != 0,
                !Objects.equals(leaderId, session.currentBidderId())
        );
    }

    private static BigDecimal money(BigDecimal value) {
        return Objects.requireNonNull(value, "amount must not be null")
                .setScale(2, RoundingMode.UNNECESSARY);
    }

    private record Offer(
            long bidderId,
            Long proxyBidId,
            BigDecimal maximum,
            long priority,
            boolean proxy
    ) {
    }

    public record Result(
            Long leadingBidderId,
            Long leadingProxyBidId,
            BigDecimal displayPrice,
            BigDecimal minimumNextBid,
            boolean hasAcceptedBid,
            boolean priceChanged,
            boolean leaderChanged
    ) {
        public Result {
            displayPrice = money(displayPrice);
            minimumNextBid = money(minimumNextBid);
            if (hasAcceptedBid != (leadingBidderId != null)) {
                throw new IllegalArgumentException("leader and accepted-bid state must agree");
            }
            if (leadingProxyBidId != null && leadingBidderId == null) {
                throw new IllegalArgumentException("leadingProxyBidId requires a leader");
            }
            if (leadingBidderId != null) {
                AuctionDomainRules.positiveId(leadingBidderId, "leadingBidderId");
            }
            if (leadingProxyBidId != null) {
                AuctionDomainRules.positiveId(leadingProxyBidId, "leadingProxyBidId");
            }
        }
    }
}
