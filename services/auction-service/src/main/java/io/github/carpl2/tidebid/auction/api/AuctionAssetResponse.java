package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionAssetQueryService;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionReviewDecision;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AuctionAssetResponse {

    private AuctionAssetResponse() {
    }

    public record Page(
            int page,
            int size,
            long total,
            long totalPages,
            List<Summary> items
    ) {
        static Page from(AuctionAssetQueryService.PageResult<AuctionAssetQueryService.AssetSummary> source) {
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
            AuctionItemReviewStatus reviewStatus,
            AuctionSessionStatus sessionStatus,
            BigDecimal startPrice,
            BigDecimal currentPrice,
            Instant startAt,
            Instant endAt,
            long itemVersion,
            long sessionVersion,
            Image coverImage,
            Instant createdAt,
            Instant updatedAt
    ) {
        static Summary from(AuctionAssetQueryService.AssetSummary source) {
            return new Summary(
                    Long.toString(source.itemId()), Long.toString(source.auctionId()), source.title(),
                    source.category(), source.itemCondition(), source.reviewStatus(), source.sessionStatus(),
                    source.startPrice(), source.currentPrice(), source.startAt(), source.endAt(),
                    source.itemVersion(), source.sessionVersion(), Image.from(source.coverImage()),
                    source.createdAt(), source.updatedAt()
            );
        }
    }

    public record Detail(
            String itemId,
            String auctionId,
            String sellerId,
            String title,
            String description,
            String category,
            AuctionItemCondition itemCondition,
            AuctionItemReviewStatus reviewStatus,
            int submissionVersion,
            AuctionSessionStatus sessionStatus,
            BigDecimal startPrice,
            BigDecimal bidIncrement,
            BigDecimal depositAmount,
            BigDecimal currentPrice,
            long bidCount,
            Instant startAt,
            Instant endAt,
            long itemVersion,
            long sessionVersion,
            Instant submittedAt,
            Instant approvedAt,
            Instant createdAt,
            Instant updatedAt,
            List<Image> images,
            Review latestReview
    ) {
        static Detail from(AuctionAssetQueryService.AssetDetail source) {
            return new Detail(
                    Long.toString(source.itemId()), Long.toString(source.auctionId()),
                    Long.toString(source.sellerId()), source.title(), source.description(), source.category(),
                    source.itemCondition(), source.reviewStatus(), source.submissionVersion(), source.sessionStatus(),
                    source.startPrice(), source.bidIncrement(), source.depositAmount(), source.currentPrice(),
                    source.bidCount(), source.startAt(), source.endAt(), source.itemVersion(), source.sessionVersion(),
                    source.submittedAt(), source.approvedAt(), source.createdAt(), source.updatedAt(),
                    source.images().stream().map(Image::from).toList(), Review.from(source.latestReview())
            );
        }
    }

    public record Image(
            String imageId,
            String objectKey,
            String originalFilename,
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
                    Long.toString(source.imageId()), source.objectKey(), source.originalFilename(),
                    source.contentType(), source.contentLength(), source.sortOrder(),
                    source.previewUrl() == null ? null : source.previewUrl().toString(),
                    source.previewExpiresAt()
            );
        }

        @Override
        public String toString() {
            return "Image[imageId=" + imageId + ", objectKey=" + objectKey
                    + ", previewUrl=[REDACTED], previewExpiresAt=" + previewExpiresAt + "]";
        }
    }

    public record Review(
            int submissionVersion,
            AuctionReviewDecision decision,
            String comment,
            Instant reviewedAt
    ) {
        static Review from(AuctionAssetQueryService.ReviewFeedback source) {
            return source == null
                    ? null
                    : new Review(source.submissionVersion(), source.decision(), source.comment(), source.reviewedAt());
        }
    }
}
