package io.github.carpl2.tidebid.contracts;

import java.util.Objects;

/** Stable limits and validation shared by both ends of the WebSocket protocol. */
public final class RealtimeProtocol {

    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_CLIENT_MESSAGE_BYTES = 8 * 1024;
    public static final int MAX_SUBSCRIPTIONS_PER_CONNECTION = 20;
    public static final int SERVER_SEND_QUEUE_CAPACITY = 128;
    public static final int HEARTBEAT_SECONDS = 30;
    public static final int IDLE_TIMEOUT_SECONDS = 90;

    public static final String AUCTION_CHANNEL_PREFIX = "tidebid:realtime:auction:";
    public static final String EVENT_DEDUPLICATION_KEY_PREFIX = "tidebid:realtime:event:";
    public static final String TICKET_KEY_PREFIX = "tidebid:realtime:ticket:";
    public static final String CONNECTION_LEASE_KEY_PREFIX = "tidebid:realtime:connection:";

    private RealtimeProtocol() {
    }

    static int requireVersion(int protocolVersion) {
        if (protocolVersion != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("unsupported protocolVersion: " + protocolVersion);
        }
        return protocolVersion;
    }

    static String requireRequestId(String requestId) {
        return ContractRules.businessKey(requestId, "requestId");
    }

    static <T> T requirePayload(T payload) {
        return Objects.requireNonNull(payload, "payload must not be null");
    }
}
