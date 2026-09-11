package io.github.carpl2.tidebid.auction.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tidebid.auction.account-client")
public record AuctionAccountClientProperties(String internalToken) {

    private static final int MINIMUM_TOKEN_LENGTH = 32;
    private static final int MAXIMUM_TOKEN_LENGTH = 512;

    public AuctionAccountClientProperties {
        internalToken = internalToken == null ? "" : internalToken.trim();
    }

    public String requiredInternalToken() {
        if (internalToken.length() < MINIMUM_TOKEN_LENGTH || internalToken.length() > MAXIMUM_TOKEN_LENGTH) {
            throw new IllegalStateException("TIDEBID_INTERNAL_SERVICE_TOKEN must contain 32 to 512 characters");
        }
        return internalToken;
    }

    @Override
    public String toString() {
        return "AuctionAccountClientProperties[internalToken=[REDACTED]]";
    }
}
