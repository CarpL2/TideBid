package io.github.carpl2.tidebid.contracts;

public enum RealtimeMessageType {
    SUBSCRIBE(Direction.CLIENT),
    UNSUBSCRIBE(Direction.CLIENT),
    PING(Direction.CLIENT),
    CONNECTED(Direction.SERVER),
    SNAPSHOT(Direction.SERVER),
    BID_ACCEPTED(Direction.SERVER),
    AUCTION_EXTENDED(Direction.SERVER),
    AUCTION_CLOSED(Direction.SERVER),
    PONG(Direction.SERVER),
    RESYNC_REQUIRED(Direction.SERVER),
    ERROR(Direction.SERVER);

    private final Direction direction;

    RealtimeMessageType(Direction direction) {
        this.direction = direction;
    }

    public boolean isClientMessage() {
        return direction == Direction.CLIENT;
    }

    public boolean isServerMessage() {
        return direction == Direction.SERVER;
    }

    private enum Direction {
        CLIENT,
        SERVER
    }
}
