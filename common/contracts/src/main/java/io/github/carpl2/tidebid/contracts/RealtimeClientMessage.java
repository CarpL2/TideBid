package io.github.carpl2.tidebid.contracts;

import java.util.Objects;

public record RealtimeClientMessage<T>(
        RealtimeMessageType type,
        int protocolVersion,
        String requestId,
        T payload
) {
    public RealtimeClientMessage {
        type = Objects.requireNonNull(type, "type must not be null");
        if (!type.isClientMessage()) {
            throw new IllegalArgumentException("type must be a client message type");
        }
        protocolVersion = RealtimeProtocol.requireVersion(protocolVersion);
        requestId = RealtimeProtocol.requireRequestId(requestId);
        payload = RealtimeProtocol.requirePayload(payload);
    }
}
