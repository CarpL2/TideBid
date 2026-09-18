package io.github.carpl2.tidebid.trade.application.port;

@FunctionalInterface
public interface TradeIdGenerator {
    long nextId();
}
