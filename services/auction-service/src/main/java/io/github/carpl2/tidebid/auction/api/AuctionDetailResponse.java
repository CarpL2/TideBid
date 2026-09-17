package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionDetailQueryService;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AuctionDetailResponse(
        String itemId,
        String auctionId,
        String title,
        String description,
        String category,
        AuctionItemCondition itemCondition,
        AuctionSessionStatus sessionStatus,
        BigDecimal startPrice,
        BigDecimal bidIncrement,
        BigDecimal depositAmount,
        BigDecimal currentPrice,
        BigDecimal displayPrice,
        BigDecimal minimumNextBid,
        long bidCount,
        Instant startAt,
        Instant endAt,
        BigDecimal finalPrice,
        Instant closedAt,
        boolean wonByCurrentUser,
        boolean ownedByCurrentUser,
        List<Image> images,
        Registration myRegistration
) {

    static AuctionDetailResponse from(AuctionDetailQueryService.AuctionDetail source) {
        return new AuctionDetailResponse(
                Long.toString(source.itemId()), Long.toString(source.auctionId()), source.title(),
                source.description(), source.category(), source.itemCondition(), source.sessionStatus(),
                source.startPrice(), source.bidIncrement(), source.depositAmount(), source.currentPrice(),
                source.displayPrice(), source.minimumNextBid(), source.bidCount(), source.startAt(), source.endAt(),
                source.finalPrice(), source.closedAt(), source.wonByCurrentUser(), source.ownedByCurrentUser(),
                source.images().stream().map(Image::from).toList(),
                Registration.from(source.myRegistration())
        );
    }

    public record Image(
            String imageId,
            String contentType,
            long contentLength,
            int sortOrder,
            String previewUrl,
            Instant previewExpiresAt
    ) {
        static Image from(AuctionDetailQueryService.ImageView source) {
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

    public record Registration(
            String registrationId,
            AuctionRegistrationStatus status,
            String failureCode,
            Instant registeredAt
    ) {
        static Registration from(AuctionDetailQueryService.RegistrationView source) {
            return source == null ? null : new Registration(
                    Long.toString(source.registrationId()), source.status(), source.failureCode(),
                    source.registeredAt()
            );
        }
    }
}
