package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionAssetQueryService;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AdminPendingAssetResponse {

    private AdminPendingAssetResponse() {
    }

    public record Page(
            int page,
            int size,
            long total,
            long totalPages,
            List<Summary> items
    ) {
        static Page from(AuctionAssetQueryService.PageResult<AuctionAssetQueryService.AdminReviewSummary> source) {
            return new Page(
                    source.page(), source.size(), source.total(), source.totalPages(),
                    source.items().stream().map(Summary::from).toList()
            );
        }
    }

    public record Summary(
            String itemId,
            String auctionId,
            String sellerId,
            String title,
            String category,
            AuctionItemCondition itemCondition,
            AuctionItemReviewStatus reviewStatus,
            int submissionVersion,
            BigDecimal startPrice,
            BigDecimal bidIncrement,
            BigDecimal depositAmount,
            Instant startAt,
            Instant endAt,
            long itemVersion,
            long sessionVersion,
            Instant submittedAt,
            Image coverImage
    ) {
        static Summary from(AuctionAssetQueryService.AdminReviewSummary source) {
            return new Summary(
                    Long.toString(source.itemId()), Long.toString(source.auctionId()),
                    Long.toString(source.sellerId()), source.title(), source.category(), source.itemCondition(),
                    source.reviewStatus(), source.submissionVersion(), source.startPrice(), source.bidIncrement(),
                    source.depositAmount(), source.startAt(), source.endAt(), source.itemVersion(),
                    source.sessionVersion(), source.submittedAt(), Image.from(source.coverImage())
            );
        }
    }

    public record Image(
            String imageId,
            String contentType,
            long contentLength,
            int sortOrder,
            String previewUrl,
            Instant previewExpiresAt
    ) {
        static Image from(AuctionAssetQueryService.ImageView source) {
            if (source == null) {
                return null;
            }
            return new Image(
                    Long.toString(source.imageId()), source.contentType(), source.contentLength(), source.sortOrder(),
                    source.previewUrl() == null ? null : source.previewUrl().toString(), source.previewExpiresAt()
            );
        }

        @Override
        public String toString() {
            return "Image[imageId=" + imageId + ", previewUrl=[REDACTED], previewExpiresAt="
                    + previewExpiresAt + "]";
        }
    }
}
