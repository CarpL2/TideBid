package io.github.carpl2.tidebid.contracts;

import java.time.Instant;

public record RealtimeConnected(
        String connectionId,
        Instant serverTime,
        int heartbeatSeconds
) {
    public RealtimeConnected {
        connectionId = ContractRules.businessKey(connectionId, "connectionId");
        serverTime = ContractRules.instant(serverTime, "serverTime");
        if (heartbeatSeconds <= 0) {
            throw new IllegalArgumentException("heartbeatSeconds must be positive");
        }
    }
}
