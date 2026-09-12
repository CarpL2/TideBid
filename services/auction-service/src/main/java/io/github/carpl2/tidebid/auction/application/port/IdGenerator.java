package io.github.carpl2.tidebid.auction.application.port;

@FunctionalInterface
public interface IdGenerator {

    long nextId();
}
