package io.github.carpl2.tidebid.trade.application.port;

import io.github.carpl2.tidebid.trade.domain.TradeOrder;

import java.util.Optional;

public interface TradeOrderRepository {

    Optional<TradeOrder> findByAuctionId(long auctionId);

    Optional<TradeOrder> findByAuctionIdForUpdate(long auctionId);

    boolean insertIfAbsent(TradeOrder order);
}
