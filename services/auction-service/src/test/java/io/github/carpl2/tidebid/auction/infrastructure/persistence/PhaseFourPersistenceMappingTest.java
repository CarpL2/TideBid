package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import io.github.carpl2.tidebid.auction.domain.AuctionBidCommand;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommandStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommandType;
import io.github.carpl2.tidebid.auction.domain.AuctionProxyBid;
import io.github.carpl2.tidebid.auction.domain.AuctionProxyBidStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.auction.domain.BidSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PhaseFourPersistenceMappingTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-20T01:00:00Z");
    private static final Instant START_AT = Instant.parse("2026-09-20T02:00:00Z");
    private static final Instant ORIGINAL_END_AT = Instant.parse("2026-09-20T03:00:00Z");
    private static final Instant EXTENDED_END_AT = Instant.parse("2026-09-20T03:05:00Z");

    @Test
    void preservesAntiSnipingSessionFieldsAcrossPersistenceMapping() {
        AuctionSession source = new AuctionSession(
                1L, 2L, 3L, new BigDecimal("100.00"), new BigDecimal("10.00"),
                new BigDecimal("50.00"), new BigDecimal("120.00"), 4L, 2L,
                START_AT, EXTENDED_END_AT, ORIGINAL_END_AT, 1, AuctionSessionStatus.OPEN,
                null, null, null, null, 5L, CREATED_AT, EXTENDED_END_AT
        );

        assertThat(AuctionPersistenceMapping.toDomain(AuctionPersistenceMapping.toEntity(source)))
                .isEqualTo(source);
    }

    @Test
    void preservesProxyBidAndItsOptimisticLockVersion() {
        AuctionProxyBid source = new AuctionProxyBid(
                11L, 12L, 13L, new BigDecimal("500.00"), AuctionProxyBidStatus.ACTIVE,
                14L, 3L, null, CREATED_AT, CREATED_AT.plusSeconds(30)
        );

        assertThat(AuctionPersistenceMapping.toDomain(AuctionPersistenceMapping.toEntity(source)))
                .isEqualTo(source);
    }

    @Test
    void preservesCompletedCommandReplaySnapshotAndGeneratedBidSource() {
        AuctionBidCommand command = new AuctionBidCommand(
                21L, 22L, 23L, "proxy_request_0001", AuctionBidCommandType.UPSERT_PROXY,
                "a".repeat(64), AuctionBidCommandStatus.SUCCEEDED, 2, new BigDecimal("130.00"),
                true, 6L, 7L, CREATED_AT, CREATED_AT.plusSeconds(1)
        );
        BidRecord generatedBid = new BidRecord(
                31L, 22L, 23L, "proxy_request_0001", BidSource.PROXY, 21L,
                new BigDecimal("130.00"), new BigDecimal("120.00"), 7L, CREATED_AT.plusSeconds(1)
        );

        assertThat(AuctionPersistenceMapping.toDomain(AuctionPersistenceMapping.toEntity(command)))
                .isEqualTo(command);
        assertThat(AuctionPersistenceMapping.toDomain(AuctionPersistenceMapping.toEntity(generatedBid)))
                .isEqualTo(generatedBid);
    }
}
