package io.github.carpl2.tidebid.contracts;

import java.time.Instant;
import java.util.Objects;

public record RealtimeServerMessage<T>(
        RealtimeMessageType type,
        int protocolVersion,
        String requestId,
        Instant sentAt,
        T payload
) {
    public RealtimeServerMessage {
        type = Objects.requireNonNull(type, "type must not be null");
        if (!type.isServerMessage()) {
            throw new IllegalArgumentException("type must be a server message type");
        }
        protocolVersion = RealtimeProtocol.requireVersion(protocolVersion);
        requestId = RealtimeProtocol.requireRequestId(requestId);
        sentAt = ContractRules.instant(sentAt, "sentAt");
        payload = RealtimeProtocol.requirePayload(payload);
    }
}
