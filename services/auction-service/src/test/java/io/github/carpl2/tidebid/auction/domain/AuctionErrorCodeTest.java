package io.github.carpl2.tidebid.auction.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class AuctionErrorCodeTest {

    @Test
    void exposesUniqueStableAuctionErrors() {
        assertThat(Arrays.stream(AuctionErrorCode.values()).map(AuctionErrorCode::code))
                .allMatch(code -> code.startsWith("AUCTION_"))
                .doesNotHaveDuplicates();
        assertThat(Arrays.stream(AuctionErrorCode.values()).map(AuctionErrorCode::defaultMessage))
                .allMatch(message -> message != null && !message.isBlank());
        assertThat(Arrays.stream(AuctionErrorCode.values()).map(AuctionErrorCode::httpStatus))
                .allMatch(status -> status >= 400 && status < 600);
    }
}
