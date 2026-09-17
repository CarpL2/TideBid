package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionAssetQueryService;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AuctionLobbyResponse {

    private AuctionLobbyResponse() {
    }

    public record Page(
            int page,
            int size,
            long total,
            long totalPages,
            List<Summary> items
    ) {
        static Page from(AuctionAssetQueryService.PageResult<AuctionAssetQueryService.LobbySummary> source) {
            return new Page(
                    source.page(), source.size(), source.total(), source.totalPages(),
                    source.items().stream().map(Summary::from).toList()
            );
        }
    }

    public record Summary(
            String itemId,
            String auctionId,
            String title,
            String category,
            AuctionItemCondition itemCondition,
            AuctionSessionStatus sessionStatus,
            BigDecimal startPrice,
            BigDecimal currentPrice,
            BigDecimal displayPrice,
            BigDecimal minimumNextBid,
            long bidCount,
            Instant startAt,
            Instant endAt,
            BigDecimal finalPrice,
            Instant closedAt,
            CoverImage coverImage
    ) {
        static Summary from(AuctionAssetQueryService.LobbySummary source) {
            return new Summary(
                    Long.toString(source.itemId()), Long.toString(source.auctionId()), source.title(),
                    source.category(), source.itemCondition(), source.sessionStatus(), source.startPrice(),
                    source.currentPrice(), source.displayPrice(), source.minimumNextBid(), source.bidCount(),
                    source.startAt(), source.endAt(), source.finalPrice(), source.closedAt(),
                    CoverImage.from(source.coverImage())
            );
        }
    }

    public record CoverImage(
            String imageId,
            String contentType,
            String previewUrl,
            Instant previewExpiresAt
    ) {
        static CoverImage from(AuctionAssetQueryService.LobbyCover source) {
            return source == null ? null : new CoverImage(
                    Long.toString(source.imageId()), source.contentType(),
                    source.previewUrl() == null ? null : source.previewUrl().toString(),
                    source.previewExpiresAt()
            );
        }

        @Override
        public String toString() {
            return "CoverImage[imageId=" + imageId + ", previewUrl=[REDACTED], previewExpiresAt="
                    + previewExpiresAt + "]";
        }
    }
}
