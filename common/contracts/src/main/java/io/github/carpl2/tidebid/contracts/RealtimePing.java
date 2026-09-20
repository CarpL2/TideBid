package io.github.carpl2.tidebid.contracts;

import java.time.Instant;

public record RealtimePing(Instant clientTime) {
    public RealtimePing {
        clientTime = ContractRules.instant(clientTime, "clientTime");
    }
}
