package io.github.carpl2.tidebid.trade.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tidebid.trade.account-client")
public record TradeAccountClientProperties(String internalToken) {

    public TradeAccountClientProperties {
        internalToken = internalToken == null ? "" : internalToken.trim();
    }

    public String requiredInternalToken() {
        if (internalToken.length() < 32 || internalToken.length() > 512) {
            throw new IllegalStateException("TIDEBID_INTERNAL_SERVICE_TOKEN must contain 32 to 512 characters");
        }
        return internalToken;
    }

    @Override
    public String toString() {
        return "TradeAccountClientProperties[internalToken=[REDACTED]]";
    }
}
