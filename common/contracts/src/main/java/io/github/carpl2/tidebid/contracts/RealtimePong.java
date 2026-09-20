package io.github.carpl2.tidebid.contracts;

import java.time.Instant;

public record RealtimePong(Instant serverTime, Instant echoedClientTime) {
    public RealtimePong {
        serverTime = ContractRules.instant(serverTime, "serverTime");
        echoedClientTime = ContractRules.instant(echoedClientTime, "echoedClientTime");
    }
}
